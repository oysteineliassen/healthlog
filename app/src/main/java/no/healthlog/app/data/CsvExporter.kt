package no.healthlog.app.data

private fun csvEscape(value: String): String =
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/**
 * Enkel CSV med én rad per dag, til bruk i regneark utenfor appen. Dette er ikke ment
 * som en gjenopprettbar sikkerhetskopi (bruk JSON-eksporten til det) - bare et
 * lesbart format for egen analyse.
 */
fun healthDaysToCsv(days: List<HealthDay>): String {
    val header = listOf(
        "date", "weightKg", "waistCm", "bodyFatPercent", "hrvMs", "sleepMinutes", "deepSleepMinutes", "lightSleepMinutes", "remSleepMinutes", "sleepAwakeMinutes", "sleepScore",
        "restingHeartRate", "avgHeartRate", "bodyBatteryMax", "bodyBatteryMin", "stress", "steps", "activeMinutes",
        "trainingMinutes", "trainingIntensity", "trainingType", "trainingSessions", "morningNote", "eveningNote", "sleepReflection", "sleepFactors", "dayTag", "nutritionLevel", "habitTags", "source", "updatedAt"
    ).joinToString(",")

    val rows = days.sortedBy { it.date }.map { day ->
        listOf(
            day.date,
            day.weightKg?.toString() ?: "",
            day.waistCm?.toString() ?: "",
            day.bodyFatPercent?.toString() ?: "",
            day.hrvMs?.toString() ?: "",
            day.sleepMinutes?.toString() ?: "",
            day.deepSleepMinutes?.toString() ?: "",
            day.lightSleepMinutes?.toString() ?: "",
            day.remSleepMinutes?.toString() ?: "",
            day.sleepAwakeMinutes?.toString() ?: "",
            day.sleepScore?.toString() ?: "",
            day.restingHeartRate?.toString() ?: "",
            day.avgHeartRate?.toString() ?: "",
            day.bodyBatteryMax?.toString() ?: "",
            day.bodyBatteryMin?.toString() ?: "",
            day.stress?.toString() ?: "",
            day.steps?.toString() ?: "",
            day.activeMinutes?.toString() ?: "",
            day.trainingMinutes?.toString() ?: "",
            day.trainingIntensity ?: "",
            day.trainingType ?: "",
            csvEscape(day.trainingSessions ?: ""),
            csvEscape(day.morningNote ?: ""),
            csvEscape(day.eveningNote ?: ""),
            csvEscape(day.sleepReflection ?: ""),
            day.sleepFactors ?: "",
            day.dayTag ?: "",
            day.nutritionLevel ?: "",
            csvEscape(day.habitTags ?: ""),
            day.source,
            day.updatedAt.toString()
        ).joinToString(",")
    }

    return (listOf(header) + rows).joinToString("\n")
}
