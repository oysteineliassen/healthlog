package no.healthlog.app.coach

import no.healthlog.app.calculateHealthScore
import no.healthlog.app.personalHrvScoreRange
import no.healthlog.app.data.HealthDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class PeriodComparisonData(
    val stepsA: Int,
    val stepsB: Int,
    /** Snitt per dag med skrittdata - rettferdig også når periodene er ulikt lange eller én pågår. */
    val avgStepsA: Double?,
    val avgStepsB: Double?,
    val avgSleepA: Double?,
    val avgSleepB: Double?,
    val avgScoreA: Double?,
    val avgScoreB: Double?
)

private fun average(days: List<HealthDay>, selector: (HealthDay) -> Number?): Double? {
    val values = days.mapNotNull { selector(it)?.toDouble() }
    return if (values.isEmpty()) null else values.average()
}

/** Sammenligner to helt vilkårlige, selvvalgte perioder - i motsetning til uke-/månedsoppsummeringen som alltid sammenligner mot den forrige. */
fun buildPeriodComparison(
    allDays: List<HealthDay>,
    periodAStart: LocalDate,
    periodAEnd: LocalDate,
    periodBStart: LocalDate,
    periodBEnd: LocalDate,
    sleepGoalMinutes: Int = 480,
    stepsGoal: Int = 8000
): PeriodComparisonData {
    val hrvRange = personalHrvScoreRange(allDays)
    val byDate = allDays.mapNotNull { day ->
        runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
    }.toMap()

    val periodA = byDate.filterKeys { !it.isBefore(periodAStart) && !it.isAfter(periodAEnd) }.values.toList()
    val periodB = byDate.filterKeys { !it.isBefore(periodBStart) && !it.isAfter(periodBEnd) }.values.toList()

    return PeriodComparisonData(
        stepsA = periodA.sumOf { it.steps ?: 0 },
        stepsB = periodB.sumOf { it.steps ?: 0 },
        avgStepsA = average(periodA) { it.steps },
        avgStepsB = average(periodB) { it.steps },
        avgSleepA = average(periodA) { it.sleepMinutes?.let { m -> m / 60.0 } },
        avgSleepB = average(periodB) { it.sleepMinutes?.let { m -> m / 60.0 } },
        avgScoreA = average(periodA) { calculateHealthScore(it, sleepGoalMinutes, stepsGoal, hrvRange).toDouble() },
        avgScoreB = average(periodB) { calculateHealthScore(it, sleepGoalMinutes, stepsGoal, hrvRange).toDouble() }
    )
}

enum class ComparisonPeriodType { WEEK, MONTH }

data class PeriodRange(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/**
 * Hele perioder: uke = mandag-søndag, måned = 1. til siste dag. [offset] 0 er perioden
 * [today] ligger i, 1 er perioden før, osv.
 */
fun periodRange(type: ComparisonPeriodType, today: LocalDate, offset: Int): PeriodRange = when (type) {
    ComparisonPeriodType.WEEK -> {
        val start = today.with(DayOfWeek.MONDAY).minusWeeks(offset.toLong())
        PeriodRange(start, start.plusDays(6))
    }
    ComparisonPeriodType.MONTH -> {
        val month = YearMonth.from(today).minusMonths(offset.toLong())
        PeriodRange(month.atDay(1), month.atEndOfMonth())
    }
}
