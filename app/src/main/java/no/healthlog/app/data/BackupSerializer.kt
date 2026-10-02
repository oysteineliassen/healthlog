package no.healthlog.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private const val BACKUP_VERSION = 1

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

fun healthDaysToJson(days: List<HealthDay>): String {
    val root = JSONObject()
    root.put("version", BACKUP_VERSION)
    root.put("exportedAt", Instant.now().toString())

    val array = JSONArray()
    days.forEach { day ->
        val obj = JSONObject()
        obj.put("date", day.date)
        obj.putOpt("weightKg", day.weightKg)
        obj.putOpt("waistCm", day.waistCm)
        obj.putOpt("bodyFatPercent", day.bodyFatPercent)
        obj.putOpt("hrvMs", day.hrvMs)
        obj.putOpt("sleepMinutes", day.sleepMinutes)
        obj.putOpt("sleepScore", day.sleepScore)
        obj.putOpt("restingHeartRate", day.restingHeartRate)
        obj.putOpt("avgHeartRate", day.avgHeartRate)
        obj.putOpt("activeMinutes", day.activeMinutes)
        obj.putOpt("deepSleepMinutes", day.deepSleepMinutes)
        obj.putOpt("lightSleepMinutes", day.lightSleepMinutes)
        obj.putOpt("remSleepMinutes", day.remSleepMinutes)
        obj.putOpt("sleepAwakeMinutes", day.sleepAwakeMinutes)
        obj.putOpt("bodyBatteryMax", day.bodyBatteryMax)
        obj.putOpt("bodyBatteryMin", day.bodyBatteryMin)
        obj.putOpt("stress", day.stress)
        obj.putOpt("steps", day.steps)
        obj.putOpt("trainingMinutes", day.trainingMinutes)
        obj.putOpt("trainingIntensity", day.trainingIntensity)
        obj.putOpt("trainingType", day.trainingType)
        obj.putOpt("trainingSessions", day.trainingSessions)
        obj.putOpt("morningNote", day.morningNote)
        obj.putOpt("eveningNote", day.eveningNote)
        obj.putOpt("dayTag", day.dayTag)
        obj.putOpt("nutritionLevel", day.nutritionLevel)
        obj.putOpt("habitTags", day.habitTags)
        // Manglet tidligere: uten denne mistet manuelle rettelser beskyttelsen mot
        // Health Connect-synk etter en gjenoppretting, og ble overskrevet ved neste synk.
        obj.putOpt("manualOverrides", day.manualOverrides)
        obj.put("source", day.source)
        obj.put("updatedAt", day.updatedAt)
        array.put(obj)
    }
    root.put("days", array)

    return root.toString(2)
}

/**
 * Kaster ikke ved feil rader - hopper bare over dem, slik at én korrupt linje ikke
 * blokkerer resten av en ellers gyldig gjenoppretting. Kaster derimot hvis selve
 * filen ikke er gyldig JSON i det hele tatt.
 */
fun jsonToHealthDays(json: String): List<HealthDay> {
    val root = JSONObject(json)
    val array = root.optJSONArray("days") ?: JSONArray()

    val result = mutableListOf<HealthDay>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val date = obj.optStringOrNull("date")?.takeIf { it.isNotBlank() } ?: continue

        result.add(
            HealthDay(
                date = date,
                weightKg = obj.optDoubleOrNull("weightKg"),
                waistCm = obj.optDoubleOrNull("waistCm"),
                bodyFatPercent = obj.optDoubleOrNull("bodyFatPercent"),
                hrvMs = obj.optDoubleOrNull("hrvMs"),
                sleepMinutes = obj.optIntOrNull("sleepMinutes"),
                sleepScore = obj.optIntOrNull("sleepScore"),
                restingHeartRate = obj.optIntOrNull("restingHeartRate"),
                avgHeartRate = obj.optIntOrNull("avgHeartRate"),
                activeMinutes = obj.optIntOrNull("activeMinutes"),
                deepSleepMinutes = obj.optIntOrNull("deepSleepMinutes"),
                lightSleepMinutes = obj.optIntOrNull("lightSleepMinutes"),
                remSleepMinutes = obj.optIntOrNull("remSleepMinutes"),
                sleepAwakeMinutes = obj.optIntOrNull("sleepAwakeMinutes"),
                bodyBatteryMax = obj.optIntOrNull("bodyBatteryMax"),
                bodyBatteryMin = obj.optIntOrNull("bodyBatteryMin"),
                stress = obj.optIntOrNull("stress"),
                steps = obj.optIntOrNull("steps"),
                trainingMinutes = obj.optIntOrNull("trainingMinutes"),
                trainingIntensity = obj.optStringOrNull("trainingIntensity"),
                trainingType = obj.optStringOrNull("trainingType"),
                trainingSessions = obj.optStringOrNull("trainingSessions"),
                morningNote = obj.optStringOrNull("morningNote"),
                // Eldre sikkerhetskopier (før dagboken ble delt i to) hadde bare "note" -
                // faller tilbake til den som kveldsnotat hvis de nye feltene mangler.
                eveningNote = obj.optStringOrNull("eveningNote") ?: obj.optStringOrNull("note"),
                dayTag = obj.optStringOrNull("dayTag"),
                nutritionLevel = obj.optStringOrNull("nutritionLevel"),
                habitTags = obj.optStringOrNull("habitTags"),
                manualOverrides = obj.optStringOrNull("manualOverrides"),
                source = obj.optStringOrNull("source") ?: "backup",
                updatedAt = if (obj.has("updatedAt")) obj.optLong("updatedAt") else System.currentTimeMillis()
            )
        )
    }

    return result
}
