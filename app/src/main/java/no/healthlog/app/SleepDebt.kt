package no.healthlog.app

import android.content.Context
import no.healthlog.app.data.HealthDay
import java.time.LocalDate

data class SleepDebt(val netHours: Double, val goalHours: Float, val daysConsidered: Int)

private const val SLEEP_DEBT_WINDOW_DAYS = 14

/**
 * Summerer avviket fra DITT SATTE søvnmål (se GoalPreferences, samme tall som "Dagens
 * mål" bruker) over de siste 14 dagene. Positivt tall betyr at du i sum har sovet
 * mindre enn målet ditt (gjeld), negativt betyr et overskudd. En god natt kan altså
 * trekke ned tidligere opparbeidet gjeld, samme prinsipp som Garmin og andre klokker
 * bruker.
 *
 * Brukte tidligere en egen, automatisk utregnet baseline (calculateSleepGoal i
 * HrvGoal.kt) i stedet for det tallet brukeren selv satte - forvirrende når de to
 * kortene viste ulike "mål" for samme ting. Bruker nå ETT felles tall overalt.
 *
 * Returnerer null hvis det ikke finnes søvndata i det hele tatt i vinduet - ingenting
 * fornuftig å vise da.
 */
fun calculateSleepDebt(context: Context, allDays: List<HealthDay>, today: LocalDate = LocalDate.now()): SleepDebt? {
    val goalHours = GoalPreferences.getSleepGoalMinutes(context) / 60f
    val windowStart = today.minusDays((SLEEP_DEBT_WINDOW_DAYS - 1).toLong())

    val byDate = allDays.mapNotNull { day ->
        runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
    }.toMap()

    val sleepHoursInWindow = (0 until SLEEP_DEBT_WINDOW_DAYS).mapNotNull { offset ->
        byDate[windowStart.plusDays(offset.toLong())]?.sleepMinutes?.let { it / 60.0 }
    }

    if (sleepHoursInWindow.isEmpty()) return null

    val netHours = sleepHoursInWindow.sumOf { goalHours - it }
    return SleepDebt(netHours = netHours, goalHours = goalHours, daysConsidered = sleepHoursInWindow.size)
}
