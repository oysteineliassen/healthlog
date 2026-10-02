package no.healthlog.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_days")
data class HealthDay(
    @PrimaryKey val date: String,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    // Fra en smartvekt via Health Connect (f.eks. Garmin Index), i prosent.
    val bodyFatPercent: Double? = null,
    val hrvMs: Double? = null,
    val sleepMinutes: Int? = null,
    val sleepScore: Int? = null,
    val restingHeartRate: Int? = null,
    // Snittpuls gjennom HELE dagen (alle pulsmålinger fra Health Connect, ikke bare
    // hvilepuls eller puls under trening) - se HealthConnectManager for utregningen.
    val avgHeartRate: Int? = null,
    // Minutter med "aktiv" skritt-rate (rask gange-tempo eller mer) gjennom dagen,
    // regnet ut fra Health Connect sine egne skritt-databolker - UAVHENGIG av formelt
    // loggede treningsøkter. Fanger opp f.eks. en lang gåtur som aldri ble logget som
    // "trening" i Garmin. Se HealthConnectManager for selve utregningen.
    val activeMinutes: Int? = null,
    // Søvnstadier fra Health Connect, kun tilgjengelig når kilden (f.eks. Garmin)
    // faktisk rapporterer detaljerte stadier - null hvis ikke. sleepMinutes over er
    // allerede redusert for våken tid når stadiedata finnes, se HealthConnectManager.
    val deepSleepMinutes: Int? = null,
    val lightSleepMinutes: Int? = null,
    val remSleepMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null,
    val bodyBatteryMax: Int? = null,
    val bodyBatteryMin: Int? = null,
    val stress: Int? = null,
    val steps: Int? = null,
    val trainingMinutes: Int? = null,
    val trainingIntensity: String? = null,
    val trainingType: String? = null,
    // Full liste over ALLE treningsøkter den dagen (ikke bare den lengste, som
    // trainingType/trainingIntensity over fortsatt representerer for enkelhets skyld
    // i grafer/AI-trener/rekorder). Brukes kun til visning i "I dag" og Historikk -
    // se TrainingSession/trainingSessionList()/toTrainingSessionsString() under.
    val trainingSessions: String? = null,
    val morningNote: String? = null,
    val eveningNote: String? = null,
    val dayTag: String? = null,
    val nutritionLevel: String? = null,
    // Egne vane-merker (f.eks. "Alkohol|Sen middag"). null = ingenting registrert den
    // dagen, "" = registrert, men uten merker - skillet brukes av AI-treneren, som bare
    // sammenligner dager der du faktisk tok stilling til merkene. Se habitTagSet().
    val habitTags: String? = null,
    // Kommaseparert liste over feltnavn (f.eks. "steps,restingHeartRate") som er
    // manuelt rettet i Historikk og derfor IKKE skal overskrives neste gang Health
    // Connect-synk kjører. Se manualOverrideSet()/toManualOverridesString() under.
    val manualOverrides: String? = null,
    val source: String = "manual",
    val updatedAt: Long = System.currentTimeMillis()
)

fun HealthDay?.manualOverrideSet(): Set<String> =
    this?.manualOverrides?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

fun Set<String>.toManualOverridesString(): String? =
    if (isEmpty()) null else joinToString(",")

fun HealthDay?.habitTagSet(): Set<String> =
    this?.habitTags?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

/** Tomt sett gir "" (ikke null) - se kommentaren ved habitTags. "|" i et navn byttes ut. */
fun Set<String>.toHabitTagsString(): String =
    map { it.replace("|", "/").trim() }.filter { it.isNotEmpty() }.sorted().joinToString("|")

/** Én enkelt treningsøkt: type + varighet i minutter + en eventuell intensitetsvurdering. */
data class TrainingSession(val type: String, val minutes: Int, val intensity: String?)

/**
 * "type:minutter:intensitet" per økt, adskilt med "|" - enkel tekstserialisering
 * konsekvent med hvordan manualOverrides over gjør det, i stedet for en egen tabell
 * bare for dette. Ugyldige/uleselige oppføringer hoppes stille over.
 */
fun HealthDay?.trainingSessionList(): List<TrainingSession> {
    val raw = this?.trainingSessions ?: return emptyList()
    return raw.split("|").mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size != 3) return@mapNotNull null
        val minutes = parts[1].toIntOrNull() ?: return@mapNotNull null
        TrainingSession(type = parts[0], minutes = minutes, intensity = parts[2].ifBlank { null })
    }
}

fun List<TrainingSession>.toTrainingSessionsString(): String? =
    if (isEmpty()) null else joinToString("|") { "${it.type}:${it.minutes}:${it.intensity ?: ""}" }
