package no.healthlog.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.healthlog.app.coach.ReadinessLevel
import no.healthlog.app.coach.ReadinessSignalType
import no.healthlog.app.coach.ReadinessStatus
import no.healthlog.app.coach.calculateReadiness
import no.healthlog.app.data.HealthDay
import no.healthlog.app.ui.StatusColors
import java.time.LocalDate

/**
 * Toppkortet på I dag: svarer på "hvordan er formen i dag?" med dagsform, helse-score,
 * én setning og hva som trekker opp eller ned. Uten nok data til dagsform vises bare
 * scoren. Trykk på ringen viser hvordan scoren er regnet ut ([onShowScore]).
 */
@Composable
fun TodayHeroCard(today: HealthDay?, all: List<HealthDay>, onRegister: () -> Unit, onShowScore: () -> Unit) {
    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val readiness = remember(all, sleepGoalMinutes) { calculateReadiness(all, LocalDate.now(), sleepGoalMinutes) }
    val score = calculateHealthScore(
        today,
        sleepGoalMinutes = sleepGoalMinutes,
        stepsGoal = GoalPreferences.getStepsGoal(context),
        hrvRange = remember(all) { personalHrvScoreRange(all) }
    )

    val good = StatusColors.good()
    val warn = StatusColors.warn()
    val (headline, headlineColor) = when (readiness?.level) {
        ReadinessLevel.GOOD -> stringResource(if (readiness?.isStrong == true) R.string.hero_ready_hard else R.string.hero_ready_normal) to good
        ReadinessLevel.MODERATE -> stringResource(R.string.hero_ready_moderate) to warn
        ReadinessLevel.LOW -> stringResource(R.string.hero_ready_low) to warn
        null -> healthScoreStatus(context, score) to MaterialTheme.colorScheme.onSurface
    }
    val advice = when {
        readiness == null -> stringResource(R.string.hero_no_readiness)
        readiness.level == ReadinessLevel.GOOD && readiness.isStrong -> stringResource(R.string.readiness_advice_good_strong)
        readiness.level == ReadinessLevel.GOOD -> stringResource(R.string.readiness_advice_good_normal)
        readiness.level == ReadinessLevel.MODERATE -> stringResource(R.string.readiness_advice_moderate)
        else -> stringResource(R.string.readiness_advice_low)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (readiness != null) R.string.readiness_title else R.string.health_score_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(headline, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = headlineColor)
                }
                ScoreRing(score, Modifier.clickable(onClick = onShowScore))
            }

            Text(advice, style = MaterialTheme.typography.bodyLarge)

            if (readiness != null && readiness.signals.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    readiness.signals.forEach { signal ->
                        val name = stringResource(
                            when (signal.type) {
                                ReadinessSignalType.HRV -> R.string.metric_label_hrv
                                ReadinessSignalType.RESTING_HR -> R.string.metric_label_resting_hr
                                ReadinessSignalType.SLEEP -> R.string.metric_label_sleep
                                ReadinessSignalType.TRAINING_LOAD -> R.string.hero_signal_load
                            }
                        )
                        val (textRes, color) = when (signal.status) {
                            ReadinessStatus.BETTER -> R.string.hero_signal_better to good
                            ReadinessStatus.NORMAL -> R.string.hero_signal_normal to good
                            ReadinessStatus.WORSE -> R.string.hero_signal_worse to warn
                        }
                        Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
                            Text(
                                stringResource(textRes, name),
                                color = color,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Button(onClick = onRegister, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.fab_register_description))
            }
        }
    }
}

/** Helse-scoren som en ring med tallet i midten. */
@Composable
private fun ScoreRing(score: Int, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.hero_score_description, score)
    Box(modifier.size(84.dp).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                fill, -90f, 360f * score.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.hero_score_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
