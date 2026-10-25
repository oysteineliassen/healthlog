package no.healthlog.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.healthlog.app.billing.BillingManager
import no.healthlog.app.coach.availableReviewYears
import no.healthlog.app.coach.buildYearInReview
import no.healthlog.app.coach.longestStreakBefore
import no.healthlog.app.data.HealthDay
import no.healthlog.app.share.createRecordsImageFile
import no.healthlog.app.share.createRecordsPdfFile
import no.healthlog.app.share.createYearInReviewImageFile
import no.healthlog.app.share.createYearInReviewPdfFile
import no.healthlog.app.share.shareFile
import java.time.LocalDate

/**
 * Alle-tiders personlige bester, regnet ut fra hele historikken. Gjenbruker
 * longestStreakBefore fra coach-pakken (internal, derfor tilgjengelig her siden alt
 * er i samme modul) i stedet for å duplisere streak-logikken.
 */
@Composable
fun RecordsScreen(days: List<HealthDay>, billingManager: BillingManager) {
    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val stepsGoal = GoalPreferences.getStepsGoal(context)

    val byDate = remember(days) {
        days.mapNotNull { day ->
            runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
        }.toMap()
    }
    val tomorrow = LocalDate.now().plusDays(1)

    val longestStepsStreak = remember(byDate) {
        longestStreakBefore(byDate, tomorrow) { (it.steps ?: 0) >= 8000 }
    }
    val longestSleepStreak = remember(byDate) {
        longestStreakBefore(byDate, tomorrow) { (it.sleepMinutes ?: 0) >= 480 }
    }
    val lowestRestingHr = remember(days) { days.mapNotNull { it.restingHeartRate }.minOrNull() }
    val highestHrv = remember(days) { days.mapNotNull { it.hrvMs }.maxOrNull() }
    val mostSteps = remember(days) { days.mapNotNull { it.steps }.maxOrNull() }
    val longestTraining = remember(days) { days.mapNotNull { it.trainingMinutes }.maxOrNull() }
    val highestScore = remember(days, sleepGoalMinutes, stepsGoal) {
        val hrvRange = personalHrvScoreRange(days)
        days.maxOfOrNull { calculateHealthScore(it, sleepGoalMinutes, stepsGoal, hrvRange) }
    }

    val coroutineScope = rememberCoroutineScope()
    val recordsTitle = stringResource(R.string.records_title)
    val allTimeLabel = stringResource(R.string.records_share_period_label)

    fun shareRecords(asPdf: Boolean) {
        coroutineScope.launch {
            val file = withContext(Dispatchers.IO) {
                if (asPdf) {
                    createRecordsPdfFile(context, days, recordsTitle, allTimeLabel)
                } else {
                    createRecordsImageFile(context, days, recordsTitle, allTimeLabel)
                }
            }
            shareFile(context, file, if (asPdf) "application/pdf" else "image/png")
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.records_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            YearInReviewCard(days)
        }

        item {
            Text(
                text = stringResource(R.string.records_all_time_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { shareRecords(asPdf = false) }) {
                    Text(stringResource(R.string.share_as_image))
                }
                OutlinedButton(onClick = { shareRecords(asPdf = true) }) {
                    Text(stringResource(R.string.share_as_pdf))
                }
            }
        }

        if (longestStepsStreak >= 1) {
            item {
                RecordRow(
                    stringResource(R.string.record_steps_streak),
                    stringResource(R.string.record_days_format, longestStepsStreak)
                )
            }
        }

        if (longestSleepStreak >= 1) {
            item {
                RecordRow(
                    stringResource(R.string.record_sleep_streak),
                    stringResource(R.string.record_days_format, longestSleepStreak)
                )
            }
        }

        lowestRestingHr?.let {
            item { RecordRow(stringResource(R.string.record_lowest_resting_hr), "$it bpm") }
        }

        highestHrv?.let {
            item { RecordRow(stringResource(R.string.record_highest_hrv), "${it.toInt()} ms") }
        }

        mostSteps?.let {
            item { RecordRow(stringResource(R.string.record_most_steps), "%,d".format(it)) }
        }

        longestTraining?.let {
            item { RecordRow(stringResource(R.string.record_longest_training), "$it min") }
        }

        highestScore?.let {
            item { RecordRow(stringResource(R.string.record_highest_score), "$it / 100") }
        }

        item {
            RecordRow(stringResource(R.string.record_total_days), days.size.toString())
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * "Året som gikk"-oppsummering, litt som Spotify Wrapped - viser bare år som faktisk
 * har minst én registrering, med det nyeste valgt som standard.
 */
@Composable
private fun YearInReviewCard(days: List<HealthDay>) {
    val availableYears = remember(days) { availableReviewYears(days) }
    if (availableYears.isEmpty()) return

    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val stepsGoal = GoalPreferences.getStepsGoal(context)

    var selectedYear by rememberSaveable { mutableStateOf(availableYears.first()) }
    val data = remember(days, selectedYear, sleepGoalMinutes, stepsGoal) {
        buildYearInReview(days, selectedYear, sleepGoalMinutes, stepsGoal)
    } ?: return

    val coroutineScope = rememberCoroutineScope()
    val trainingTypeText = data.mostCommonTrainingType?.let { trainingTypeLabel(it) }
    val reviewTitle = stringResource(R.string.year_review_title, data.year)

    fun shareReview(asPdf: Boolean) {
        coroutineScope.launch {
            val file = withContext(Dispatchers.IO) {
                if (asPdf) {
                    createYearInReviewPdfFile(context, data, trainingTypeText, reviewTitle)
                } else {
                    createYearInReviewImageFile(context, data, trainingTypeText, reviewTitle)
                }
            }
            shareFile(context, file, if (asPdf) "application/pdf" else "image/png")
        }
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(reviewTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)

            if (availableYears.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableYears.forEach { year ->
                        FilterChip(
                            selected = year == selectedYear,
                            onClick = { selectedYear = year },
                            label = { Text(year.toString()) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text("${stringResource(R.string.year_review_days_logged)}: ${data.daysLogged}")
            Text("${stringResource(R.string.year_review_total_steps)}: ${"%,d".format(data.totalSteps)}")
            data.avgSleepHours?.let {
                Text("${stringResource(R.string.year_review_avg_sleep)}: ${"%.1f".format(it)} t")
            }
            data.avgScore?.let {
                Text("${stringResource(R.string.year_review_avg_score)}: ${"%.0f".format(it)}")
            }
            Text("${stringResource(R.string.year_review_total_training)}: ${data.totalTrainingMinutes} min")
            trainingTypeText?.let {
                Text("${stringResource(R.string.year_review_most_common_training)}: $it")
            }
            data.highestHrv?.let {
                Text("${stringResource(R.string.year_review_highest_hrv)}: ${it.toInt()} ms")
            }
            data.lowestRestingHr?.let {
                Text("${stringResource(R.string.year_review_lowest_resting_hr)}: $it bpm")
            }
            if (data.longestStepsStreak >= 1) {
                Text(
                    "${stringResource(R.string.year_review_longest_steps_streak)}: " +
                        stringResource(R.string.record_days_format, data.longestStepsStreak)
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { shareReview(asPdf = false) }) {
                    Text(stringResource(R.string.share_as_image))
                }
                OutlinedButton(onClick = { shareReview(asPdf = true) }) {
                    Text(stringResource(R.string.share_as_pdf))
                }
            }
        }
    }
}
