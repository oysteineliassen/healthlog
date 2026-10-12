/*
 * Eksport fra HealthLog til Tallerken
 * ===================================
 * Lager en .json-fil med vekt, midjemål, aktive kalorier og skritt per dag.
 * Filen velges i Tallerken under Profil → «Importer fra HealthLog».
 *
 * Slik legges den inn i HealthLog (eller gi denne fila til Claude Code med
 * beskjed: «Legg inn TallerkenExport.kt i HealthLog og koble den til som
 * beskrevet i toppkommentaren»):
 *
 * 1. Legg fila i app/src/main/java/no/healthlog/app/export/.
 *
 * 2. AndroidManifest.xml – legg til lesetillatelsene som mangler
 *    (HealthLog har trolig allerede STEPS og WEIGHT):
 *      <uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED"/>
 *      <uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED"/>
 *      <uses-permission android:name="android.permission.health.READ_STEPS"/>
 *      <uses-permission android:name="android.permission.health.READ_WEIGHT"/>
 *
 * 3. Legg TallerkenExport.PERMISSIONS til settet HealthLog ber om når
 *    Health Connect-tilgang gis (der de andre tillatelsene ligger i dag).
 *
 * 4. Legg TallerkenExportButton inn på innstillingssiden, og gi den en
 *    funksjon som henter HealthLog sine egne målinger fra Room og gjør dem om
 *    til TallerkenDay(date, weightKg, waistCm). Egne målinger vinner over
 *    vekt fra Health Connect samme dag.
 *
 * Merk: Health Connect lar som standard apper lese data bare 30 dager før
 * tilgangen ble gitt. Eldre aktive kalorier krever den ekstra tillatelsen
 * for helsehistorikk (READ_HEALTH_DATA_HISTORY) på nyere Android-versjoner.
 * Vekt og midjemål fra HealthLog sin egen database er ikke begrenset.
 *
 * Filformat (det Tallerken leser):
 * {"source":"HealthLog","version":1,"exportedAt":"…","days":[
 *   {"date":"2026-10-12","weightKg":82.4,"waistCm":94.0,"activeKcal":640,"totalKcal":2510,"steps":8200}
 * ]}
 * Felter som mangler for en dag utelates. Mangler activeKcal men totalKcal
 * finnes, regner Tallerken aktive kalorier som total minus hvileforbrenning.
 */
package no.healthlog.app.export

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToInt

data class TallerkenDay(
    val date: LocalDate,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val activeKcal: Int? = null,
    val totalKcal: Int? = null,
    val steps: Long? = null,
)

object TallerkenExport {

    /** Health Connect-tillatelser eksporten trenger. */
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    /**
     * Leser aktive og totale kalorier, skritt og vekt per dag fra Health Connect.
     * Dager uten data utelates.
     */
    suspend fun readHealthConnect(
        client: HealthConnectClient,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, TallerkenDay> {
        val days = sortedMapOf<LocalDate, TallerkenDay>()

        // Kalorier og skritt, summert per døgn. Leses i bolker på 90 dager.
        var chunkStart = from
        while (!chunkStart.isAfter(to)) {
            val chunkEnd = minOf(chunkStart.plusDays(89), to)
            val groups = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        StepsRecord.COUNT_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(
                        chunkStart.atStartOfDay(),
                        chunkEnd.plusDays(1).atStartOfDay(),
                    ),
                    timeRangeSlicer = Period.ofDays(1),
                )
            )
            for (g in groups) {
                val active = g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                val total = g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                val steps = g.result[StepsRecord.COUNT_TOTAL]
                if (active == null && total == null && steps == null) continue
                val day = g.startTime.toLocalDate()
                days[day] = TallerkenDay(
                    date = day,
                    activeKcal = active?.roundToInt()?.takeIf { it > 0 },
                    totalKcal = total?.roundToInt()?.takeIf { it > 0 },
                    steps = steps?.takeIf { it > 0 },
                )
            }
            chunkStart = chunkEnd.plusDays(1)
        }

        // Vekt: siste måling hver dag (postene kommer i stigende tidsrekkefølge).
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        from.atStartOfDay(zone).toInstant(),
                        to.plusDays(1).atStartOfDay(zone).toInstant(),
                    ),
                    pageToken = pageToken,
                )
            )
            for (r in response.records) {
                val day = localDate(r.time, r.zoneOffset ?: zone.rules.getOffset(r.time))
                val existing = days[day] ?: TallerkenDay(day)
                days[day] = existing.copy(weightKg = r.weight.inKilograms)
            }
            pageToken = response.pageToken
        } while (pageToken != null)

        return days
    }

    /** Slår sammen Health Connect-data med HealthLog sine egne målinger. Egne målinger vinner. */
    fun merge(healthConnect: Map<LocalDate, TallerkenDay>, manual: List<TallerkenDay>): List<TallerkenDay> {
        val days = healthConnect.toSortedMap()
        for (m in manual) {
            val base = days[m.date] ?: TallerkenDay(m.date)
            days[m.date] = base.copy(
                weightKg = m.weightKg ?: base.weightKg,
                waistCm = m.waistCm ?: base.waistCm,
            )
        }
        return days.values.toList()
    }

    fun toJson(days: List<TallerkenDay>): String {
        val arr = JSONArray()
        for (d in days.sortedBy { it.date }) {
            val o = JSONObject().put("date", d.date.toString())
            d.weightKg?.let { o.put("weightKg", round1(it)) }
            d.waistCm?.let { o.put("waistCm", round1(it)) }
            d.activeKcal?.let { o.put("activeKcal", it) }
            d.totalKcal?.let { o.put("totalKcal", it) }
            d.steps?.let { o.put("steps", it) }
            if (o.length() > 1) arr.put(o)
        }
        return JSONObject()
            .put("source", "HealthLog")
            .put("version", 1)
            .put("exportedAt", Instant.now().toString())
            .put("days", arr)
            .toString()
    }

    /**
     * Bygger hele eksporten for de siste [days] dagene.
     * [manual] er HealthLog sine egne vekt- og midjemålinger fra Room.
     * Uten Health Connect-tilgang ([client] = null) eksporteres bare de egne målingene.
     */
    suspend fun build(
        client: HealthConnectClient?,
        manual: List<TallerkenDay>,
        days: Long = 90,
    ): String {
        val to = LocalDate.now()
        val from = to.minusDays(days - 1)
        val hc = if (client != null && hasPermissions(client)) {
            runCatching { readHealthConnect(client, from, to) }.getOrDefault(emptyMap())
        } else emptyMap()
        return toJson(merge(hc, manual.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }))
    }

    suspend fun hasPermissions(client: HealthConnectClient): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    suspend fun writeTo(context: Context, uri: Uri, json: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("Kunne ikke åpne fila")
    }

    private fun localDate(time: Instant, zone: ZoneId): LocalDate = LocalDateTime.ofInstant(time, zone).toLocalDate()
    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}

/**
 * Knapp som lager eksportfila og lar brukeren lagre den, for eksempel i Nedlastinger.
 * [buildJson] kaller typisk TallerkenExport.build(client, egneMålinger).
 */
@Composable
fun TallerkenExportButton(buildJson: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pending
        pending = null
        if (uri != null && json != null) {
            scope.launch {
                val ok = runCatching { TallerkenExport.writeTo(context, uri, json) }.isSuccess
                Toast.makeText(
                    context,
                    if (ok) "Lagret. Velg fila i Tallerken under Profil." else "Kunne ikke lagre fila.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Button(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                try {
                    pending = buildJson()
                    launcher.launch("tallerken-${LocalDate.now()}.json")
                } catch (e: Exception) {
                    Toast.makeText(context, "Eksporten feilet: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        },
    ) { Text(if (busy) "Henter data …" else "Eksporter til Tallerken") }
}
