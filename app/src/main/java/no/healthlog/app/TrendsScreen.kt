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

enum class TrendRange { DAYS_14, DAYS_31, YEAR_1 }

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
    val hrvBaseline = remember(sorted) { hrvBaselineSummary(sorted) }
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
    // Trykk på vektgrafen: kroppssammensetning for siste veiing (se BodyCompositionDialog).
    var showBodyComposition by rememberSaveable { mutableStateOf(false) }
    if (showBodyComposition) {
        BodyCompositionDialog(sorted, onDismiss = { showBodyComposition = false })
    }

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
    // 7-dagers snitt, som for vekt: HRV svinger mye fra natt til natt, trenden sier mer.
    val hrvSmoothed = if (isMonthly) {
        emptyList()
    } else {
        rollingAverage(hrvPoints.map { it.date to it.value.toDouble() })
            .map { (date, value) -> TrendPoint(date, value.toFloat()) }
    }
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
                footer = weightProjection?.let { projection -> @Composable { WeightProjectionText(projection) } },
                onClick = { showBodyComposition = true }
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
                smoothed = hrvSmoothed,
                footer = {
                    Text(
                        hrvBaseline?.let { (mean, sd) ->
                            stringResource(R.string.hrv_goal_explained, hrvGoal.value, mean, sd)
                        } ?: stringResource(R.string.hrv_goal_explained_generic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
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

    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
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

        // Gruppefilter: tolv-tretten grafer under hverandre blir langt - velg en gruppe
        // for å se bare de som hører sammen. Rekkefølgen du har dratt deg til beholdes.
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedGroup == null,
                    onClick = { selectedGroup = null },
                    label = { Text(stringResource(R.string.trend_group_all)) }
                )
                TrendGroup.entries.forEach { group ->
                    FilterChip(
                        selected = selectedGroup == group.name,
                        onClick = { selectedGroup = if (selectedGroup == group.name) null else group.name },
                        label = { Text(stringResource(group.labelRes)) }
                    )
                }
            }
        }

        val visibleGroupIds = selectedGroup?.let { name -> TrendGroup.entries.firstOrNull { it.name == name }?.cardIds }
        items(trendCardOrder.filter { visibleGroupIds == null || it in visibleGroupIds }, key = { it }) { id ->
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

/** Grupper for filteret over trendgrafene. [cardIds] er id-ene i trendCards. */
private enum class TrendGroup(val labelRes: Int, val cardIds: Set<String>) {
    BODY(R.string.trend_group_body, setOf("weight", "waist", "body_fat")),
    RECOVERY(R.string.trend_group_recovery, setOf("hrv", "resting_hr", "sleep", "sleep_score", "body_battery", "stress")),
    ACTIVITY(R.string.trend_group_activity, setOf("steps", "active_minutes", "training", "avg_heart_rate"))
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
