package no.healthlog.app.coach

import no.healthlog.app.calculateHealthScore
import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import kotlin.math.max

data class YearInReviewData(
    val year: Int,
    val daysLogged: Int,
    val totalSteps: Long,
    val avgSteps: Double?,
    val avgSleepHours: Double?,
    val avgScore: Double?,
    val totalTrainingMinutes: Int,
    val mostCommonTrainingType: String?,
    val highestHrv: Double?,
    val lowestRestingHr: Int?,
    val longestStepsStreak: Int
)

private fun average(days: List<HealthDay>, selector: (HealthDay) -> Number?): Double? {
    val values = days.mapNotNull { selector(it)?.toDouble() }
    return if (values.isEmpty()) null else values.average()
}

/** Lister de årene som faktisk har minst én registrering, nyeste først - til bruk i en årsvelger. */
fun availableReviewYears(allDays: List<HealthDay>): List<Int> =
    allDays.mapNotNull { runCatching { LocalDate.parse(it.date).year }.getOrNull() }
        .distinct()
        .sortedDescending()

/**
 * Bygger en "året som gikk"-oppsummering for [year]. Streaken regnes kun ut fra
 * dager INNENFOR selve året (ikke historikk fra tidligere år), slik at tallet er
 * meningsfullt isolert for akkurat det året. Returnerer null hvis året ikke har
 * noen registreringer i det hele tatt.
 */
fun buildYearInReview(
    allDays: List<HealthDay>,
    year: Int,
    sleepGoalMinutes: Int = 480,
    stepsGoal: Int = 8000
): YearInReviewData? {
    val byDate = allDays.mapNotNull { day ->
        runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
    }.toMap().filterKeys { it.year == year }

    if (byDate.isEmpty()) return null

    val yearDays = byDate.values.toList()

    val trainingTypeCounts = yearDays
        .mapNotNull { it.trainingType }
        .flatMap { it.split("|") }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
    val mostCommonType = trainingTypeCounts.maxByOrNull { it.value }?.key

    val longestStepsStreak = run {
        val dates = byDate.keys.sorted()
        var best = 0
        var current = 0
        var prev: LocalDate? = null
        for (d in dates) {
            val day = byDate.getValue(d)
            val consecutive = prev != null && d == prev!!.plusDays(1)
            val meetsGoal = (day.steps ?: 0) >= 8000
            current = if (meetsGoal) (if (consecutive) current + 1 else 1) else 0
            best = max(best, current)
            prev = d
        }
        best
    }

    return YearInReviewData(
        year = year,
        daysLogged = yearDays.size,
        totalSteps = yearDays.sumOf { (it.steps ?: 0).toLong() },
        avgSteps = average(yearDays) { it.steps },
        avgSleepHours = average(yearDays) { it.sleepMinutes?.let { m -> m / 60.0 } },
        avgScore = average(yearDays) { calculateHealthScore(it, sleepGoalMinutes, stepsGoal).toDouble() },
        totalTrainingMinutes = yearDays.sumOf { it.trainingMinutes ?: 0 },
        mostCommonTrainingType = mostCommonType,
        highestHrv = yearDays.mapNotNull { it.hrvMs }.maxOrNull(),
        lowestRestingHr = yearDays.mapNotNull { it.restingHeartRate }.minOrNull(),
        longestStepsStreak = longestStepsStreak
    )
}
