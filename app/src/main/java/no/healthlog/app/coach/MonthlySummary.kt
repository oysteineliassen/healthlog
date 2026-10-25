package no.healthlog.app.coach

import no.healthlog.app.calculateHealthScore
import no.healthlog.app.personalHrvScoreRange
import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import java.time.YearMonth

data class MonthlySummaryData(
    val stepsThisMonth: Int,
    val stepsLastMonth: Int?,
    val avgSleepThisMonth: Double?,
    val avgSleepLastMonth: Double?,
    val avgScoreThisMonth: Double?,
    val avgScoreLastMonth: Double?
)

private fun average(days: List<HealthDay>, selector: (HealthDay) -> Number?): Double? {
    val values = days.mapNotNull { selector(it)?.toDouble() }
    return if (values.isEmpty()) null else values.average()
}

/**
 * Sammenligner måneden [monthEndDate] tilhører med måneden før - samme prinsipp som
 * buildWeeklySummary, bare på månedsnivå. Returnerer null hvis det ikke finnes noe
 * data i det hele tatt for inneværende måned.
 */
fun buildMonthlySummary(
    allDays: List<HealthDay>,
    monthEndDate: LocalDate,
    sleepGoalMinutes: Int = 480,
    stepsGoal: Int = 8000
): MonthlySummaryData? {
    val month = YearMonth.from(monthEndDate)
    val prevMonth = month.minusMonths(1)

    val hrvRange = personalHrvScoreRange(allDays)
    val byDate = allDays.mapNotNull { day ->
        runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
    }.toMap()

    val thisMonth = byDate.filterKeys { YearMonth.from(it) == month }.values.toList()
    val lastMonth = byDate.filterKeys { YearMonth.from(it) == prevMonth }.values.toList()

    if (thisMonth.isEmpty()) return null

    return MonthlySummaryData(
        stepsThisMonth = thisMonth.sumOf { it.steps ?: 0 },
        stepsLastMonth = if (lastMonth.isEmpty()) null else lastMonth.sumOf { it.steps ?: 0 },
        avgSleepThisMonth = average(thisMonth) { it.sleepMinutes?.let { m -> m / 60.0 } },
        avgSleepLastMonth = average(lastMonth) { it.sleepMinutes?.let { m -> m / 60.0 } },
        avgScoreThisMonth = average(thisMonth) { calculateHealthScore(it, sleepGoalMinutes, stepsGoal, hrvRange).toDouble() },
        avgScoreLastMonth = average(lastMonth) { calculateHealthScore(it, sleepGoalMinutes, stepsGoal, hrvRange).toDouble() }
    )
}
