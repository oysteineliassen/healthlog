package no.healthlog.app.share

import android.content.Context
import no.healthlog.app.calculateHealthScore
import no.healthlog.app.personalHrvScoreRange
import no.healthlog.app.coach.longestStreakBefore
import no.healthlog.app.data.HealthDay
import no.healthlog.app.R
import java.time.LocalDate

/**
 * Samme personlige bester som RecordsScreen viser i appen, bygget som
 * ClinicalRow/ClinicalSection slik at de kan gå gjennom samme rendering som det
 * kliniske sammendraget - unngår å duplisere selve tegnelogikken.
 */
fun buildRecordsSections(context: Context, days: List<HealthDay>): List<ClinicalSection> {
    val byDate = days.mapNotNull { day ->
        runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
    }.toMap()
    val tomorrow = LocalDate.now().plusDays(1)

    val longestStepsStreak = longestStreakBefore(byDate, tomorrow) { (it.steps ?: 0) >= 8000 }
    val longestSleepStreak = longestStreakBefore(byDate, tomorrow) { (it.sleepMinutes ?: 0) >= 480 }
    val lowestRestingHr = days.mapNotNull { it.restingHeartRate }.minOrNull()
    val highestHrv = days.mapNotNull { it.hrvMs }.maxOrNull()
    val mostSteps = days.mapNotNull { it.steps }.maxOrNull()
    val longestTraining = days.mapNotNull { it.trainingMinutes }.maxOrNull()
    val hrvRange = personalHrvScoreRange(days)
    val highestScore = days.maxOfOrNull { calculateHealthScore(it, hrvRange = hrvRange) }

    val rows = listOfNotNull(
        if (longestStepsStreak >= 1) {
            ClinicalRow(context.getString(R.string.record_steps_streak), context.getString(R.string.record_days_format, longestStepsStreak))
        } else {
            null
        },
        if (longestSleepStreak >= 1) {
            ClinicalRow(context.getString(R.string.record_sleep_streak), context.getString(R.string.record_days_format, longestSleepStreak))
        } else {
            null
        },
        lowestRestingHr?.let { ClinicalRow(context.getString(R.string.record_lowest_resting_hr), "$it bpm") },
        highestHrv?.let { ClinicalRow(context.getString(R.string.record_highest_hrv), "${it.toInt()} ms") },
        mostSteps?.let { ClinicalRow(context.getString(R.string.record_most_steps), "%,d".format(it)) },
        longestTraining?.let { ClinicalRow(context.getString(R.string.record_longest_training), "$it min") },
        highestScore?.let { ClinicalRow(context.getString(R.string.record_highest_score), "$it / 100") },
        ClinicalRow(context.getString(R.string.record_total_days), days.size.toString())
    )

    return listOf(ClinicalSection(context.getString(R.string.records_title), rows))
}
