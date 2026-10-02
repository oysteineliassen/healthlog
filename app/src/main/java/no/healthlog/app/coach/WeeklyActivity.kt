package no.healthlog.app.coach

import no.healthlog.app.TrainingIntensity
import no.healthlog.app.data.HealthDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Aktivitetsminutter for én dag, regnet som "moderat-minutter":
 * hard trening teller dobbelt (helserådene sidestiller 75 min hard med 150 min moderat).
 *
 * Tar det STØRSTE av aktive minutter (fra skritt-tempo) og treningen, ikke summen - en
 * løpetur gir både aktive minutter og en treningsøkt, og skal ikke telles to ganger.
 */
fun activityMinutesForDay(day: HealthDay): Int {
    val active = day.activeMinutes ?: 0
    val training = day.trainingMinutes ?: 0
    val trainingEquivalent = if (day.trainingIntensity == TrainingIntensity.VIGOROUS) training * 2 else training
    return max(active, trainingEquivalent)
}

data class WeeklyActivity(
    val minutes: Int,
    val goalMinutes: Int,
    val weekStart: LocalDate,
    /** Dager igjen av uka etter i dag (0 på søndag). */
    val daysLeft: Int
) {
    val isComplete: Boolean get() = minutes >= goalMinutes
    val fraction: Float get() = if (goalMinutes <= 0) 1f else (minutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
}

/** Inneværende uke (mandag-søndag) fram til og med [today]. */
fun weeklyActivity(allDays: List<HealthDay>, today: LocalDate, goalMinutes: Int): WeeklyActivity {
    val weekStart = today.with(DayOfWeek.MONDAY)
    val minutes = allDays.sumOf { day ->
        val date = parseDate(day) ?: return@sumOf 0
        if (date.isBefore(weekStart) || date.isAfter(today)) 0 else activityMinutesForDay(day)
    }
    val daysLeft = ChronoUnit.DAYS.between(today, weekStart.plusDays(6)).toInt()
    return WeeklyActivity(minutes, goalMinutes, weekStart, daysLeft)
}
