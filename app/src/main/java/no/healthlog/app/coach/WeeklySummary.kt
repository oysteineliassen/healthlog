package no.healthlog.app.coach

import no.healthlog.app.calculateHealthScore
import no.healthlog.app.data.HealthDay
import java.time.LocalDate

data class WeeklySummaryData(
    val stepsThisWeek: Int,
    val stepsLastWeek: Int?,
    val avgSleepThisWeek: Double?,
    val avgSleepLastWeek: Double?,
    val avgScoreThisWeek: Double?,
    val avgScoreLastWeek: Double?
)

private fun average(days: List<HealthDay>, selector: (HealthDay) -> Number?): Double? {
    val values = days.mapNotNull { selector(it)?.toDouble() }
    return if (values.isEmpty()) null else values.average()
}

/**
 * Sammenligner de 7 dagene som slutter på [weekEndDate] med de 7 dagene før det igjen.
 * Returnerer null hvis det ikke finnes noe data i det hele tatt for inneværende uke.
 */
fun buildWeeklySummary(
    allDays: List<HealthDay>,
    weekEndDate: LocalDate,
    sleepGoalMinutes: Int = 480,
    stepsGoal: Int = 8000
): WeeklySummaryData? {
    val weekStart = weekEndDate.minusDays(6)
    val prevWeekStart = weekStart.minusDays(7)
    val prevWeekEnd = weekStart.minusDays(1)

    val byDate = allDays.mapNotNull { day ->
        runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
    }.toMap()

    val thisWeek = byDate.filterKeys { it in weekStart..weekEndDate }.values.toList()
    val lastWeek = byDate.filterKeys { it in prevWeekStart..prevWeekEnd }.values.toList()

    if (thisWeek.isEmpty()) return null

    return WeeklySummaryData(
        stepsThisWeek = thisWeek.sumOf { it.steps ?: 0 },
        stepsLastWeek = if (lastWeek.isEmpty()) null else lastWeek.sumOf { it.steps ?: 0 },
        avgSleepThisWeek = average(thisWeek) { it.sleepMinutes?.let { m -> m / 60.0 } },
        avgSleepLastWeek = average(lastWeek) { it.sleepMinutes?.let { m -> m / 60.0 } },
        avgScoreThisWeek = average(thisWeek) { calculateHealthScore(it, sleepGoalMinutes, stepsGoal).toDouble() },
        avgScoreLastWeek = average(lastWeek) { calculateHealthScore(it, sleepGoalMinutes, stepsGoal).toDouble() }
    )
}
