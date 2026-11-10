package no.healthlog.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.healthlog.app.billing.BillingManager
import no.healthlog.app.data.HealthDay
import no.healthlog.app.coach.ComparisonPeriodType
import no.healthlog.app.coach.PeriodRange
import no.healthlog.app.coach.periodRange
import no.healthlog.app.coach.buildPeriodComparison
import no.healthlog.app.coach.WeightProjection
import no.healthlog.app.coach.WeightProjectionStatus
import no.healthlog.app.coach.projectWeight
import no.healthlog.app.coach.rollingAverage
import no.healthlog.app.data.habitTagSet
import no.healthlog.app.share.createClinicalPdfFile
import no.healthlog.app.share.createSummaryImageFile
import no.healthlog.app.share.createSummaryPdfFile
import no.healthlog.app.share.shareFile
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Sammenlign to hele perioder (uker mandag-søndag eller kalendermåneder). Hver periode
 * velges med piler, og raden viser nøyaktig hvilke datoer som er med - i motsetning
 * til den gamle kalendervelgeren, der du valgte en løs startdag og måtte gjette hvilke
 * dager som ble tatt med. Standard: forrige periode (A) mot inneværende (B).
 */
@Composable
fun PeriodComparisonCard(days: List<HealthDay>) {
    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val stepsGoal = GoalPreferences.getStepsGoal(context)
    val today = LocalDate.now()

    var type by rememberSaveable { mutableStateOf(ComparisonPeriodType.WEEK) }
    // Antall perioder tilbake fra inneværende (0 = denne uka/måneden).
    var offsetA by rememberSaveable { mutableStateOf(1) }
    var offsetB by rememberSaveable { mutableStateOf(0) }

    val rangeA = periodRange(type, today, offsetA)
    val rangeB = periodRange(type, today, offsetB)
    val comparison = remember(days, rangeA, rangeB, sleepGoalMinutes, stepsGoal) {
        buildPeriodComparison(days, rangeA.start, rangeA.end, rangeB.start, rangeB.end, sleepGoalMinutes, stepsGoal)
    }

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.period_comparison_title), fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == ComparisonPeriodType.WEEK,
                    onClick = { type = ComparisonPeriodType.WEEK },
                    label = { Text(stringResource(R.string.period_comparison_week)) }
                )
                FilterChip(
                    selected = type == ComparisonPeriodType.MONTH,
                    onClick = { type = ComparisonPeriodType.MONTH },
                    label = { Text(stringResource(R.string.period_comparison_month)) }
                )
            }

            Spacer(Modifier.height(12.dp))

            PeriodStepperRow(
                title = stringResource(R.string.period_comparison_a),
                type = type,
                range = rangeA,
                today = today,
                canGoForward = offsetA > 0,
                onBack = { offsetA += 1 },
                onForward = { offsetA -= 1 }
            )
            PeriodStepperRow(
                title = stringResource(R.string.period_comparison_b),
                type = type,
                range = rangeB,
                today = today,
                canGoForward = offsetB > 0,
                onBack = { offsetB += 1 },
                onForward = { offsetB -= 1 }
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Text(
                stringResource(
                    R.string.period_comparison_steps_avg,
                    comparison.avgStepsA?.let { "%,.0f".format(it) } ?: "–",
                    comparison.avgStepsB?.let { "%,.0f".format(it) } ?: "–"
                ),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(6.dp))

            if (comparison.avgSleepA != null || comparison.avgSleepB != null) {
                Text(
                    stringResource(
                        R.string.period_comparison_sleep,
                        comparison.avgSleepA?.let { "%.1f".format(it) } ?: "–",
                        comparison.avgSleepB?.let { "%.1f".format(it) } ?: "–"
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
            }

            if (comparison.avgScoreA != null || comparison.avgScoreB != null) {
                Text(
                    stringResource(
                        R.string.period_comparison_score,
                        comparison.avgScoreA?.let { "%.0f".format(it) } ?: "–",
                        comparison.avgScoreB?.let { "%.0f".format(it) } ?: "–"
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private val weekDayMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

/** Én periode: "A  ◀  Uke 39 · 22.09–28.09  ▶". Pil fram er av når perioden er den inneværende. */
@Composable
private fun PeriodStepperRow(
    title: String,
    type: ComparisonPeriodType,
    range: PeriodRange,
    today: LocalDate,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit
) {
    val label = when (type) {
        ComparisonPeriodType.WEEK -> stringResource(
            R.string.period_label_week,
            range.start.get(WeekFields.ISO.weekOfWeekBasedYear()),
            range.start.format(weekDayMonthFormatter),
            range.end.format(weekDayMonthFormatter)
        )
        ComparisonPeriodType.MONTH -> YearMonth.from(range.start)
            .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.width(72.dp))
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.action_previous_period))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.SemiBold)
            if (today in range) {
                Text(
                    stringResource(R.string.period_so_far),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.action_next_period))
        }
    }
}
