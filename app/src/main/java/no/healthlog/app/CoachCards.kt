package no.healthlog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import no.healthlog.app.coach.CoachCondition
import no.healthlog.app.coach.CoachFact
import no.healthlog.app.coach.CoachMessage
import no.healthlog.app.coach.CoachMessageId
import no.healthlog.app.coach.CoachMetric
import no.healthlog.app.coach.CoachPreferences
import no.healthlog.app.coach.ReadinessLevel
import no.healthlog.app.coach.ReadinessSignal
import no.healthlog.app.coach.ReadinessSignalType
import no.healthlog.app.coach.ReadinessStatus
import no.healthlog.app.coach.calculateReadiness
import no.healthlog.app.coach.evaluateCoachMessage
import no.healthlog.app.coach.weeklyActivity
import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import kotlin.math.roundToInt

private val ReadinessGreen = Color(0xFF2E7D32)
private val ReadinessAmber = Color(0xFFE08A00)
private val ReadinessRed = Color(0xFFC62828)

// ---------------------------------------------------------------------------------
// Dagsform
// ---------------------------------------------------------------------------------

/**
 * Dagsform: grønn/gul/rød med ett konkret råd for i dag, og tallene vurderingen
 * bygger på (se coach/Readiness.kt). Skjules helt når det er for lite data - heller
 * ingenting enn et råd på sviktende grunnlag.
 */
@Composable
fun ReadinessCard(all: List<HealthDay>) {
    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val readiness = remember(all, sleepGoalMinutes) {
        calculateReadiness(all, LocalDate.now(), sleepGoalMinutes)
    } ?: return

    val (levelColor, levelLabel) = when (readiness.level) {
        ReadinessLevel.GOOD -> ReadinessGreen to stringResource(R.string.readiness_level_good)
        ReadinessLevel.MODERATE -> ReadinessAmber to stringResource(R.string.readiness_level_moderate)
        ReadinessLevel.LOW -> ReadinessRed to stringResource(R.string.readiness_level_low)
    }
    val advice = when {
        readiness.level == ReadinessLevel.GOOD && readiness.isStrong -> stringResource(R.string.readiness_advice_good_strong)
        readiness.level == ReadinessLevel.GOOD -> stringResource(R.string.readiness_advice_good_normal)
        readiness.level == ReadinessLevel.MODERATE -> stringResource(R.string.readiness_advice_moderate)
        else -> stringResource(R.string.readiness_advice_low)
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.readiness_title),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(shape = RoundedCornerShape(50), color = levelColor.copy(alpha = 0.16f)) {
                    Text(
                        levelLabel,
                        color = levelColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(advice, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))

            readiness.signals.forEach { signal ->
                ReadinessSignalRow(signal)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ReadinessSignalRow(signal: ReadinessSignal) {
    val dotColor = when (signal.status) {
        ReadinessStatus.BETTER -> ReadinessGreen
        ReadinessStatus.NORMAL -> MaterialTheme.colorScheme.outline
        ReadinessStatus.WORSE -> ReadinessAmber
    }
    val text = when (signal.type) {
        ReadinessSignalType.HRV -> stringResource(
            R.string.readiness_signal_vs_normal,
            stringResource(R.string.metric_label_hrv),
            formatMetric(CoachMetric.HRV_MS, signal.value),
            formatMetric(CoachMetric.HRV_MS, signal.reference)
        )
        ReadinessSignalType.RESTING_HR -> stringResource(
            R.string.readiness_signal_vs_normal,
            stringResource(R.string.metric_label_resting_hr),
            formatMetric(CoachMetric.RESTING_HR, signal.value),
            formatMetric(CoachMetric.RESTING_HR, signal.reference)
        )
        ReadinessSignalType.SLEEP -> stringResource(
            R.string.readiness_signal_vs_goal,
            stringResource(R.string.readiness_label_sleep_last_night),
            formatMetric(CoachMetric.SLEEP_HOURS, signal.value),
            formatMetric(CoachMetric.SLEEP_HOURS, signal.reference)
        )
        ReadinessSignalType.TRAINING_LOAD -> stringResource(R.string.readiness_signal_load_high, signal.value.roundToInt())
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------
// AI-trener
// ---------------------------------------------------------------------------------

/**
 * Den fiktive "AI-treneren" - ikke ekte AI, men en regelmotor (coach/CoachEngine.kt)
 * som velger én ferdigskrevet tilbakemelding basert på mønstre i dine data. Trykk på
 * kortet viser tallene bak meldingen.
 */
@Composable
fun CoachCard(all: List<HealthDay>) {
    val context = LocalContext.current
    val today = LocalDate.now()

    val message = remember(all) {
        evaluateCoachMessage(
            all,
            CoachPreferences.getHistory(context),
            today,
            sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context),
            stepsGoal = GoalPreferences.getStepsGoal(context)
        )
    } ?: return

    LaunchedEffect(message.id) {
        CoachPreferences.recordShown(context, message.id, today)
    }

    var showWhy by remember { mutableStateOf(false) }
    val text = coachMessageText(message)

    Card(
        modifier = Modifier.clickable { showWhy = true },
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.coach_title), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.coach_why_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showWhy) {
        CoachWhyDialog(message = message, messageText = text, onDismiss = { showWhy = false })
    }
}

@Composable
private fun coachMessageText(message: CoachMessage): String {
    val int = message.intArg ?: 0
    val dbl = message.doubleArg ?: 0.0
    return when (message.id) {
        CoachMessageId.MILESTONE_STREAK_RECORD_STEPS -> stringResource(R.string.coach_milestone_streak_record_steps, int)
        CoachMessageId.MILESTONE_STREAK_RECORD_SLEEP -> stringResource(R.string.coach_milestone_streak_record_sleep, int)
        CoachMessageId.MILESTONE_FIRST_HRV_ABOVE_50 -> stringResource(R.string.coach_milestone_first_hrv_50)
        CoachMessageId.MILESTONE_TOTAL_DAYS -> stringResource(R.string.coach_milestone_total_days, int)
        CoachMessageId.CONCERN_SLEEP_LOW -> stringResource(R.string.coach_concern_sleep_low)
        CoachMessageId.CONCERN_STRESS_HIGH -> stringResource(R.string.coach_concern_stress_high)
        CoachMessageId.CONCERN_NO_ENTRY -> stringResource(R.string.coach_concern_no_entry, int)
        CoachMessageId.CONCERN_HRV_DOWN -> stringResource(R.string.coach_concern_hrv_down)
        CoachMessageId.CONCERN_TRAINING_LOAD_SPIKE -> stringResource(R.string.coach_concern_training_load_spike, int)
        CoachMessageId.CONCERN_AWAKE_UP -> stringResource(R.string.coach_concern_awake_up, int)
        CoachMessageId.POSITIVE_HRV_UP -> stringResource(R.string.coach_positive_hrv_up)
        CoachMessageId.POSITIVE_RESTING_HR_DOWN -> stringResource(R.string.coach_positive_resting_hr_down)
        CoachMessageId.POSITIVE_SLEEP_STREAK -> stringResource(R.string.coach_positive_sleep_streak, int)
        CoachMessageId.POSITIVE_STEPS_STREAK -> stringResource(R.string.coach_positive_steps_streak, int)
        CoachMessageId.POSITIVE_SCORE_STREAK -> stringResource(R.string.coach_positive_score_streak)
        CoachMessageId.POSITIVE_DEEP_SLEEP_UP -> stringResource(R.string.coach_positive_deep_sleep_up, int)
        CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION ->
            stringResource(R.string.coach_correlation_training_sleep, "%.1f".format(dbl))
        CoachMessageId.POSITIVE_TRAINING_HRV_CORRELATION ->
            stringResource(R.string.coach_correlation_training_hrv, "%.0f".format(dbl))
        CoachMessageId.POSITIVE_REST_DAY_STRESS_CORRELATION ->
            stringResource(R.string.coach_correlation_rest_stress, "%.0f".format(dbl))
        CoachMessageId.POSITIVE_LOW_STRESS_SLEEP_CORRELATION ->
            stringResource(R.string.coach_correlation_stress_sleep, "%.1f".format(dbl))
        CoachMessageId.POSITIVE_LOW_STRESS_HRV_CORRELATION ->
            stringResource(R.string.coach_correlation_stress_hrv, "%.0f".format(dbl))
        CoachMessageId.POSITIVE_LIGHT_MEALS_SLEEP_CORRELATION ->
            stringResource(R.string.coach_correlation_light_meals_sleep, "%.1f".format(dbl))
        CoachMessageId.POSITIVE_LIGHT_MEALS_STRESS_CORRELATION ->
            stringResource(R.string.coach_correlation_light_meals_stress, "%.0f".format(dbl))
        CoachMessageId.POSITIVE_ACTIVE_DAY_STRESS_CORRELATION ->
            stringResource(R.string.coach_correlation_active_day_stress, "%.0f".format(dbl))
        CoachMessageId.POSITIVE_GREAT_SLEEP_LAST_NIGHT ->
            stringResource(R.string.coach_great_sleep_last_night, "%.1f".format(dbl))
        CoachMessageId.NUDGE_TRAINING_LOAD_DROP -> stringResource(R.string.coach_nudge_training_load_drop, int)
        CoachMessageId.HABIT_SLEEP_LOWER ->
            stringResource(R.string.coach_habit_sleep_lower, message.textArg.orEmpty(), "%.1f".format(dbl))
        CoachMessageId.HABIT_SLEEP_HIGHER ->
            stringResource(R.string.coach_habit_sleep_higher, message.textArg.orEmpty(), "%.1f".format(dbl))
        CoachMessageId.HABIT_HRV_LOWER ->
            stringResource(R.string.coach_habit_hrv_lower, message.textArg.orEmpty(), "%.0f".format(dbl))
        CoachMessageId.HABIT_HRV_HIGHER ->
            stringResource(R.string.coach_habit_hrv_higher, message.textArg.orEmpty(), "%.0f".format(dbl))
        CoachMessageId.NEUTRAL -> stringResource(R.string.coach_neutral)
    }
}

/** "Hvorfor?"-visningen: meldingen igjen, og tallene den bygger på. */
@Composable
private fun CoachWhyDialog(message: CoachMessage, messageText: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.coach_why_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(12.dp))
                Text(messageText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))

                if (message.facts.isEmpty()) {
                    Text(
                        stringResource(R.string.coach_why_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    message.facts.forEach { fact ->
                        Row {
                            Text("•  ", style = MaterialTheme.typography.bodyMedium)
                            Text(coachFactText(fact), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (message.facts.any { it is CoachFact.WithVsWithout || it is CoachFact.HabitNextDay }) {
                        Text(
                            stringResource(R.string.coach_why_weighting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun coachFactText(fact: CoachFact): String = when (fact) {
    is CoachFact.RecentVsPrevious -> stringResource(
        R.string.coach_why_recent_vs_previous,
        metricLabel(fact.metric), formatMetric(fact.metric, fact.recent), formatMetric(fact.metric, fact.previous)
    )
    is CoachFact.WithVsWithout -> stringResource(
        R.string.coach_why_with_without,
        metricLabel(fact.metric),
        conditionLabel(fact.condition),
        formatMetric(fact.metric, fact.comparison.withMean), fact.comparison.withDays,
        formatMetric(fact.metric, fact.comparison.withoutMean), fact.comparison.withoutDays
    )
    is CoachFact.Streak -> stringResource(R.string.coach_why_streak, fact.current, fact.previousRecord)
    is CoachFact.LatestVsNormal -> if (fact.normal != null) {
        stringResource(
            R.string.coach_why_latest_vs_normal,
            metricLabel(fact.metric), formatMetric(fact.metric, fact.latest), formatMetric(fact.metric, fact.normal)
        )
    } else {
        stringResource(R.string.coach_why_latest, metricLabel(fact.metric), formatMetric(fact.metric, fact.latest))
    }
    is CoachFact.RecentAverage -> stringResource(
        R.string.coach_why_recent_average,
        metricLabel(fact.metric), fact.days, formatMetric(fact.metric, fact.average)
    )
    is CoachFact.Last7VsNormal -> stringResource(
        R.string.coach_why_last7_vs_normal,
        metricLabel(fact.metric), formatMetric(fact.metric, fact.last7), formatMetric(fact.metric, fact.normal)
    )
    is CoachFact.DaysSince -> stringResource(R.string.coach_why_days_since, fact.lastEntry.format(dateFormatter))
    is CoachFact.BasedOnDays -> stringResource(R.string.coach_why_based_on, fact.days)
    is CoachFact.HabitNextDay -> stringResource(
        R.string.coach_why_habit_next_day,
        metricLabel(fact.metric),
        fact.tag,
        formatMetric(fact.metric, fact.comparison.withMean), fact.comparison.withDays,
        formatMetric(fact.metric, fact.comparison.withoutMean), fact.comparison.withoutDays
    )
}

@Composable
private fun metricLabel(metric: CoachMetric): String = stringResource(
    when (metric) {
        CoachMetric.SLEEP_HOURS -> R.string.metric_label_sleep
        CoachMetric.HRV_MS -> R.string.metric_label_hrv
        CoachMetric.RESTING_HR -> R.string.metric_label_resting_hr
        CoachMetric.STRESS -> R.string.metric_label_stress
        CoachMetric.STEPS -> R.string.metric_label_steps
        CoachMetric.TRAINING_LOAD -> R.string.coach_metric_training_load
        CoachMetric.DEEP_SLEEP_MIN -> R.string.coach_metric_deep_sleep
        CoachMetric.AWAKE_MIN -> R.string.coach_metric_awake
        CoachMetric.HEALTH_SCORE -> R.string.health_score_title
    }
)

@Composable
private fun conditionLabel(condition: CoachCondition): String = stringResource(
    when (condition) {
        CoachCondition.TRAINED -> R.string.coach_condition_trained
        CoachCondition.REST_DAY -> R.string.coach_condition_rest_day
        CoachCondition.LOW_STRESS -> R.string.coach_condition_low_stress
        CoachCondition.LIGHT_MEALS -> R.string.coach_condition_light_meals
        CoachCondition.ACTIVE_DAY -> R.string.coach_condition_active_day
    }
)

@Composable
private fun formatMetric(metric: CoachMetric, value: Double): String = when (metric) {
    CoachMetric.SLEEP_HOURS -> "%.1f %s".format(value, stringResource(R.string.unit_hours))
    CoachMetric.HRV_MS -> "%.0f %s".format(value, stringResource(R.string.unit_ms))
    CoachMetric.RESTING_HR -> "%.0f %s".format(value, stringResource(R.string.unit_bpm))
    CoachMetric.STRESS, CoachMetric.HEALTH_SCORE -> "%.0f".format(value)
    CoachMetric.STEPS -> "%,d".format(value.roundToInt())
    CoachMetric.TRAINING_LOAD, CoachMetric.DEEP_SLEEP_MIN, CoachMetric.AWAKE_MIN ->
        "%.0f %s".format(value, stringResource(R.string.unit_minutes))
}

// ---------------------------------------------------------------------------------
// Ukemål for aktivitet
// ---------------------------------------------------------------------------------

/**
 * Minutter aktivitet denne uka (mandag-søndag) mot ukemålet ditt - helserådene gjelder
 * per uke, ikke per dag, så en rolig dag kan tas igjen senere i uka. Se coach/WeeklyActivity.kt.
 */
@Composable
fun WeeklyActivityCard(all: List<HealthDay>) {
    val context = LocalContext.current
    val goal = GoalPreferences.getWeeklyActivityGoal(context)
    val week = remember(all, goal) { weeklyActivity(all, LocalDate.now(), goal) }
    val remaining = (week.goalMinutes - week.minutes).coerceAtLeast(0)

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.weekly_activity_title), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.weekly_activity_progress, week.minutes, week.goalMinutes),
                style = MaterialTheme.typography.titleMedium,
                color = if (week.isComplete) ReadinessGreen else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { week.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (week.isComplete) ReadinessGreen else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    week.isComplete -> stringResource(R.string.weekly_activity_done)
                    week.daysLeft == 0 -> stringResource(R.string.weekly_activity_remaining_today, remaining)
                    else -> stringResource(R.string.weekly_activity_remaining, remaining, week.daysLeft)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.weekly_activity_explanation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
