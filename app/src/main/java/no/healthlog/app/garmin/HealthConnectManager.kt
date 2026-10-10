package no.healthlog.app.garmin

import no.healthlog.app.GoalPreferences
import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import no.healthlog.app.TrainingIntensity
import no.healthlog.app.TrainingType
import no.healthlog.app.data.TrainingSession
import no.healthlog.app.data.toTrainingSessionsString
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

private const val TAG = "HealthConnectDebug"
// "_v2": økt da kroppsfett kom til, slik at de som allerede er spurt om HRV spørres én gang til.
private const val KEY_OPTIONAL_ASKED = "hc_optional_permissions_asked_v2"

/**
 * Det Health Connect faktisk kan gi oss fra Garmin: skritt, søvnvarighet, hvilepuls, vekt
 * og treningsøkter (varighet, en grov intensitetsvurdering, og treningstype - se
 * readRange() og mapExerciseType()). Body Battery og stress er Garmin-proprietære og
 * deles ikke via Health Connect - de forblir manuelle felt (se ManualEntryInput /
 * EntryDialog / EditHistoryDialog). HRV hentes hvis en kilde har lagt den i Health
 * Connect (mange klokker/apper gjør det, Garmin ikke nødvendigvis) - ellers manuelt.
 */
/** Se readRange for utregningen - sleepMinutes er allerede redusert for våken tid når stadiedata finnes. */
private data class SleepDayTotals(
    val sleepMinutes: Int,
    val deepMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val remMinutes: Int? = null,
    val awakeMinutes: Int? = null
)

data class HealthConnectDaySnapshot(
    val steps: Int? = null,
    val activeMinutes: Int? = null,
    val sleepMinutes: Int? = null,
    val deepSleepMinutes: Int? = null,
    val lightSleepMinutes: Int? = null,
    val remSleepMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null,
    val restingHeartRate: Int? = null,
    val avgHeartRate: Int? = null,
    val hrvMs: Double? = null,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
    val trainingMinutes: Int? = null,
    val trainingIntensity: String? = null,
    val trainingType: String? = null,
    // Serialisert liste over ALLE økter den dagen (se TrainingSession i HealthDay.kt) -
    // trainingType/trainingIntensity over representerer fortsatt bare den lengste
    // økten, brukt av grafer/AI-trener/rekorder som forventer én verdi per dag.
    val trainingSessions: String? = null
)

/**
 * Health Connect har rundt 60 spesifikke exerciseType-koder - dekker bare de vanligste
 * her, med "annet" som trygg fallback. NB: dette API-et har endret konstantnavn mellom
 * versjoner før (se merknaden om permission-typer over) - stemmer ikke navnene under med
 * det som faktisk finnes i din versjon av biblioteket, fjern/juster de aktuelle linjene.
 */
private fun mapExerciseType(exerciseType: Int): String = when (exerciseType) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> TrainingType.RUNNING
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> TrainingType.WALKING
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> TrainingType.BIKING
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> TrainingType.STRENGTH_TRAINING
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> TrainingType.SWIMMING
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> TrainingType.YOGA
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> TrainingType.HIKING
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> TrainingType.HIIT
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> TrainingType.ELLIPTICAL
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> TrainingType.ROWING
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> TrainingType.SKIING
    else -> TrainingType.OTHER
}

class HealthConnectManager(private val context: Context) {

    // Permission-typene Health Connect-biblioteket forventer endrer seg litt mellom
    // alpha-versjoner (dette er fortsatt et raskt bevegelig API i 2026) – stemmer ikke
    // navnene helt med det autofullføring foreslår, sjekk gjeldende SKILL/dokumentasjon
    // for versjonen du faktisk har i build.gradle.
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        // Lar oss dele manuelt registrert vekt tilbake til Health Connect, slik at andre
        // apper (Samsung Health, Google Fit osv.) også ser den - se writeWeight() under.
        HealthPermission.getWritePermission(WeightRecord::class)
    )

    /**
     * Valgfri: HRV. Holdes BEVISST utenfor [permissions] over - hasAllPermissions() sjekker
     * den listen, og en ny påkrevd tillatelse ville fått bakgrunnssynken til å stoppe
     * for alle som allerede har gitt de gamle, helt til de ga den nye også.
     */
    val hrvPermission: String = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)

    /** Valgfri: kroppsfett fra smartvekt. Samme begrunnelse som for HRV over. */
    val bodyFatPermission: String = HealthPermission.getReadPermission(BodyFatRecord::class)

    val optionalPermissions: Set<String> get() = setOf(hrvPermission, bodyFatPermission)

    /** Det tillatelsesdialogen ber om: de påkrevde + de valgfrie. */
    val permissionsToRequest: Set<String> get() = permissions + optionalPermissions

    // Egen, valgfri tillatelse for bakgrunnssynk – bes om separat fra de fire over,
    // siden vanlig (manuell, i forgrunnen) synk fungerer helt fint uten den.
    val backgroundPermission: String = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    fun sdkStatus(): Int =
        HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun hasHrvPermission(): Boolean =
        hrvPermission in client.permissionController.getGrantedPermissions()

    suspend fun hasBodyFatPermission(): Boolean =
        bodyFatPermission in client.permissionController.getGrantedPermissions()

    suspend fun hasAllOptionalPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(optionalPermissions)

    /**
     * Brukere som ga tillatelser før de valgfrie (HRV, kroppsfett) kom, har aldri blitt
     * spurt om dem. De spørres ÉN gang ved neste manuelle synk - ikke hver gang, om de
     * sier nei.
     */
    fun shouldAskForOptionalPermissionsOnce(): Boolean {
        val prefs = context.getSharedPreferences("healthlog_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OPTIONAL_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_OPTIONAL_ASKED, true).apply()
        return true
    }

    suspend fun hasBackgroundPermission(): Boolean =
        backgroundPermission in client.permissionController.getGrantedPermissions()

    fun isBackgroundReadFeatureAvailable(): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    /**
     * readRecords() svarer maks 1000 poster per kall og gir da et pageToken tilbake –
     * uten å følge det tapes resten av dataene stille, uten noen feilmelding. Løkker
     * derfor til pageToken er tom/null (Health Connect kan gi "" i stedet for null
     * avhengig av versjon, derfor isNullOrEmpty()).
     */
    private suspend fun <T : Record> readAllRecords(
        recordType: KClass<T>,
        range: TimeRangeFilter
    ): List<T> {
        val all = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(recordType, range, pageToken = pageToken)
            )
            all.addAll(response.records)
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return all
    }

    /**
     * Utvider selve spørringsvinduet til å starte DAGEN FØR [date], siden en søvnøkt
     * som startet i går kveld og sluttet tidlig i dag ellers kan falle helt utenfor
     * spørringen (Health Connect sitt TimeRangeFilter ser ut til å kreve at øktens eget
     * STARTTIDSPUNKT er innenfor vinduet, ikke bare at den overlapper det) - selv om
     * skritt og andre kortere databolker ikke rammes av det samme, siden de sjelden
     * strekker seg over midnatt. Bruker likevel fortsatt kun [date] sitt eget oppslag
     * fra resultatet under, siden endTime-basert gruppering (se readRange) allerede
     * sørger for at en slik økt havner på riktig (opplevd) dag i seg selv.
     */
    suspend fun readDay(date: LocalDate): HealthConnectDaySnapshot =
        readRange(date.minusDays(1), date)[date] ?: HealthConnectDaySnapshot()

    /**
     * Leser skritt/søvn/hvilepuls/vekt for hele [startDate]..[endDateInclusive] i fire
     * spørringer totalt (én per datatype), ikke én spørring per dag – viktig når dette
     * brukes til å hente flere ukers historikk.
     */
    suspend fun readRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate
    ): Map<LocalDate, HealthConnectDaySnapshot> {
        val zone = ZoneId.systemDefault()
        val start = startDate.atStartOfDay(zone).toInstant()
        val end = endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)

        val stepsRecords = readAllRecords(StepsRecord::class, range)
        Log.d(TAG, "readRange steps: ${stepsRecords.size} rådata-poster mellom $startDate og $endDateInclusive")
        stepsRecords.forEach { Log.d(TAG, "  steg-post: startTime=${it.startTime} count=${it.count}") }
        // Skriver både telefonen og klokka skritt til Health Connect, ligger de samme
        // skrittene der to ganger. Per dag brukes derfor bare ÉN kilde - den med flest
        // skritt (normalt klokka, som også teller når telefonen ligger igjen).
        val stepsRecordsByDay = stepsRecords
            .groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, records) -> recordsFromMainSource(records, { it.metadata.dataOrigin.packageName }, { it.count }) }
        val stepsByDay = stepsRecordsByDay
            .mapValues { (_, records) -> records.sumOf { it.count }.toInt() }

        val sleepRecords = readAllRecords(SleepSessionRecord::class, range)
        Log.d(TAG, "readRange søvn: ${sleepRecords.size} rådata-poster")
        sleepRecords.forEach { Log.d(TAG, "  søvn-post: startTime=${it.startTime} endTime=${it.endTime} stadier=${it.stages.size}") }
        // Grupperes på når man VÅKNET (endTime), ikke når man sovnet (startTime).
        // Health Connect selv tilskriver økter til startdatoen, men det fører til at en
        // natt som starter sent én dag og en annen natt som slutter tidlig samme dag
        // begge havner på samme kalenderdag og telles sammen – altså dobbelt opp.
        //
        // sleepMinutes trekker fra våken tid NÅR kilden rapporterer detaljerte stadier
        // (ikke alle gjør det) - ellers faller den tilbake til rå økt-varighet som før.
        // deep/light/rem/awake forblir null for netter uten stadiedata, i stedet for 0,
        // for å skille "ingen detaljer tilgjengelig" fra "faktisk null minutter".
        val sleepByDay = sleepRecords
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, records) ->
                var hasStageData = false
                var totalAwake = 0L
                var totalDeep = 0L
                var totalLight = 0L
                var totalRem = 0L
                var totalSessionMinutes = 0L

                records.forEach { record ->
                    totalSessionMinutes += Duration.between(record.startTime, record.endTime).toMinutes()
                    if (record.stages.isNotEmpty()) hasStageData = true
                    record.stages.forEach { stage ->
                        val stageMinutes = Duration.between(stage.startTime, stage.endTime).toMinutes()
                        when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> totalAwake += stageMinutes
                            SleepSessionRecord.STAGE_TYPE_DEEP -> totalDeep += stageMinutes
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> totalLight += stageMinutes
                            SleepSessionRecord.STAGE_TYPE_REM -> totalRem += stageMinutes
                        }
                    }
                }

                if (hasStageData) {
                    SleepDayTotals(
                        sleepMinutes = (totalSessionMinutes - totalAwake).toInt().coerceAtLeast(0),
                        deepMinutes = totalDeep.toInt(),
                        lightMinutes = totalLight.toInt(),
                        remMinutes = totalRem.toInt(),
                        awakeMinutes = totalAwake.toInt()
                    )
                } else {
                    SleepDayTotals(sleepMinutes = totalSessionMinutes.toInt())
                }
            }

        val restingHrRecords = readAllRecords(RestingHeartRateRecord::class, range)
        Log.d(TAG, "readRange hvilepuls: ${restingHrRecords.size} rådata-poster")
        val restingHrByDay = restingHrRecords
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .mapValues { (_, records) -> records.last().beatsPerMinute.toInt() }

        // HRV (RMSSD) - bare hvis tillatelsen er gitt; uten den ville lesingen kastet
        // SecurityException og stoppet HELE synken. Flere målinger samme dag (typisk
        // gjennom natta) slås sammen til ett snitt, lagt på dagen natten SLUTTER (som
        // søvnen) - målinger før midnatt hører til natten som ender neste morgen.
        val hrvByDay: Map<LocalDate, Double> = if (hasHrvPermission()) {
            runCatching { readAllRecords(HeartRateVariabilityRmssdRecord::class, range) }
                .getOrElse { e ->
                    Log.w(TAG, "Kunne ikke lese HRV", e)
                    emptyList()
                }
                .groupBy { nightDate(it.time.atZone(zone).toLocalDateTime()) }
                .mapValues { (_, records) -> records.map { it.heartRateVariabilityMillis }.average() }
        } else {
            emptyMap()
        }
        Log.d(TAG, "readRange HRV: ${hrvByDay.size} dager med verdi")

        // Kroppsfett - også valgfritt; siste måling per dag.
        val bodyFatByDay: Map<LocalDate, Double> = if (hasBodyFatPermission()) {
            runCatching { readAllRecords(BodyFatRecord::class, range) }
                .getOrElse { e ->
                    Log.w(TAG, "Kunne ikke lese kroppsfett", e)
                    emptyList()
                }
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .mapValues { (_, records) -> records.maxBy { it.time }.percentage.value }
        } else {
            emptyMap()
        }

        val weightRecords = readAllRecords(WeightRecord::class, range)
        Log.d(TAG, "readRange vekt: ${weightRecords.size} rådata-poster")
        val weightByDay = weightRecords
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .mapValues { (_, records) -> records.last().weight.inKilograms }

        // Samme økt kan ligge i Health Connect flere ganger når flere apper har skrevet
        // den (f.eks. Garmin Connect og Google Fit/Strava) - da ble den vist dobbelt og
        // treningsminuttene telt to ganger. Overlappende økter slås sammen til én.
        val rawExerciseRecords = readAllRecords(ExerciseSessionRecord::class, range)
        val maxHeartRate = GoalPreferences.getMaxHeartRate(context)
        val exerciseRecords = dedupeOverlappingSessions(rawExerciseRecords, { it.startTime }, { it.endTime })
        Log.d(TAG, "readRange trening: ${rawExerciseRecords.size} rådata-poster, ${exerciseRecords.size} etter duplikatfjerning")
        val trainingMinutesByDay = exerciseRecords
            .groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, records) ->
                records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
            }

        // Kontinuerlige pulsmålinger, brukt til å anslå hvor hardt en treningsøkt faktisk
        // var. Health Connect pakker målinger i vilkårlige serier (ikke nødvendigvis én
        // serie per treningsøkt), så alle enkeltmålinger flates ut til (tidspunkt, bpm)
        // først, og matches mot økter etterpå i stedet for å anta noe om hvordan de er
        // gruppert i utgangspunktet.
        val heartRateRecords = readAllRecords(HeartRateRecord::class, range)
        Log.d(TAG, "readRange puls under trening: ${heartRateRecords.size} rådata-poster")
        val heartRateSamples = heartRateRecords.flatMap { record ->
            record.samples.map { it.time to it.beatsPerMinute }
        }

        // "Aktive minutter" fra skritt-tempo og puls, uavhengig av loggede treningsøkter -
        // se activeMinutesFromSteps. Pulsen per skritt-periode er snittet av målingene i
        // samme tidsrom (Garmin skriver skritt per minutt, så det blir minuttpulsen).
        val heartRateByMinute = heartRateSamples
            .groupBy { (time, _) -> time.epochSecond / 60 }
            .mapValues { (_, samples) -> samples.map { (_, bpm) -> bpm }.average() }
        val activeStepsThreshold = GoalPreferences.getActiveStepsPerMinute(context).toDouble()
        val maxHeartRateSetting = GoalPreferences.getMaxHeartRate(context)
        val activeMinutesByDay = stepsRecordsByDay
            .mapValues { (date, records) ->
                activeMinutesFromSteps(
                    records.map { r ->
                        val fromMinute = r.startTime.epochSecond / 60
                        val toMinute = (r.endTime.epochSecond - 1) / 60
                        val hr = (fromMinute..maxOf(fromMinute, toMinute)).mapNotNull { heartRateByMinute[it] }
                        StepPeriod(Duration.between(r.startTime, r.endTime).seconds, r.count, hr.takeIf { it.isNotEmpty() }?.average())
                    },
                    activeStepsThreshold,
                    restingHrByDay[date],
                    maxHeartRateSetting
                )
            }

        // Snittpuls for HELE dagen (i motsetning til bpmDuringExercise/bpmDuringSession
        // under, som kun ser på pulsen under selve treningsøktene) - grupperer alle
        // enkeltmålinger etter hvilken dato de falt på, uavhengig av treningsøkter.
        val avgHeartRateByDay = heartRateSamples
            .groupBy { (time, _) -> time.atZone(zone).toLocalDate() }
            .mapValues { (_, samples) -> samples.map { (_, bpm) -> bpm }.average().toInt() }

        // Grov intensitetsvurdering: IKKE en klinisk VO2max-beregning (det krever
        // makspuls/alder, som vi ikke har) - bare hvor mye pulsen under økten var forhøyet
        // over DIN egen hvilepuls den dagen. Gir "lett/moderat/høy" som en fornuftig
        // tommelfingerregel, personlig for deg fremfor et generisk bpm-tall for alle.
        val trainingIntensityByDay = exerciseRecords
            .groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapNotNull { (date, sessionsThatDay) ->
                val restingHr = restingHrByDay[date]
                val bpmDuringExercise = sessionsThatDay.flatMap { session ->
                    heartRateSamples
                        .filter { (time, _) -> !time.isBefore(session.startTime) && !time.isAfter(session.endTime) }
                        .map { (_, bpm) -> bpm }
                }
                val intensity = classifyIntensity(bpmDuringExercise, restingHr, maxHeartRate) ?: return@mapNotNull null
                date to intensity
            }
            .toMap()

        // Ved flere økter samme dag (f.eks. en løpetur og en styrkeøkt) brukes typen til
        // den LENGSTE økten som "dagens treningstype" (trainingType) - det enkeltfeltet
        // brukes fortsatt av grafer/AI-trener/rekorder som forventer én verdi per dag.
        // trainingSessions under gir derimot ALLE øktene, til visning i "I dag"/Historikk.
        val trainingTypeByDay = exerciseRecords
            .groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, sessionsThatDay) ->
                sessionsThatDay
                    .maxByOrNull { Duration.between(it.startTime, it.endTime) }
                    ?.let { mapExerciseType(it.exerciseType) }
            }

        val trainingSessionsByDay = exerciseRecords
            .groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (date, sessionsThatDay) ->
                val restingHr = restingHrByDay[date]
                sessionsThatDay.map { session ->
                    val bpmDuringSession = heartRateSamples
                        .filter { (time, _) -> !time.isBefore(session.startTime) && !time.isAfter(session.endTime) }
                        .map { (_, bpm) -> bpm }
                    TrainingSession(
                        type = mapExerciseType(session.exerciseType),
                        minutes = Duration.between(session.startTime, session.endTime).toMinutes().toInt(),
                        intensity = classifyIntensity(bpmDuringSession, restingHr, maxHeartRate)
                    )
                }
            }

        val allDates = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDateInclusive) }

        val result = allDates.associateWith { date ->
            HealthConnectDaySnapshot(
                steps = stepsByDay[date],
                activeMinutes = activeMinutesByDay[date],
                sleepMinutes = sleepByDay[date]?.sleepMinutes,
                deepSleepMinutes = sleepByDay[date]?.deepMinutes,
                lightSleepMinutes = sleepByDay[date]?.lightMinutes,
                remSleepMinutes = sleepByDay[date]?.remMinutes,
                sleepAwakeMinutes = sleepByDay[date]?.awakeMinutes,
                restingHeartRate = restingHrByDay[date],
                avgHeartRate = avgHeartRateByDay[date],
                hrvMs = hrvByDay[date],
                bodyFatPercent = bodyFatByDay[date],
                weightKg = weightByDay[date],
                trainingMinutes = trainingMinutesByDay[date],
                trainingIntensity = trainingIntensityByDay[date],
                trainingType = trainingTypeByDay[date],
                trainingSessions = trainingSessionsByDay[date]?.toTrainingSessionsString()
            )
        }
        result.forEach { (date, snapshot) -> Log.d(TAG, "readRange resultat $date: $snapshot") }
        return result
    }

    /**
     * Rå pulsmålinger for ÉN spesifikk dag, med tidsstempel - til bruk i "puls gjennom
     * dagen"-grafen på I dag-fanen. Hentes LIVE hver gang grafen faktisk vises, lagres
     * aldri i vår egen database (Health Connect har allerede denne detaljen selv - å
     * duplisere alt ville blitt ~700 rader per dag for null reell gevinst).
     */
    suspend fun readIntradayHeartRate(date: LocalDate): List<Pair<Instant, Long>> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)

        val records = readAllRecords(HeartRateRecord::class, range)
        return records
            .flatMap { record -> record.samples.map { it.time to it.beatsPerMinute } }
            .sortedBy { it.first }
    }

    /**
     * Skriver manuelt registrert vekt tilbake til Health Connect, som noon lokal tid den
     * aktuelle dagen (ingen faktisk klokkeslett finnes for en manuell registrering, så
     * midt på dagen er et nøytralt, entydig valg). Kaster hvis skrivetillatelsen ikke er
     * gitt ennå eller Health Connect ikke er tilgjengelig - kalleren avgjør selv om det
     * skal feile stille (se HealthViewModel.saveManual).
     */
    /**
     * Diagnose: hvordan skrittene for [date] faktisk ligger i Health Connect (lengde og
     * tempo per periode, per kilde). Brukes til å stille inn utregningen av aktive
     * minutter etter hvordan Garmin skriver dataene, i stedet for å gjette.
     */
    suspend fun stepsDiagnostics(date: LocalDate): String {
        val zone = ZoneId.systemDefault()
        val range = TimeRangeFilter.between(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        val records = readAllRecords(StepsRecord::class, range).sortedBy { it.startTime }
        val timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        val sb = StringBuilder("Skritt $date: ${records.size} perioder, ${records.sumOf { it.count }} skritt\n")
        records.groupBy { it.metadata.dataOrigin.packageName }.forEach { (source, list) ->
            val seconds = list.map { Duration.between(it.startTime, it.endTime).seconds }
            sb.append("\nKilde $source: ${list.size} perioder, ${list.sumOf { it.count }} skritt\n")
            sb.append("Lengde: min ${seconds.min()} s, median ${seconds.sorted()[seconds.size / 2]} s, maks ${seconds.max()} s\n")
            val pace = list.map { r -> r.count * 60.0 / Duration.between(r.startTime, r.endTime).seconds.coerceAtLeast(1) }
            sb.append("Tempo: maks ${pace.max().toInt()} skritt/min\n")
            // Minutter per tempo-intervall - brukes til å velge grensen for aktive minutter.
            val bands = listOf(0 to 40, 40 to 60, 60 to 80, 80 to 90, 90 to 100, 100 to 120, 120 to 140, 140 to 1000)
            bands.forEach { (lo, hi) ->
                val minutes = list.zip(pace).filter { (_, p) -> p >= lo && p < hi }
                    .sumOf { (r, _) -> Duration.between(r.startTime, r.endTime).seconds } / 60
                val name = if (hi >= 1000) "$lo+" else "$lo-${hi - 1}"
                sb.append("  $name skritt/min: $minutes min\n")
            }
            sb.append("Største perioder (skritt):\n")
            list.sortedByDescending { it.count }.take(12).sortedBy { it.startTime }.forEach { r ->
                val s = Duration.between(r.startTime, r.endTime).seconds
                sb.append("  ${r.startTime.atZone(zone).format(timeFormat)}  ${s}s  ${r.count} skritt  ${(r.count * 60.0 / s.coerceAtLeast(1)).toInt()}/min\n")
            }
        }
        return sb.toString()
    }

    suspend fun writeWeight(date: LocalDate, weightKg: Double) {
        val zone = ZoneId.systemDefault()
        val time = date.atTime(12, 0).atZone(zone).toInstant()
        val record = WeightRecord(
            weight = Mass.kilograms(weightKg),
            time = time,
            zoneOffset = zone.rules.getOffset(time),
            metadata = Metadata.manualEntry()
        )
        client.insertRecords(listOf(record))
    }
}
