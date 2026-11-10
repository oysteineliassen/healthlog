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
import no.healthlog.app.ui.ListCard
import no.healthlog.app.ui.NavRow
import no.healthlog.app.ui.SectionTitle
import no.healthlog.app.ui.StatTile
import no.healthlog.app.ui.SubPage
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.roundToInt
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

    // Oversikten: én liten flis per måling (siste verdi, endring i perioden og mini-graf).
    // Trykk åpner detaljsiden med den store grafen fra trendCards.
    val hoursUnit = stringResource(R.string.unit_hours)
    fun plain(unit: String, decimals: Int): (Float) -> String = { v -> "${formatValue(v, decimals)} $unit".trim() }
    val tiles = listOf(
        TrendTile("hrv", stringResource(R.string.metric_label_hrv), hrvPoints, plain(stringResource(R.string.unit_ms), 0)),
        TrendTile("resting_hr", stringResource(R.string.metric_label_resting_hr), restingHeartRatePoints, plain(stringResource(R.string.unit_bpm), 0)),
        TrendTile("sleep", stringResource(R.string.metric_label_sleep), sleepPoints, { v -> formatDuration((v * 60).roundToInt()) }, plain(hoursUnit, 1)),
        TrendTile("sleep_score", stringResource(R.string.metric_label_sleep_score), sleepScorePoints, plain("", 0)),
        TrendTile("body_battery", stringResource(R.string.metric_label_body_battery), bodyBatteryPoints.map { TrendPoint(it.date, it.high) }, plain("", 0)),
        TrendTile("stress", stringResource(R.string.metric_label_stress), stressPoints, plain("", 0)),
        TrendTile("weight", stringResource(R.string.metric_title_weight), weightPoints, plain(stringResource(R.string.unit_kg), 1)),
        TrendTile("body_fat", stringResource(R.string.metric_title_body_fat), bodyFatPoints, plain("%", 1)),
        TrendTile("waist", stringResource(R.string.metric_title_waist), waistPoints, plain(stringResource(R.string.unit_cm), 1)),
        TrendTile("steps", stringResource(R.string.metric_label_steps), stepsPoints, { v -> "%,d".format(v.roundToInt()) }),
        TrendTile("active_minutes", stringResource(R.string.metric_label_active_minutes), activeMinutesPoints, plain(stringResource(R.string.unit_minutes), 0)),
        TrendTile("training", stringResource(R.string.metric_label_training), trainingPoints, plain(stringResource(R.string.unit_minutes), 0)),
        TrendTile("avg_heart_rate", stringResource(R.string.metric_label_avg_heart_rate), avgHeartRatePoints, plain(stringResource(R.string.unit_bpm), 0))
    ).filter { it.points.isNotEmpty() && it.id in trendCardsById }

    var openTrend by rememberSaveable { mutableStateOf<String?>(null) }
    var showComparison by rememberSaveable { mutableStateOf(false) }
    var showRecords by rememberSaveable { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }

    val rangeRow: @Composable () -> Unit = {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val ranges = listOf(
                TrendRange.DAYS_14 to R.string.range_14_days,
                TrendRange.DAYS_31 to R.string.range_31_days,
                TrendRange.YEAR_1 to R.string.range_1_year
            )
            ranges.forEachIndexed { index, (range, labelRes) ->
                SegmentedButton(
                    selected = selectedRange == range,
                    onClick = { selectedRange = range },
                    shape = SegmentedButtonDefaults.itemShape(index, ranges.size)
                ) { Text(stringResource(labelRes)) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.trends_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    FilledTonalIconButton(onClick = { showShareMenu = true }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_as_image)) },
                            onClick = { showShareMenu = false; shareSummary(asPdf = false) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_as_pdf)) },
                            onClick = { showShareMenu = false; shareSummary(asPdf = true) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_as_clinical_pdf)) },
                            onClick = { showShareMenu = false; shareClinicalPdf() }
                        )
                    }
                }
            }
        }

        item { rangeRow() }

        if (tiles.isEmpty()) {
            item {
                Text(stringResource(R.string.chart_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        TrendGroup.entries.forEach { group ->
            val groupTiles = tiles.filter { it.id in group.cardIds }
            if (groupTiles.isEmpty()) return@forEach
            item(key = "group_${group.name}") {
                SectionTitle(stringResource(group.labelRes), Modifier.padding(top = 6.dp))
            }
            item(key = "tiles_${group.name}") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    groupTiles.chunked(2).forEach { rowTiles ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            rowTiles.forEach { tile ->
                                val last = tile.points.last().value
                                val change = if (tile.points.size >= 2) last - tile.points.first().value else null
                                StatTile(
                                    label = tile.label,
                                    value = tile.format(last),
                                    modifier = Modifier.weight(1f),
                                    detail = change?.let {
                                        stringResource(
                                            R.string.trend_tile_change,
                                            (if (it > 0f) "+" else if (it < 0f) "−" else "") + tile.formatChange(abs(it))
                                        )
                                    },
                                    onClick = { openTrend = tile.id },
                                    extra = { Sparkline(tile.points) }
                                )
                            }
                            if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            ListCard(Modifier.padding(top = 6.dp)) {
                NavRow(stringResource(R.string.period_comparison_title), onClick = { showComparison = true })
                NavRow(stringResource(R.string.trends_records_row), onClick = { showRecords = true }, showDivider = false)
            }
        }
    }

    // Detaljsiden: periodevelger, vane-markering og den store grafen for målingen.
    openTrend?.let { id ->
        val tile = tiles.firstOrNull { it.id == id }
        SubPage(title = tile?.label ?: stringResource(R.string.trends_title), onBack = { openTrend = null }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                rangeRow()
                if (allHabitTags.isNotEmpty()) {
                    Column {
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
                }
                trendCardsById[id]?.invoke()
                if (id == "weight" || id == "body_fat") {
                    ListCard {
                        NavRow(stringResource(R.string.body_comp_title), onClick = { showBodyComposition = true }, showDivider = false)
                    }
                }
            }
        }
    }

    if (showComparison) {
        SubPage(title = stringResource(R.string.period_comparison_title), onBack = { showComparison = false }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                PeriodComparisonCard(days)
            }
        }
    }

    if (showRecords) {
        SubPage(title = stringResource(R.string.records_title), onBack = { showRecords = false }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                RecordsScreen(days, billingManager, showTitle = false)
            }
        }
    }
}

/** Én flis i Trender-oversikten. [formatChange] brukes for endringen når den skal vises annerledes enn verdien. */
private class TrendTile(
    val id: String,
    val label: String,
    val points: List<TrendPoint>,
    val format: (Float) -> String,
    val formatChange: (Float) -> String = format
)

/** Mini-graf uten akser: bare formen på utviklingen i perioden. */
@Composable
private fun Sparkline(points: List<TrendPoint>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        if (points.size < 2) {
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
            return@Canvas
        }
        val min = points.minOf { it.value }
        val max = points.maxOf { it.value }
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val pad = 3.dp.toPx()
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = size.width * i / (points.size - 1)
            val y = pad + (size.height - 2 * pad) * (1f - (p.value - min) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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
