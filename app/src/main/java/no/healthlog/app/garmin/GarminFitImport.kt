package no.healthlog.app.garmin

import no.healthlog.app.data.HealthDay
import no.healthlog.app.data.manualOverrideSet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/*
 * Import av Garmin Connect-eksport (zip med .fit-filer, én zip per dag). Det viktige her
 * er måledataene Garmin IKKE deler med Health Connect: søvnscore, HRV over natten, Body
 * Battery og stress. Søvnfaser og hvilepuls tas med også, siden de kommer rett fra kilden.
 */

// Globale meldingsnumre i FIT-profilen.
private const val MESG_EVENT = 21
private const val MESG_MONITORING_HR_DATA = 211
private const val MESG_STRESS_LEVEL = 227
private const val MESG_SLEEP_LEVEL = 275
private const val MESG_SLEEP_ASSESSMENT = 346
private const val MESG_HRV_STATUS_SUMMARY = 370

private const val EVENT_SLEEP = 74L
private const val EVENT_TYPE_START = 0L
private const val EVENT_TYPE_STOP = 1L

/**
 * Stress/Body Battery lagres hvert minutt. En dag regnes bare med når den har minst to
 * timer med målinger - ellers ville siste minutt før midnatt (som havner i neste dags
 * fil-sett) gi en "dag" med stress og Body Battery fra ett eneste minutt.
 */
internal const val MIN_STRESS_READINGS_PER_DAY = 120

/** Det appen henter ut for én dag. null = fantes ikke i filene. */
data class GarminDayData(
    val date: LocalDate,
    val sleepScore: Int? = null,
    val hrvMs: Double? = null,
    val bodyBatteryMax: Int? = null,
    val bodyBatteryMin: Int? = null,
    val stress: Int? = null,
    val restingHeartRate: Int? = null,
    val sleepMinutes: Int? = null,
    val deepSleepMinutes: Int? = null,
    val lightSleepMinutes: Int? = null,
    val remSleepMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null
)

private fun fitTimeToDate(fitSeconds: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochSecond(fitSeconds + FIT_EPOCH_OFFSET_SECONDS).atZone(zone).toLocalDate()

private class Night(val end: Long, val minutesByLevel: Map<Long, Int>, val score: Int?)

/** Én søvnfil: fasene (1 våken, 2 lett, 3 dyp, 4 REM) fra hver overgang til neste. */
private fun readNight(messages: List<FitMessage>): Night? {
    val levels = messages
        .filter { it.globalNumber == MESG_SLEEP_LEVEL && it[FIT_TIMESTAMP_FIELD] != null && it[0] != null }
        .sortedBy { it[FIT_TIMESTAMP_FIELD] }
    if (levels.isEmpty()) return null
    val sleepEvents = messages.filter { it.globalNumber == MESG_EVENT && it[0] == EVENT_SLEEP }
    val stop = sleepEvents.filter { it[1] == EVENT_TYPE_STOP }.mapNotNull { it[FIT_TIMESTAMP_FIELD] }.maxOrNull()
    val end = maxOf(stop ?: 0L, levels.last()[FIT_TIMESTAMP_FIELD]!!)

    val minutes = mutableMapOf<Long, Int>()
    levels.forEachIndexed { i, level ->
        val from = level[FIT_TIMESTAMP_FIELD]!!
        val to = if (i + 1 < levels.size) levels[i + 1][FIT_TIMESTAMP_FIELD]!! else end
        val m = ((to - from) / 60).toInt()
        if (m > 0) minutes[level[0]!!] = (minutes[level[0]!!] ?: 0) + m
    }
    val score = messages.firstOrNull { it.globalNumber == MESG_SLEEP_ASSESSMENT }?.get(6)?.toInt()
    return Night(end, minutes, score)
}

/**
 * Slår sammen alle meldinger fra en eller flere eksporter til én oppsummering per dag
 * (lokal dato i [zone]). Søvn og HRV havner på dagen natten SLUTTER, som i resten av appen.
 */
fun summarizeGarminFiles(files: List<List<FitMessage>>, zone: ZoneId): List<GarminDayData> {
    val result = mutableMapOf<LocalDate, GarminDayData>()
    fun update(date: LocalDate, change: (GarminDayData) -> GarminDayData) {
        result[date] = change(result[date] ?: GarminDayData(date))
    }

    // Søvn: lengste natt per dato (en kort lur skal ikke erstatte nattesøvnen).
    files.mapNotNull(::readNight)
        .groupBy { fitTimeToDate(it.end, zone) }
        .forEach { (date, nights) ->
            val night = nights.maxBy { n -> n.minutesByLevel.filterKeys { it != 1L }.values.sum() }
            val deep = night.minutesByLevel[3L]
            val light = night.minutesByLevel[2L]
            val rem = night.minutesByLevel[4L]
            val asleep = listOfNotNull(deep, light, rem).sum().takeIf { it > 0 }
            update(date) {
                it.copy(
                    sleepScore = night.score,
                    sleepMinutes = asleep,
                    deepSleepMinutes = deep ?: 0,
                    lightSleepMinutes = light ?: 0,
                    remSleepMinutes = rem ?: 0,
                    sleepAwakeMinutes = night.minutesByLevel[1L] ?: 0
                )
            }
        }

    val all = files.flatten()

    // HRV: snittet for natten (Garmins "siste natts gjennomsnitt"), skala 1/128 ms.
    all.filter { it.globalNumber == MESG_HRV_STATUS_SUMMARY }
        .mapNotNull { m -> m[FIT_TIMESTAMP_FIELD]?.let { ts -> m[1]?.let { ts to it / 128.0 } } }
        .groupBy({ fitTimeToDate(it.first, zone) }, { it })
        .forEach { (date, values) -> update(date) { it.copy(hrvMs = values.maxBy { v -> v.first }.second) } }

    // Hvilepuls: siste "dagens hvilepuls" per dato (oppdateres utover dagen).
    all.filter { it.globalNumber == MESG_MONITORING_HR_DATA }
        .mapNotNull { m -> m[FIT_TIMESTAMP_FIELD]?.let { ts -> (m[1] ?: m[0])?.let { ts to it.toInt() } } }
        .groupBy({ fitTimeToDate(it.first, zone) }, { it })
        .forEach { (date, values) -> update(date) { it.copy(restingHeartRate = values.maxBy { v -> v.first }.second) } }

    // Stress (felt 0) og Body Battery (felt 3, udokumentert men stabilt) per minutt.
    // Negative stressverdier betyr "aktivitet/ikke målbart" og holdes utenfor snittet.
    all.filter { it.globalNumber == MESG_STRESS_LEVEL && it[1] != null }
        .distinctBy { it[1] }
        .groupBy { fitTimeToDate(it[1]!!, zone) }
        .filterValues { it.size >= MIN_STRESS_READINGS_PER_DAY }
        .forEach { (date, readings) ->
            val stress = readings.mapNotNull { it[0] }.filter { it in 0..100 }
            val battery = readings.mapNotNull { it[3] }.filter { it in 0..100 }
            update(date) {
                it.copy(
                    stress = stress.takeIf { s -> s.isNotEmpty() }?.average()?.roundToInt(),
                    bodyBatteryMax = battery.maxOrNull()?.toInt(),
                    bodyBatteryMin = battery.minOrNull()?.toInt()
                )
            }
        }

    return result.values.sortedBy { it.date }
}

/**
 * Pakker ut .fit-filer fra en zip (også zip-filer inni zip-en, slik den store "Last ned
 * dine data"-eksporten er bygget opp). En vanlig .fit-fil returneres som den er.
 */
fun extractFitFiles(bytes: ByteArray, maxDepth: Int = 3): List<ByteArray> {
    val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    if (!isZip) return if (looksLikeFit(bytes)) listOf(bytes) else emptyList()
    if (maxDepth == 0) return emptyList()

    val out = mutableListOf<ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name.lowercase()
            if (!name.endsWith(".fit") && !name.endsWith(".zip")) continue
            val content = ByteArrayOutputStream().also { zip.copyTo(it) }.toByteArray()
            out += extractFitFiles(content, maxDepth - 1)
        }
    }
    return out
}

private fun looksLikeFit(bytes: ByteArray): Boolean =
    bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT"

/**
 * Fletter Garmin-data inn i en eksisterende dag. Garmin er originalkilden, så verdiene
 * overskriver det som ligger der - unntatt felter du har rettet manuelt i Historikk.
 * Felter Garmin ikke har for dagen beholdes.
 */
fun mergeGarminDay(old: HealthDay?, garmin: GarminDayData): HealthDay {
    val overrides = old.manualOverrideSet()
    fun <T> pick(field: String, new: T?, current: T?): T? =
        if (field in overrides) current else (new ?: current)

    val base = old ?: HealthDay(date = garmin.date.toString(), source = "garmin-file")
    return base.copy(
        sleepScore = pick("sleepScore", garmin.sleepScore, base.sleepScore),
        hrvMs = pick("hrvMs", garmin.hrvMs, base.hrvMs),
        bodyBatteryMax = pick("bodyBatteryMax", garmin.bodyBatteryMax, base.bodyBatteryMax),
        bodyBatteryMin = pick("bodyBatteryMin", garmin.bodyBatteryMin, base.bodyBatteryMin),
        stress = pick("stress", garmin.stress, base.stress),
        restingHeartRate = pick("restingHeartRate", garmin.restingHeartRate, base.restingHeartRate),
        sleepMinutes = pick("sleepMinutes", garmin.sleepMinutes, base.sleepMinutes),
        deepSleepMinutes = garmin.deepSleepMinutes ?: base.deepSleepMinutes,
        lightSleepMinutes = garmin.lightSleepMinutes ?: base.lightSleepMinutes,
        remSleepMinutes = garmin.remSleepMinutes ?: base.remSleepMinutes,
        sleepAwakeMinutes = garmin.sleepAwakeMinutes ?: base.sleepAwakeMinutes,
        updatedAt = System.currentTimeMillis()
    )
}
