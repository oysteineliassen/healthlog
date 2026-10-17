package no.healthlog.app

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

enum class TrendRange { DAYS_14, DAYS_31, YEAR_1 }

/** Dager som skal markeres i grafene (vane-merke valgt øverst på Trender), og navnet som vises i tooltip. */
data class ChartHighlight(val dates: Set<LocalDate>, val label: String)

/** Lilla - skiller seg fra oransje syk/reise-markører og fra grafens egen farge. */
private val HighlightMarkerColor = Color(0xFF8E24AA)

data class TrendPoint(
    val date: LocalDate,
    val value: Float
)

data class TrendRangePoint(
    val date: LocalDate,
    val high: Float,
    val low: Float
)

/**
 * Bestemmer hvordan Y-aksens skala (proporsjonene i grafen) settes – uavhengig av
 * hvilke tall som faktisk vises som topp/bunn-etiketter (de viser alltid ekte
 * registrert høyeste/laveste verdi, se DatedLineChart/RangeBarChart).
 */
sealed class AxisRange {
    /** Fast skala, f.eks. 0–100 for score-baserte målinger. */
    data class Fixed(val min: Float, val max: Float) : AxisRange()

    /** [padding] over høyeste og under laveste registrerte verdi. */
    data class Padded(val padding: Float) : AxisRange()

    /** Alltid fra 0, med [paddingAbove] over høyeste registrerte verdi. */
    data class ZeroAnchored(val paddingAbove: Float = 0f) : AxisRange()

    fun resolve(dataMin: Float, dataMax: Float): Pair<Float, Float> = when (this) {
        is Fixed -> min to max
        is Padded -> (dataMin - padding) to (dataMax + padding)
        is ZeroAnchored -> 0f to (dataMax + paddingAbove)
    }
}

private val monthYearFormatter = DateTimeFormatter.ofPattern("MMM yyyy", norwegianLocale)

private fun formatPointDate(date: LocalDate, isMonthly: Boolean): String =
    if (isMonthly) {
        date.format(monthYearFormatter).replaceFirstChar { it.titlecase(norwegianLocale) }
    } else {
        date.format(dateFormatter)
    }

internal fun aggregateMonthly(points: List<TrendPoint>): List<TrendPoint> =
    points
        .groupBy { YearMonth.from(it.date) }
        .toSortedMap()
        .map { (month, monthPoints) ->
            TrendPoint(
                date = month.atDay(1),
                value = monthPoints.map { it.value }.average().toFloat()
            )
        }

internal fun aggregateMonthlyRange(points: List<TrendRangePoint>): List<TrendRangePoint> =
    points
        .groupBy { YearMonth.from(it.date) }
        .toSortedMap()
        .map { (month, monthPoints) ->
            TrendRangePoint(
                date = month.atDay(1),
                high = monthPoints.map { it.high }.average().toFloat(),
                low = monthPoints.map { it.low }.average().toFloat()
            )
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(days: List<HealthDay>, billingManager: BillingManager) {
    var selectedRange by remember { mutableStateOf(TrendRange.DAYS_14) }
    val isMonthly = selectedRange == TrendRange.YEAR_1
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val sorted = days.sortedBy { it.date }
    // Uavhengig av hvilken periode (14d/31d/1å) som er valgt over - baseline skal
    // alltid bygges på de nyeste faktiske målingene, ikke bare det som vises i grafen.
    val hrvGoal = remember(sorted) { calculateHrvGoal(sorted) }
    val restingHrGoal = remember(sorted) { calculateRestingHrGoal(sorted) }
    // Bruker DITT SATTE søvnmål (GoalPreferences, samme tall som Dagens mål og
    // Søvnoverskudd) i stedet for en egen automatisk utregnet baseline - de viste
    // ulike tall for samme ting før, forvirrende.
    val sleepGoalHours = GoalPreferences.getSleepGoalMinutes(context) / 60f
    // Bygges én gang, delt av alle grafene - lar hver graf markere dager du har
    // tagget (syk/reise/hviledag), slik at et hakk i kurven er forklart med et blikk
    // i stedet for at man må sjekke Historikk separat.
    val taggedDates = remember(sorted) {
        sorted.mapNotNull { day ->
            val tag = day.dayTag ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
            date to tag
        }.toMap()
    }
    val today = LocalDate.now()
    val cutoffDate = when (selectedRange) {
        TrendRange.DAYS_14 -> today.minusDays(13)
        TrendRange.DAYS_31 -> today.minusDays(30)
        TrendRange.YEAR_1 -> today.minusDays(364)
    }

    // Samme periode som grafene under viser, brukt av del-som-bilde/PDF-knappene.
    val periodDays = sorted.filter { day ->
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
        date != null && !date.isBefore(cutoffDate)
    }
    val periodLabel = "${cutoffDate.format(dateFormatter)} – ${today.format(dateFormatter)}"
    val summaryTitle = stringResource(R.string.share_summary_title)
    val clinicalTitle = stringResource(R.string.clinical_summary_title)

    fun shareSummary(asPdf: Boolean) {
        coroutineScope.launch {
            val file = withContext(Dispatchers.IO) {
                if (asPdf) {
                    createSummaryPdfFile(context, periodDays, summaryTitle, periodLabel)
                } else {
                    createSummaryImageFile(context, periodDays, summaryTitle, periodLabel)
                }
            }
            shareFile(context, file, if (asPdf) "application/pdf" else "image/png")
        }
    }

    fun shareClinicalPdf() {
        coroutineScope.launch {
            val file = withContext(Dispatchers.IO) {
                createClinicalPdfFile(context, periodDays, clinicalTitle, periodLabel)
            }
            shareFile(context, file, "application/pdf")
        }
    }

    fun pointsFor(valueSelector: (HealthDay) -> Float?): List<TrendPoint> {
        val daily = sorted.mapNotNull { day ->
            val date = runCatching {
                LocalDate.parse(day.date)
            }.getOrNull()

            val value = valueSelector(day)

            if (date != null && !date.isBefore(cutoffDate) && value != null) {
                TrendPoint(date = date, value = value)
            } else {
                null
            }
        }

        return if (isMonthly) aggregateMonthly(daily) else daily
    }

    fun pointsForRange(
        highSelector: (HealthDay) -> Float?,
        lowSelector: (HealthDay) -> Float?
    ): List<TrendRangePoint> {
        val daily = sorted.mapNotNull { day ->
            val date = runCatching {
                LocalDate.parse(day.date)
            }.getOrNull()

            val high = highSelector(day)
            val low = lowSelector(day)

            if (date != null && !date.isBefore(cutoffDate) && high != null && low != null) {
                TrendRangePoint(date = date, high = high, low = low)
            } else {
                null
            }
        }

        return if (isMonthly) aggregateMonthlyRange(daily) else daily
    }

    val weightPoints = pointsFor { it.weightKg?.toFloat() }
    val weightGoal = GoalPreferences.getWeightGoalKg(context)
    // Trendlinjen gir bare mening for daglige punkter - i årsvisningen er punktene
    // allerede månedssnitt.
    val weightSmoothed = if (isMonthly) {
        emptyList()
    } else {
        rollingAverage(weightPoints.map { it.date to it.value.toDouble() })
            .map { (date, value) -> TrendPoint(date, value.toFloat()) }
    }
    val weightProjection = remember(sorted, weightGoal) { projectWeight(sorted, today, weightGoal) }

    // Vane-merke valgt øverst - markeres med lilla prikker nederst i alle grafene.
    val allHabitTags = remember(sorted) { sorted.flatMap { it.habitTagSet() }.distinct().sorted() }
    var selectedHabit by rememberSaveable { mutableStateOf<String?>(null) }
    val habitHighlight = remember(sorted, selectedHabit) {
        selectedHabit?.let { tag ->
            ChartHighlight(
                dates = sorted.filter { tag in it.habitTagSet() }
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .toSet(),
                label = tag
            )
        }
    }
    val waistPoints = pointsFor { it.waistCm?.toFloat() }
    val hrvPoints = pointsFor { it.hrvMs?.toFloat() }
    val restingHeartRatePoints =
        pointsFor { it.restingHeartRate?.toFloat() }
    val avgHeartRatePoints =
        pointsFor { it.avgHeartRate?.toFloat() }
    val sleepPoints =
        pointsFor { it.sleepMinutes?.toFloat()?.div(60f) }
    val sleepScorePoints = pointsFor { it.sleepScore?.toFloat() }
    val stressPoints = pointsFor { it.stress?.toFloat() }
    val trainingPoints = pointsFor { it.trainingMinutes?.toFloat() }
    val stepsPoints = pointsFor { it.steps?.toFloat() }
    val activeMinutesPoints = pointsFor { it.activeMinutes?.toFloat() }
    val bodyBatteryPoints = pointsForRange(
        { it.bodyBatteryMax?.toFloat() },
        { it.bodyBatteryMin?.toFloat() }
    )

    // De tolv trendgrafene, bygget som en (id, innhold)-liste i stedet for enkeltvise
    // "item { ... }"-blokker - lar oss omorganisere dem etter en lagret rekkefølge.
    // Selve dra-i-håndtaket-interaksjonen (ikke hele kortet, og slett ikke grafen selv,
    // som allerede har sin egen trykk-og-hold-avlesning - se pointerInput lenger ned i
    // TrendCard/RangeTrendCard) unngår enhver konflikt med den eksisterende avlesningen.
    // Kroppsfett vises bare når det faktisk finnes målinger (krever smartvekt) - ellers
    // ville det stått et tomt kort for alle uten.
    val bodyFatPoints = pointsFor { it.bodyFatPercent?.toFloat() }
    val hasBodyFatData = remember(sorted) { sorted.any { it.bodyFatPercent != null } }
    // 7-dagers glidende snitt, som for vekt: fettprosent fra vekta svinger 1-2 poeng fra
    // dag til dag med væskebalansen, så trenden sier mer enn enkeltmålingene.
    val bodyFatSmoothed = if (isMonthly) {
        emptyList()
    } else {
        rollingAverage(bodyFatPoints.map { it.date to it.value.toDouble() })
            .map { (date, value) -> TrendPoint(date, value.toFloat()) }
    }
    val bodyFatCards: List<Pair<String, @Composable () -> Unit>> = if (hasBodyFatData) {
        listOf(
            "body_fat" to @Composable {
                TrendCard(
                    title = stringResource(R.string.metric_title_body_fat),
                    points = bodyFatPoints,
                    unit = "%",
                    decimals = 1,
                    isMonthly = isMonthly,
                    axisRange = AxisRange.Padded(2f),
                    taggedDates = taggedDates,
                    smoothed = bodyFatSmoothed,
                    highlight = habitHighlight
                )
            }
        )
    } else {
        emptyList()
    }

    val trendCards: List<Pair<String, @Composable () -> Unit>> = listOf(
        "weight" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_title_weight),
                points = weightPoints,
                unit = stringResource(R.string.unit_kg),
                decimals = 1,
                isMonthly = isMonthly,
                axisRange = AxisRange.Padded(2f),
                goalValue = weightGoal?.toFloat(),
                goalLabel = stringResource(R.string.goal_label_weight),
                taggedDates = taggedDates,
                smoothed = weightSmoothed,
                highlight = habitHighlight,
                includeGoalInScale = true,
                footer = weightProjection?.let { projection -> @Composable { WeightProjectionText(projection) } }
            )
        },
        "waist" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_title_waist),
                points = waistPoints,
                unit = stringResource(R.string.unit_cm),
                decimals = 1,
                isMonthly = isMonthly,
                axisRange = AxisRange.Padded(5f),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "hrv" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_hrv),
                points = hrvPoints,
                unit = stringResource(R.string.unit_ms),
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.Padded(5f),
                goalValue = hrvGoal.value,
                goalLabel = stringResource(
                    if (hrvGoal.isPersonalized) R.string.goal_label_hrv_personal else R.string.goal_label_hrv_generic
                ),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "resting_hr" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_resting_hr),
                points = restingHeartRatePoints,
                unit = stringResource(R.string.unit_bpm),
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.Padded(10f),
                goalValue = restingHrGoal.value,
                goalLabel = stringResource(
                    if (restingHrGoal.isPersonalized) R.string.goal_label_resting_hr_personal else R.string.goal_label_resting_hr_generic
                ),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "avg_heart_rate" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_avg_heart_rate),
                points = avgHeartRatePoints,
                unit = stringResource(R.string.unit_bpm),
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.Padded(10f),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "steps" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_steps),
                points = stepsPoints,
                unit = "",
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.Padded(500f),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "active_minutes" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_active_minutes),
                points = activeMinutesPoints,
                unit = stringResource(R.string.unit_minutes),
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.Padded(5f),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "sleep" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_sleep),
                points = sleepPoints,
                unit = stringResource(R.string.unit_hours),
                decimals = 1,
                isMonthly = isMonthly,
                axisRange = AxisRange.ZeroAnchored(),
                goalValue = sleepGoalHours,
                goalLabel = stringResource(R.string.goal_label_sleep_own),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "sleep_score" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_sleep_score),
                points = sleepScorePoints,
                unit = stringResource(R.string.unit_points),
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.Fixed(0f, 100f),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "body_battery" to @Composable {
            RangeTrendCard(
                title = stringResource(R.string.metric_label_body_battery),
                points = bodyBatteryPoints,
                unit = "",
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.Fixed(0f, 100f),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "stress" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_stress),
                points = stressPoints,
                unit = stringResource(R.string.unit_points),
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.ZeroAnchored(10f),
                goalValue = 30f,
                goalLabel = stringResource(R.string.goal_label_under),
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        },
        "training" to @Composable {
            TrendCard(
                title = stringResource(R.string.metric_label_training),
                points = trainingPoints,
                unit = stringResource(R.string.unit_minutes),
                decimals = 0,
                isMonthly = isMonthly,
                axisRange = AxisRange.ZeroAnchored(),
                goalValue = 30f,
                taggedDates = taggedDates,
                highlight = habitHighlight
            )
        }
    ) + bodyFatCards
    val trendCardsById = trendCards.toMap()

    var trendCardOrder by rememberSaveable {
        mutableStateOf(
            TilePreferences.getTrendCardOrder(context)?.filter { it in trendCardsById }
                ?: trendCards.map { it.first }
        )
    }
    LaunchedEffect(trendCardsById.keys) {
        val missing = trendCardsById.keys - trendCardOrder.toSet()
        if (missing.isNotEmpty()) trendCardOrder = trendCardOrder + missing
    }

    var draggingTrendCardId by remember { mutableStateOf<String?>(null) }
    var trendCardDragOffset by remember { mutableStateOf(Offset.Zero) }
    val trendCardBounds = remember { mutableStateMapOf<String, Rect>() }

    fun endTrendCardDrag() {
        val fromId = draggingTrendCardId
        val fromBounds = fromId?.let { trendCardBounds[it] }
        if (fromId != null && fromBounds != null) {
            val draggedCenter = fromBounds.center + trendCardDragOffset
            val targetId = trendCardBounds.entries
                .filter { it.key != fromId }
                .minByOrNull { (_, bounds) -> (bounds.center - draggedCenter).getDistance() }
                ?.key
            if (targetId != null) {
                val fromIndex = trendCardOrder.indexOf(fromId)
                val toIndex = trendCardOrder.indexOf(targetId)
                if (fromIndex != -1 && toIndex != -1) {
                    trendCardOrder = trendCardOrder.toMutableList().apply {
                        this[fromIndex] = targetId
                        this[toIndex] = fromId
                    }
                    TilePreferences.setTrendCardOrder(context, trendCardOrder)
                }
            }
        }
        draggingTrendCardId = null
        trendCardDragOffset = Offset.Zero
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(
            top = 28.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.trends_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = stringResource(R.string.trends_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedRange == TrendRange.DAYS_14,
                    onClick = { selectedRange = TrendRange.DAYS_14 },
                    label = { Text(stringResource(R.string.range_14_days)) }
                )
                FilterChip(
                    selected = selectedRange == TrendRange.DAYS_31,
                    onClick = { selectedRange = TrendRange.DAYS_31 },
                    label = { Text(stringResource(R.string.range_31_days)) }
                )
                FilterChip(
                    selected = selectedRange == TrendRange.YEAR_1,
                    onClick = { selectedRange = TrendRange.YEAR_1 },
                    label = { Text(stringResource(R.string.range_1_year)) }
                )
            }

            if (allHabitTags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.trends_habit_highlight_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allHabitTags.forEach { tag ->
                        FilterChip(
                            selected = selectedHabit == tag,
                            onClick = { selectedHabit = if (selectedHabit == tag) null else tag },
                            label = { Text(tag) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { shareSummary(asPdf = false) }) {
                    Text(stringResource(R.string.share_as_image))
                }
                OutlinedButton(onClick = { shareSummary(asPdf = true) }) {
                    Text(stringResource(R.string.share_as_pdf))
                }
                OutlinedButton(onClick = { shareClinicalPdf() }) {
                    Text(stringResource(R.string.share_as_clinical_pdf))
                }
            }
        }

        item {
            PeriodComparisonCard(days)
        }

        items(trendCardOrder, key = { it }) { id ->
            val content = trendCardsById[id]
            if (content != null) {
                ReorderableTrendCardBox(
                    isDragging = draggingTrendCardId == id,
                    dragOffset = trendCardDragOffset,
                    onPositioned = { bounds -> trendCardBounds[id] = bounds },
                    onDragStart = {
                        draggingTrendCardId = id
                        trendCardDragOffset = Offset.Zero
                    },
                    onDrag = { delta -> trendCardDragOffset += delta },
                    onDragEnd = { endTrendCardDrag() },
                    onDragCancel = {
                        draggingTrendCardId = null
                        trendCardDragOffset = Offset.Zero
                    },
                    content = content
                )
            }
        }
    }
}

/** Anslaget under vektgrafen: tempo per uke, og når målet nås med dagens tempo. */
@Composable
private fun WeightProjectionText(projection: WeightProjection) {
    val goalText = projection.goalKg?.let { "%.1f".format(it) } ?: ""
    Text(
        text = stringResource(
            R.string.weight_projection_trend,
            "%+.1f".format(projection.weeklyChangeKg),
            "%.1f".format(projection.currentTrendKg)
        ),
        style = MaterialTheme.typography.bodySmall
    )
    val statusText = when (projection.status) {
        WeightProjectionStatus.NO_GOAL -> stringResource(R.string.weight_projection_no_goal)
        WeightProjectionStatus.REACHED -> stringResource(R.string.weight_projection_reached, goalText)
        WeightProjectionStatus.ON_TRACK -> stringResource(
            R.string.weight_projection_on_track,
            goalText,
            projection.eta?.format(dateFormatter) ?: ""
        )
        WeightProjectionStatus.TOO_SLOW -> stringResource(R.string.weight_projection_too_slow, goalText)
        WeightProjectionStatus.AWAY -> stringResource(R.string.weight_projection_away, goalText)
    }
    Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatValue(value: Float, decimals: Int): String {
    return when (decimals) {
        0 -> "%.0f".format(value)
        else -> "%.1f".format(value)
    }
}

private fun nearestIndex(x: Float, offsets: List<Offset>): Int? {
    if (offsets.isEmpty()) return null
    return offsets.indices.minByOrNull { abs(offsets[it].x - x) }
}

@Composable
fun TrendCard(
    title: String,
    points: List<TrendPoint>,
    unit: String,
    decimals: Int = 1,
    isMonthly: Boolean = false,
    axisRange: AxisRange = AxisRange.Padded(0f),
    goalValue: Float? = null,
    goalLabel: String = stringResource(R.string.goal_label_default),
    taggedDates: Map<LocalDate, String> = emptyMap(),
    smoothed: List<TrendPoint> = emptyList(),
    highlight: ChartHighlight? = null,
    includeGoalInScale: Boolean = false,
    footer: (@Composable () -> Unit)? = null
) {
    val firstValue = points.firstOrNull()?.value
    val lastValue = points.lastOrNull()?.value
    val placeholder = stringResource(R.string.value_placeholder)

    val change = if (
        firstValue != null &&
        lastValue != null &&
        points.size >= 2
    ) {
        lastValue - firstValue
    } else {
        null
    }

    val changeText = change?.let { difference ->
        val prefix = if (difference > 0f) "+" else ""

        "$prefix${formatValue(difference, decimals)} $unit"
    } ?: placeholder

    val periodText = if (points.isNotEmpty()) {
        val firstDate = formatPointDate(points.first().date, isMonthly)
        val lastDate = formatPointDate(points.last().date, isMonthly)

        if (firstDate == lastDate) {
            firstDate
        } else {
            "$firstDate – $lastDate"
        }
    } else {
        stringResource(R.string.no_measurements)
    }

    Card(
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = periodText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    text = lastValue?.let {
                        "${formatValue(it, decimals)} $unit"
                    } ?: placeholder,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(14.dp))

            DatedLineChart(
                title, points, unit, decimals, isMonthly, axisRange, goalValue, goalLabel, taggedDates,
                smoothed = smoothed, highlight = highlight, includeGoalInScale = includeGoalInScale
            )

            if (points.size >= 2) {
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.label_change),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = changeText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = if (isMonthly) stringResource(R.string.label_months) else stringResource(R.string.label_measurements),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = points.size.toString(),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            footer?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
        }
    }
}

@Composable
fun DatedLineChart(
    title: String,
    points: List<TrendPoint>,
    unit: String,
    decimals: Int,
    isMonthly: Boolean = false,
    axisRange: AxisRange = AxisRange.Padded(0f),
    goalValue: Float? = null,
    goalLabel: String = stringResource(R.string.goal_label_default),
    taggedDates: Map<LocalDate, String> = emptyMap(),
    smoothed: List<TrendPoint> = emptyList(),
    highlight: ChartHighlight? = null,
    includeGoalInScale: Boolean = false
) {
    if (points.isEmpty()) {
        Text(
            text = stringResource(R.string.chart_no_data),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val chartColor = MaterialTheme.colorScheme.primary
    val guideColor =
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

    if (points.size == 1) {
        Column {
            Text(
                text = stringResource(R.string.chart_single_point),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(10.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                drawCircle(
                    color = chartColor,
                    center = Offset(
                        x = size.width / 2f,
                        y = size.height / 2f
                    ),
                    radius = 7f
                )
            }

            Text(
                text = formatPointDate(points.first().date, isMonthly),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        return
    }

    val values = points.map { it.value }
    // Vektmålet tas med i skalaen når det er bedt om, slik at mål-streken alltid er synlig
    // selv om du er et stykke unna målet ennå.
    val goalForScale = if (includeGoalInScale) goalValue else null
    val dataMinValue = listOfNotNull(values.minOrNull(), goalForScale).minOrNull() ?: 0f
    val dataMaxValue = listOfNotNull(values.maxOrNull(), goalForScale).maxOrNull() ?: 1f
    val (scaleMin, scaleMax) = axisRange.resolve(dataMinValue, dataMaxValue)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    // Nøytral "legg merke til dette"-farge for tagg-markører - ikke rødt/grønt, siden
    // en tagget dag (syk/reise/hviledag) verken er "bra" eller "dårlig" i seg selv.
    val tagMarkerColor = Color(0xFFFFA726)
    val density = LocalDensity.current

    val horizontalPaddingPx = 10f
    val verticalPaddingPx = 10f

    var canvasSize by remember(points) { mutableStateOf(IntSize.Zero) }
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    // Posisjonene brukes både til tegning i Canvas og til å plassere tooltipen,
    // så de regnes ut én gang her i stedet for separat begge steder. Skalaen
    // (scaleMin/scaleMax) kommer fra axisRange og kan avvike bevisst fra de
    // faktisk registrerte verdiene (dataMinValue/dataMaxValue) som vises i etikettene.
    val chartPointOffsets = remember(points, canvasSize, scaleMin, scaleMax) {
        if (canvasSize.width == 0 || points.size < 2) {
            emptyList()
        } else {
            val chartWidth = (canvasSize.width - horizontalPaddingPx * 2f).coerceAtLeast(1f)
            val chartHeight = (canvasSize.height - verticalPaddingPx * 2f).coerceAtLeast(1f)
            val valueRange = max(0.1f, scaleMax - scaleMin)
            // Posisjonerer punktene etter FAKTISK antall dager fra første måling, ikke
            // bare rekkefølge (indeks) - ellers ser en 8-dagers pause helt lik ut som en
            // 4-dagers pause i grafen, siden alle punkter da får like stor avstand.
            val firstDate = points.first().date
            val totalDays = ChronoUnit.DAYS.between(firstDate, points.last().date)
                .toFloat()
                .coerceAtLeast(1f)

            points.mapIndexed { index, point ->
                val daysSinceStart = ChronoUnit.DAYS.between(firstDate, point.date).toFloat()
                val normalizedX = daysSinceStart / totalDays
                val normalizedValue = (point.value - scaleMin) / valueRange
                Offset(
                    x = horizontalPaddingPx + normalizedX * chartWidth,
                    y = canvasSize.height - verticalPaddingPx - normalizedValue * chartHeight
                )
            }
        }
    }

    // Glidende snitt (kun vekt i dag) - samme dato- og verdiskala som punktene over.
    val smoothedOffsets = remember(points, smoothed, canvasSize, scaleMin, scaleMax) {
        if (canvasSize.width == 0 || points.size < 2 || smoothed.size < 2) {
            emptyList()
        } else {
            val chartWidth = (canvasSize.width - horizontalPaddingPx * 2f).coerceAtLeast(1f)
            val chartHeight = (canvasSize.height - verticalPaddingPx * 2f).coerceAtLeast(1f)
            val valueRange = max(0.1f, scaleMax - scaleMin)
            val firstDate = points.first().date
            val totalDays = ChronoUnit.DAYS.between(firstDate, points.last().date).toFloat().coerceAtLeast(1f)
            smoothed.map { p ->
                Offset(
                    x = horizontalPaddingPx + ChronoUnit.DAYS.between(firstDate, p.date).toFloat() / totalDays * chartWidth,
                    y = canvasSize.height - verticalPaddingPx - (p.value - scaleMin) / valueRange * chartHeight
                )
            }
        }
    }
    // Med trendlinje blir enkeltmålingene tonet ned, så det er snittet øyet følger.
    val rawColor = if (smoothedOffsets.isNotEmpty()) chartColor.copy(alpha = 0.35f) else chartColor

    Box(modifier = Modifier.fillMaxWidth()) {
        val chartPlaceholder = stringResource(R.string.value_placeholder)
        val chartLastValue = points.lastOrNull()?.value
        val chartPeriodText = "${formatPointDate(points.first().date, isMonthly)} – ${formatPointDate(points.last().date, isMonthly)}"
        val chartChangeText = if (points.size >= 2) {
            val difference = points.last().value - points.first().value
            val prefix = if (difference > 0f) "+" else ""
            "$prefix${formatValue(difference, decimals)} $unit"
        } else {
            chartPlaceholder
        }
        val chartDescription = stringResource(
            R.string.chart_accessibility_description,
            title,
            chartPeriodText,
            chartLastValue?.let { "${formatValue(it, decimals)} $unit" } ?: chartPlaceholder,
            chartChangeText
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics { contentDescription = chartDescription }
                .onSizeChanged { canvasSize = it }
                .pointerInput(points) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        selectedIndex = nearestIndex(down.position.x, chartPointOffsets)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                selectedIndex = nearestIndex(change.position.x, chartPointOffsets)
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        selectedIndex = null
                    }
                }
        ) {
            drawLine(
                color = guideColor,
                start = Offset(horizontalPaddingPx, verticalPaddingPx),
                end = Offset(horizontalPaddingPx, size.height - verticalPaddingPx),
                strokeWidth = 2f
            )

            drawLine(
                color = guideColor,
                start = Offset(horizontalPaddingPx, size.height - verticalPaddingPx),
                end = Offset(size.width - horizontalPaddingPx, size.height - verticalPaddingPx),
                strokeWidth = 2f
            )

            if (goalValue != null && scaleMax > scaleMin && goalValue in scaleMin..scaleMax) {
                val goalChartHeight = (size.height - verticalPaddingPx * 2f).coerceAtLeast(1f)
                val normalizedGoal = (goalValue - scaleMin) / max(0.1f, scaleMax - scaleMin)
                val goalY = size.height - verticalPaddingPx - normalizedGoal * goalChartHeight
                drawLine(
                    color = goalColor,
                    start = Offset(horizontalPaddingPx, goalY),
                    end = Offset(size.width - horizontalPaddingPx, goalY),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                )
            }

            for (index in 0 until chartPointOffsets.lastIndex) {
                drawLine(
                    color = rawColor,
                    start = chartPointOffsets[index],
                    end = chartPointOffsets[index + 1],
                    strokeWidth = if (smoothedOffsets.isNotEmpty()) 3f else 5f,
                    cap = StrokeCap.Round
                )
            }

            chartPointOffsets.forEach { point ->
                drawCircle(
                    color = rawColor,
                    center = point,
                    radius = 5f
                )
            }

            for (index in 0 until smoothedOffsets.lastIndex) {
                drawLine(
                    color = chartColor,
                    start = smoothedOffsets[index],
                    end = smoothedOffsets[index + 1],
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
            }

            // Små markører for taggede dager (syk/reise/hviledag) - tegnes ved en fast
            // høyde nær toppen, ikke ved selve datapunktets egen y-verdi, slik at de
            // aldri overlapper eller skjuler den faktiske kurven.
            points.forEachIndexed { index, point ->
                if (taggedDates.containsKey(point.date)) {
                    val x = chartPointOffsets.getOrNull(index)?.x ?: return@forEachIndexed
                    drawCircle(
                        color = tagMarkerColor,
                        center = Offset(x, verticalPaddingPx + 2f),
                        radius = 4f
                    )
                }
            }

            highlight?.let { h ->
                points.forEachIndexed { index, point ->
                    if (point.date in h.dates) {
                        val x = chartPointOffsets.getOrNull(index)?.x ?: return@forEachIndexed
                        drawCircle(
                            color = HighlightMarkerColor,
                            center = Offset(x, size.height - verticalPaddingPx - 2f),
                            radius = 4f
                        )
                    }
                }
            }

            selectedIndex?.let { index ->
                val point = chartPointOffsets.getOrNull(index) ?: return@let

                drawLine(
                    color = chartColor.copy(alpha = 0.5f),
                    start = Offset(point.x, verticalPaddingPx),
                    end = Offset(point.x, size.height - verticalPaddingPx),
                    strokeWidth = 2f
                )
                drawCircle(color = chartColor, center = point, radius = 9f)
                drawCircle(color = Color.White, center = point, radius = 3.5f)
            }
        }

        // Y-akse: viser aksens grenser (skalaen grafen faktisk tegnes mot), ikke
        // nødvendigvis de registrerte ekstremverdiene - de kan ligge et stykke inni
        // grafen når axisRange gir ekstra pusterom (f.eks. Padded/Fixed).
        Text(
            text = "${formatValue(scaleMax, decimals)} $unit",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )

        Text(
            text = "${formatValue(scaleMin, decimals)} $unit",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )

        // Tooltip som følger fingeren ved trykk-og-dra, samme mønster som i Garmin-appen.
        selectedIndex?.let { index ->
            val point = points.getOrNull(index)
            val offsetPx = chartPointOffsets.getOrNull(index)

            if (point != null && offsetPx != null) {
                val canvasWidthDp = with(density) { canvasSize.width.toFloat().toDp() }
                val anchorXDp = with(density) { offsetPx.x.toDp() }
                val tooltipHalfWidth = 52.dp
                val clampedX = (anchorXDp - tooltipHalfWidth)
                    .coerceIn(0.dp, (canvasWidthDp - tooltipHalfWidth * 2).coerceAtLeast(0.dp))

                Card(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = clampedX, y = 0.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = formatPointDate(point.date, isMonthly),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        Text(
                            text = stringResource(R.string.tooltip_value_format, formatValue(point.value, decimals), unit),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        taggedDates[point.date]?.let { tag ->
                            Text(
                                text = dayTagLabel(tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                        if (highlight != null && point.date in highlight.dates) {
                            Text(
                                text = highlight.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                    }
                }
            }
        }
    }

    if (smoothedOffsets.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.chart_legend_rolling_average),
            style = MaterialTheme.typography.labelSmall,
            color = chartColor
        )
    }

    if (goalValue != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.goal_caption_format, goalLabel, formatValue(goalValue, decimals), unit),
            style = MaterialTheme.typography.labelSmall,
            color = goalColor
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatPointDate(points.first().date, isMonthly),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = formatPointDate(points.last().date, isMonthly),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatWithUnit(value: Float, decimals: Int, unit: String): String =
    if (unit.isBlank()) formatValue(value, decimals) else "${formatValue(value, decimals)} $unit"

@Composable
fun RangeTrendCard(
    title: String,
    points: List<TrendRangePoint>,
    unit: String,
    decimals: Int = 0,
    isMonthly: Boolean = false,
    axisRange: AxisRange = AxisRange.Padded(0f),
    taggedDates: Map<LocalDate, String> = emptyMap(),
    highlight: ChartHighlight? = null
) {
    val lastPoint = points.lastOrNull()
    val placeholder = stringResource(R.string.value_placeholder)

    val periodText = if (points.isNotEmpty()) {
        val firstDate = formatPointDate(points.first().date, isMonthly)
        val lastDate = formatPointDate(points.last().date, isMonthly)

        if (firstDate == lastDate) firstDate else "$firstDate – $lastDate"
    } else {
        stringResource(R.string.no_measurements)
    }

    Card(
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = periodText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = lastPoint?.let { formatWithUnit(it.high, decimals, unit) } ?: placeholder,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (lastPoint != null) {
                        Text(
                            text = formatWithUnit(lastPoint.low, decimals, unit),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            RangeBarChart(title, points, unit, decimals, isMonthly, axisRange, taggedDates, highlight)

            if (points.size >= 2) {
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.label_avg_high_low),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = "${formatWithUnit(points.map { it.high }.average().toFloat(), decimals, unit)} / " +
                                formatWithUnit(points.map { it.low }.average().toFloat(), decimals, unit),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = if (isMonthly) stringResource(R.string.label_months) else stringResource(R.string.label_measurements),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = points.size.toString(),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RangeBarChart(
    title: String,
    points: List<TrendRangePoint>,
    unit: String,
    decimals: Int,
    isMonthly: Boolean = false,
    axisRange: AxisRange = AxisRange.Padded(0f),
    taggedDates: Map<LocalDate, String> = emptyMap(),
    highlight: ChartHighlight? = null
) {
    if (points.isEmpty()) {
        Text(
            text = stringResource(R.string.chart_no_data),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val highColor = MaterialTheme.colorScheme.primary
    val lowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tagMarkerColor = Color(0xFFFFA726)
    val density = LocalDensity.current

    if (points.size == 1) {
        val p = points.first()
        Column {
            Text(
                text = stringResource(R.string.chart_single_point),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${formatWithUnit(p.high, decimals, unit)} / ${formatWithUnit(p.low, decimals, unit)}",
                fontWeight = FontWeight.SemiBold
            )
        }
        return
    }

    val allValues = points.flatMap { listOf(it.high, it.low) }
    val dataMinValue = allValues.minOrNull() ?: 0f
    val dataMaxValue = allValues.maxOrNull() ?: 1f
    val (scaleMin, scaleMax) = axisRange.resolve(dataMinValue, dataMaxValue)

    val horizontalPaddingPx = 10f
    val verticalPaddingPx = 10f

    var canvasSize by remember(points) { mutableStateOf(IntSize.Zero) }
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    data class BarOffsets(val x: Float, val highY: Float, val lowY: Float)

    val barOffsets = remember(points, canvasSize, scaleMin, scaleMax) {
        if (canvasSize.width == 0) {
            emptyList()
        } else {
            val chartWidth = (canvasSize.width - horizontalPaddingPx * 2f).coerceAtLeast(1f)
            val chartHeight = (canvasSize.height - verticalPaddingPx * 2f).coerceAtLeast(1f)
            val valueRange = max(0.1f, scaleMax - scaleMin)
            // Samme prinsipp som i DatedLineChart - avstand langs X etter faktisk antall
            // dager, ikke bare indeks, slik at ujevne måleintervaller vises korrekt.
            val firstDate = points.first().date
            val totalDays = if (points.size > 1) {
                ChronoUnit.DAYS.between(firstDate, points.last().date).toFloat().coerceAtLeast(1f)
            } else {
                1f
            }

            points.mapIndexed { index, p ->
                val daysSinceStart = ChronoUnit.DAYS.between(firstDate, p.date).toFloat()
                val x = horizontalPaddingPx + (daysSinceStart / totalDays) * chartWidth
                val highNorm = (p.high - scaleMin) / valueRange
                val lowNorm = (p.low - scaleMin) / valueRange
                BarOffsets(
                    x = x,
                    highY = canvasSize.height - verticalPaddingPx - highNorm * chartHeight,
                    lowY = canvasSize.height - verticalPaddingPx - lowNorm * chartHeight
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        val chartDescription = stringResource(
            R.string.range_chart_accessibility_description,
            title,
            formatPointDate(points.first().date, isMonthly),
            formatPointDate(points.last().date, isMonthly),
            formatWithUnit(points.last().high, decimals, unit),
            formatWithUnit(points.last().low, decimals, unit)
        )
        Canvas(
            modifier = Modifier
                .semantics { contentDescription = chartDescription }
                .fillMaxWidth()
                .height(150.dp)
                .onSizeChanged { canvasSize = it }
                .pointerInput(points) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        selectedIndex = nearestIndex(down.position.x, barOffsets.map { Offset(it.x, 0f) })
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                selectedIndex = nearestIndex(change.position.x, barOffsets.map { Offset(it.x, 0f) })
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        selectedIndex = null
                    }
                }
        ) {
            drawLine(
                color = guideColor,
                start = Offset(horizontalPaddingPx, size.height - verticalPaddingPx),
                end = Offset(size.width - horizontalPaddingPx, size.height - verticalPaddingPx),
                strokeWidth = 2f
            )

            barOffsets.forEach { b ->
                drawLine(
                    color = barColor,
                    start = Offset(b.x, b.highY),
                    end = Offset(b.x, b.lowY),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = highColor, center = Offset(b.x, b.highY), radius = 6f)
                drawCircle(color = lowColor, center = Offset(b.x, b.lowY), radius = 6f)
            }

            points.forEachIndexed { index, point ->
                if (taggedDates.containsKey(point.date)) {
                    val x = barOffsets.getOrNull(index)?.x ?: return@forEachIndexed
                    drawCircle(
                        color = tagMarkerColor,
                        center = Offset(x, verticalPaddingPx + 2f),
                        radius = 4f
                    )
                }
            }

            highlight?.let { h ->
                points.forEachIndexed { index, point ->
                    if (point.date in h.dates) {
                        val x = barOffsets.getOrNull(index)?.x ?: return@forEachIndexed
                        drawCircle(
                            color = HighlightMarkerColor,
                            center = Offset(x, size.height - verticalPaddingPx - 2f),
                            radius = 4f
                        )
                    }
                }
            }

            selectedIndex?.let { index ->
                val b = barOffsets.getOrNull(index) ?: return@let

                drawLine(
                    color = highColor.copy(alpha = 0.35f),
                    start = Offset(b.x, verticalPaddingPx),
                    end = Offset(b.x, size.height - verticalPaddingPx),
                    strokeWidth = 2f
                )
                drawCircle(color = highColor, center = Offset(b.x, b.highY), radius = 9f)
                drawCircle(color = Color.White, center = Offset(b.x, b.highY), radius = 3.5f)
                drawCircle(color = lowColor, center = Offset(b.x, b.lowY), radius = 9f)
                drawCircle(color = Color.White, center = Offset(b.x, b.lowY), radius = 3.5f)
            }
        }

        Text(
            text = formatWithUnit(scaleMax, decimals, unit),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )

        Text(
            text = formatWithUnit(scaleMin, decimals, unit),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )

        selectedIndex?.let { index ->
            val point = points.getOrNull(index)
            val b = barOffsets.getOrNull(index)

            if (point != null && b != null) {
                val canvasWidthDp = with(density) { canvasSize.width.toFloat().toDp() }
                val anchorXDp = with(density) { b.x.toDp() }
                val tooltipHalfWidth = 56.dp
                val clampedX = (anchorXDp - tooltipHalfWidth)
                    .coerceIn(0.dp, (canvasWidthDp - tooltipHalfWidth * 2).coerceAtLeast(0.dp))

                Card(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = clampedX, y = 0.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = formatPointDate(point.date, isMonthly),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        Text(
                            text = stringResource(R.string.tooltip_high_format, formatWithUnit(point.high, decimals, unit)),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        Text(
                            text = stringResource(R.string.tooltip_low_format, formatWithUnit(point.low, decimals, unit)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        taggedDates[point.date]?.let { tag ->
                            Text(
                                text = dayTagLabel(tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                        if (highlight != null && point.date in highlight.dates) {
                            Text(
                                text = highlight.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatPointDate(points.first().date, isMonthly),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = formatPointDate(points.last().date, isMonthly),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

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

    Card(shape = RoundedCornerShape(22.dp)) {
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

/**
 * Rammer inn ett trendkort med et lite dra-håndtak øverst til høyre - bevisst IKKE
 * dra-i-hele-kortet, siden selve grafen allerede bruker vanlig trykk (ikke langt
 * trykk) for sin egen "les av nøyaktig verdi"-funksjon. Håndtaket er et helt eget,
 * lite berøringsmål utenfor grafen, så de to interaksjonene aldri kan kollidere.
 */
@Composable
private fun ReorderableTrendCardBox(
    isDragging: Boolean,
    dragOffset: Offset,
    onPositioned: (Rect) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onPositioned(coords.boundsInRoot()) }
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationY = dragOffset.y
                    scaleX = 1.02f
                    scaleY = 1.02f
                    shadowElevation = 12f
                }
            }
    ) {
        content()

        Text(
            text = "⋮⋮",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta ->
                            change.consume()
                            onDrag(delta)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() }
                    )
                }
        )
    }
}
