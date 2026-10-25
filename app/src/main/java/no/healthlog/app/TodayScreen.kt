package no.healthlog.app

import no.healthlog.app.coach.FastingPeriod
import no.healthlog.app.coach.AiCoachPreferences
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.healthlog.app.billing.BillingManager
import no.healthlog.app.garmin.HealthConnectManager
import no.healthlog.app.coach.buildWeeklySummary
import no.healthlog.app.coach.buildMonthlySummary
import no.healthlog.app.data.HealthDay
import no.healthlog.app.data.trainingSessionList
import no.healthlog.app.share.createSummaryImageFile
import no.healthlog.app.share.createSummaryPdfFile
import no.healthlog.app.share.shareFile
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

@Composable
fun HealthScoreCard(day: HealthDay?, modifier: Modifier = Modifier, allDays: List<HealthDay> = emptyList()) {

    val context = LocalContext.current
    // Samme mål (fra Innstillinger) brukes nå overalt i appen som regner ut helse-score
    // - se calculateHealthScore sin dokumentasjon.
    val breakdown = calculateHealthScoreBreakdown(
        day,
        sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context),
        stepsGoal = GoalPreferences.getStepsGoal(context),
        hrvRange = remember(allDays) { personalHrvScoreRange(allDays) }
    )
    val score = breakdown.total
    val status = healthScoreStatus(context, score)
    var showDetail by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.clickable { showDetail = true },
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            Modifier.padding(20.dp)
        ) {

            Text(
                stringResource(R.string.health_score_title),
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(10.dp))

            Text(
                "$score / 100",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(Modifier.height(6.dp))

            Text(
                status,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showDetail) {
        HealthScoreDetailDialog(day = day, breakdown = breakdown, onDismiss = { showDetail = false })
    }
}

/** Vises ved trykk på Helse-score-kortet - hvert kriterium med faktisk verdi og poeng. */
@Composable
private fun HealthScoreDetailDialog(day: HealthDay?, breakdown: HealthScoreBreakdown, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.health_score_detail_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(18.dp))

                breakdown.criteria.forEach { criterion ->
                    HealthScoreDetailRow(day, criterion)
                    Spacer(Modifier.height(14.dp))
                }

                HorizontalDivider()

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.health_score_detail_total), fontWeight = FontWeight.Bold)
                    Text("${breakdown.total} / 100", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(18.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun HealthScoreDetailRow(day: HealthDay?, criterion: HealthScoreCriterion) {
    val (label, valueText) = when (criterion.type) {
        HealthScoreCriterionType.SLEEP ->
            stringResource(R.string.metric_label_sleep) to (day?.sleepMinutes?.let { "${it / 60}t ${it % 60}min" } ?: "–")
        HealthScoreCriterionType.HRV ->
            stringResource(R.string.metric_label_hrv) to (day?.hrvMs?.let { "${it.toInt()} ms" } ?: "–")
        HealthScoreCriterionType.STRESS ->
            stringResource(R.string.metric_label_stress) to (day?.stress?.toString() ?: "–")
        HealthScoreCriterionType.STEPS ->
            stringResource(R.string.metric_label_steps) to (day?.steps?.let { "%,d".format(it) } ?: "–")
        HealthScoreCriterionType.BODY_BATTERY ->
            stringResource(R.string.metric_label_body_battery) to (day?.bodyBatteryMax?.toString() ?: "–")
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${criterion.points} / ${criterion.maxPoints}", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(6.dp))

        LinearProgressIndicator(
            progress = { criterion.points / criterion.maxPoints.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}

@Composable
fun WeightTrendCard(days: List<HealthDay>) {

    val change = weightChange(days)

    if (change == null) return

    val color =
        if (change <= 0)
            Color(0xFF2E7D32)
        else
            Color(0xFFC62828)

    val arrow =
        if (change <= 0)
            "↓"
        else
            "↑"

    Card(
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            Modifier.padding(20.dp)
        ) {

            Text(
                stringResource(R.string.weight_trend_title),
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.weight_trend_change, arrow, "%.1f".format(kotlin.math.abs(change))),
                color = color,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/** Samme grønnfarge som allerede brukes i WeightTrendCard, for konsistens. */
private val DailyGoalCompleteColor = Color(0xFF2E7D32)

@Composable
fun DailyGoalsCard(day: HealthDay?, modifier: Modifier = Modifier) {

    val context = LocalContext.current
    val stepsGoal = GoalPreferences.getStepsGoal(context)
    val trainingGoal = GoalPreferences.getTrainingGoalMinutes(context)
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val sleepGoalHours = sleepGoalMinutes / 60

    // Fraksjon (0-1) i stedet for bare oppfylt/ikke oppfylt - lar kortet vise en
    // fremdriftslinje per mål fremfor et strengt ✅/❌-preg. Registrert vekt/midjemål
    // har ingen naturlig "delvis" tilstand, så de blir enten 0 eller 1.
    val goals = listOf(
        stringResource(R.string.goal_steps, stepsGoal) to ((day?.steps ?: 0).toFloat() / stepsGoal.coerceAtLeast(1)),
        stringResource(R.string.goal_training, trainingGoal) to ((day?.trainingMinutes ?: 0).toFloat() / trainingGoal.coerceAtLeast(1)),
        stringResource(R.string.goal_weight_recorded) to (if (day?.weightKg != null) 1f else 0f),
        stringResource(R.string.goal_waist_recorded) to (if (day?.waistCm != null) 1f else 0f),
        stringResource(R.string.goal_sleep, sleepGoalHours) to ((day?.sleepMinutes ?: 0).toFloat() / sleepGoalMinutes.coerceAtLeast(1))
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            Modifier.padding(20.dp)
        ) {

            Text(
                stringResource(R.string.daily_goals_title),
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(14.dp))

            goals.forEachIndexed { index, (name, fraction) ->
                val clampedFraction = fraction.coerceIn(0f, 1f)
                val isComplete = clampedFraction >= 1f

                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isComplete) DailyGoalCompleteColor else MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(4.dp))

                LinearProgressIndicator(
                    progress = { clampedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isComplete) DailyGoalCompleteColor else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                if (index != goals.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun TodayScreen(
    today: HealthDay?,
    all: List<HealthDay>,
    billingManager: BillingManager,
    healthConnectManager: HealthConnectManager,
    refreshKey: Int = 0,
    fastingPeriods: List<FastingPeriod> = emptyList(),
    onWeigh: () -> Unit = {}
) {
    val date = LocalDate.now().format(dateFormatter)
    val context = LocalContext.current
    var cardOrder by remember { mutableStateOf(resolveTodayCardOrder(TilePreferences.getTodayCardOrder(context))) }
    var hiddenCards by remember { mutableStateOf(TilePreferences.getHiddenTodayCards(context)) }
    // Ekstern AI-trener er valgfri og slås på i Innstillinger - av betyr at kortet
    // ikke finnes, heller ikke i "Tilpass I dag".
    val aiCoachEnabled = remember { AiCoachPreferences.isEnabled(context) }
    var showCustomize by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.today_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(date, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onWeigh) {
                    Text(stringResource(R.string.action_weigh))
                }
                TextButton(onClick = { showCustomize = true }) {
                    Text(stringResource(R.string.today_customize))
                }
            }
        }

        // Kortene i din valgte rekkefølge, uten de du har skjult (se TodayCustomization.kt).
        cardOrder.filter { it !in hiddenCards }.forEach { id ->
            when (id) {
                "weekly_summary" -> item(key = id) { WeeklySummaryCard(all) }
                "monthly_summary" -> item(key = id) { MonthlySummaryCard(all) }
                "summary" -> item(key = id) { SummaryCard(stringResource(R.string.summary_card_title), today) }
                "diary" -> item(key = id) { DiaryCard(today) }
                "readiness" -> item(key = id) { ReadinessCard(all) }
                "fasting" -> item(key = id) { FastingCard(all, fastingPeriods) }
                "coach" -> item(key = id) { CoachCard(all) }
                "ai_coach" -> if (aiCoachEnabled) item(key = id) { AiCoachCard(all) }
                "score_goals" -> item(key = id) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        HealthScoreCard(today, modifier = Modifier.weight(1f), allDays = all)
                        DailyGoalsCard(today, modifier = Modifier.weight(1f))
                    }
                }
                "weekly_activity" -> item(key = id) { WeeklyActivityCard(all) }
                "heart_rate" -> item(key = id) { HeartRateChartCard(healthConnectManager, refreshKey) }
                "sleep_debt" -> item(key = id) { SleepDebtCard(all) }
                "weight_trend" -> item(key = id) { WeightTrendCard(all) }
                "metrics" -> {
                    item(key = "metrics_title") {
                        Text(
                            stringResource(R.string.metrics_section_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item(key = id) { MetricGrid(today) }
                }
            }
        }
    }

    if (showCustomize) {
        TodayCustomizeDialog(
            order = if (aiCoachEnabled) cardOrder else cardOrder.filter { it != "ai_coach" },
            hidden = hiddenCards,
            onChange = { newOrder, newHidden ->
                cardOrder = newOrder
                hiddenCards = newHidden
                TilePreferences.setTodayCards(context, newOrder, newHidden)
            },
            onDismiss = { showCustomize = false }
        )
    }
}

@Composable
fun SummaryCard(title: String, day: HealthDay?) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (day == null) {
                    stringResource(R.string.no_data_summary)
                } else {
                    stringResource(
                        R.string.summary_steps_sleep_format,
                        day.steps?.let { "%,d".format(it) } ?: "–",
                        day.sleepMinutes?.let { "${it / 60} t ${it % 60} min" } ?: "–"
                    )
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.data_stored_locally),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DiaryCard(day: HealthDay?) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            // Vises bare når den er skrevet - ingen "tom"-tekst, så kortet ikke blir
            // lengre for de som ikke bruker feltet.
            if (!day?.sleepReflection.isNullOrBlank()) {
                Text(stringResource(R.string.label_sleep_reflection), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(day?.sleepReflection ?: "", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(18.dp))
            }

            Text(stringResource(R.string.label_morning_plan), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (day?.morningNote.isNullOrBlank()) {
                Text(
                    stringResource(R.string.no_morning_plan),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    day?.morningNote ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(stringResource(R.string.label_evening_summary), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (day?.eveningNote.isNullOrBlank()) {
                Text(
                    stringResource(R.string.no_evening_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    day?.eveningNote ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private data class GridMetric(val id: String, val label: String, val value: String, val detail: String? = null)

/** Rekkefølgen appen starter med, og den som brukes for flistyper som ikke finnes i en tidligere lagret rekkefølge. */
private val DEFAULT_TILE_ORDER = listOf(
    "resting_hr", "avg_heart_rate", "hrv", "sleep", "sleep_score",
    "body_battery", "stress", "steps", "active_minutes", "training", "weight_waist"
)

/** Kompakt "1t 22m"-format for søvnstadiene - full "t"/"min"-stil er brukt for selve totalen over. */
private fun formatSleepStageDuration(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}t ${minutes}m" else "${minutes}m"
}

/**
 * Trykk-og-hold en flis, dra den over en annen, slipp for å BYTTE plass på de to -
 * bevisst valgt fremfor en flytende "skyv alle andre til side"-omorganisering, som
 * ville vært mer kompleks å få robust og ville økt risikoen for utilsiktede bytter
 * ved vanlig skrolling. Rekkefølgen lagres permanent ved hvert bytte.
 */
@Composable
fun MetricGrid(day: HealthDay?) {
    val context = LocalContext.current

    val deepLabel = stringResource(R.string.sleep_stage_deep_short)
    val lightLabel = stringResource(R.string.sleep_stage_light_short)
    val remLabel = stringResource(R.string.sleep_stage_rem_short)
    val awakeLabel = stringResource(R.string.sleep_stage_awake_short)

    val sleepDetail = day?.let { d ->
        val parts = listOfNotNull(
            d.deepSleepMinutes?.let { "$deepLabel ${formatSleepStageDuration(it)}" },
            d.lightSleepMinutes?.let { "$lightLabel ${formatSleepStageDuration(it)}" },
            d.remSleepMinutes?.let { "$remLabel ${formatSleepStageDuration(it)}" },
            d.sleepAwakeMinutes?.let { "$awakeLabel ${formatSleepStageDuration(it)}" }
        )
        parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    val metricsById = listOf(
        GridMetric("resting_hr", stringResource(R.string.metric_label_resting_hr), day?.restingHeartRate?.let { "$it bpm" } ?: "–"),
        GridMetric("avg_heart_rate", stringResource(R.string.metric_label_avg_heart_rate), day?.avgHeartRate?.let { "$it bpm" } ?: "–"),
        GridMetric("hrv", stringResource(R.string.metric_label_hrv), day?.hrvMs?.let { "${it.toInt()} ms" } ?: "–"),
        GridMetric(
            "sleep",
            stringResource(R.string.metric_label_sleep),
            day?.sleepMinutes?.let { "${it / 60} t ${it % 60} min" } ?: "–",
            sleepDetail
        ),
        GridMetric("sleep_score", stringResource(R.string.metric_label_sleep_score), day?.sleepScore?.toString() ?: "–"),
        GridMetric("body_battery", stringResource(R.string.metric_label_body_battery), if (day?.bodyBatteryMax != null) "${day.bodyBatteryMax} / ${day.bodyBatteryMin ?: "–"}" else "–"),
        GridMetric("stress", stringResource(R.string.metric_label_stress), day?.stress?.toString() ?: "–"),
        GridMetric("steps", stringResource(R.string.metric_label_steps), day?.steps?.let { "%,d".format(it) } ?: "–"),
        GridMetric("active_minutes", stringResource(R.string.metric_label_active_minutes), day?.activeMinutes?.let { "$it min" } ?: "–"),
        GridMetric(
            "training",
            stringResource(R.string.metric_label_training),
            day?.trainingMinutes?.let { minutes ->
                val type = day.trainingType?.let { ", ${trainingTypeLabel(it)}" } ?: ""
                val intensity = day.trainingIntensity?.let { " (${trainingIntensityLabel(it)})" } ?: ""
                val sessionCount = day.trainingSessionList().size
                val extraSessions = if (sessionCount > 1) {
                    " " + stringResource(R.string.metric_training_extra_sessions, sessionCount - 1)
                } else {
                    ""
                }
                "$minutes min$type$intensity$extraSessions"
            } ?: "–"
        ),
        GridMetric("weight_waist", stringResource(R.string.metric_label_weight_waist), if (day?.weightKg != null) "${day.weightKg} kg / ${day.waistCm ?: "–"} cm" else "–")
    ).associateBy { it.id }

    var order by rememberSaveable {
        mutableStateOf(
            TilePreferences.getMetricTileOrder(context)?.filter { it in metricsById } ?: DEFAULT_TILE_ORDER
        )
    }
    // Fanger opp eventuelle nye flistyper lagt til i en senere app-oppdatering, som
    // ikke finnes i en tidligere lagret rekkefølge - legges bakerst, i stedet for å
    // forsvinne helt fra visningen.
    LaunchedEffect(metricsById.keys) {
        val missing = metricsById.keys - order.toSet()
        if (missing.isNotEmpty()) order = order + missing
    }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val tileBounds = remember { mutableStateMapOf<String, Rect>() }

    fun endDrag() {
        val fromId = draggingId
        val fromBounds = fromId?.let { tileBounds[it] }
        if (fromId != null && fromBounds != null) {
            val draggedCenter = fromBounds.center + dragOffset
            val targetId = tileBounds.entries
                .filter { it.key != fromId }
                .minByOrNull { (_, bounds) -> (bounds.center - draggedCenter).getDistance() }
                ?.key
            if (targetId != null) {
                val fromIndex = order.indexOf(fromId)
                val toIndex = order.indexOf(targetId)
                if (fromIndex != -1 && toIndex != -1) {
                    order = order.toMutableList().apply {
                        this[fromIndex] = targetId
                        this[toIndex] = fromId
                    }
                    TilePreferences.setMetricTileOrder(context, order)
                }
            }
        }
        draggingId = null
        dragOffset = Offset.Zero
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        order.chunked(2).forEach { rowIds ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowIds.forEach { id ->
                    val metric = metricsById[id] ?: return@forEach
                    val isDragging = draggingId == id

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords -> tileBounds[id] = coords.boundsInRoot() }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    shadowElevation = 12f
                                }
                            }
                            .pointerInput(order) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = id
                                        dragOffset = Offset.Zero
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        dragOffset += delta
                                    },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = Offset.Zero
                                    }
                                )
                            },
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.padding(15.dp)) {
                            Text(metric.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                metric.value,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            metric.detail?.let {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (rowIds.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Vises kun på søndager (siste dag i uken) - en liten "ukas oppsummering" som
 * sammenligner de siste 7 dagene med de 7 dagene før det. Trenger ikke egen
 * "allerede vist"-sporing slik CoachCard har, siden den naturlig kun er synlig én
 * dag i uken uansett.
 */
@Composable
fun WeeklySummaryCard(all: List<HealthDay>) {
    val today = LocalDate.now()
    if (today.dayOfWeek != DayOfWeek.SUNDAY) return

    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val stepsGoal = GoalPreferences.getStepsGoal(context)
    val summary = remember(all, sleepGoalMinutes, stepsGoal) {
        buildWeeklySummary(all, today, sleepGoalMinutes, stepsGoal)
    } ?: return

    val coroutineScope = rememberCoroutineScope()
    val weekStart = today.minusDays(6)
    val weekDays = remember(all, today) {
        all.filter { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
            date != null && !date.isBefore(weekStart) && !date.isAfter(today)
        }
    }
    val periodLabel = "${weekStart.format(dateFormatter)} – ${today.format(dateFormatter)}"
    val summaryTitle = stringResource(R.string.weekly_summary_title)

    fun shareWeek(asPdf: Boolean) {
        coroutineScope.launch {
            val file = withContext(Dispatchers.IO) {
                if (asPdf) {
                    createSummaryPdfFile(context, weekDays, summaryTitle, periodLabel)
                } else {
                    createSummaryImageFile(context, weekDays, summaryTitle, periodLabel)
                }
            }
            shareFile(context, file, if (asPdf) "application/pdf" else "image/png")
        }
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.weekly_summary_title), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            Text(stringResource(R.string.weekly_summary_steps, "%,d".format(summary.stepsThisWeek)))
            summary.stepsLastWeek?.let {
                Text(
                    stringResource(R.string.weekly_summary_prev_week, "%,d".format(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            summary.avgSleepThisWeek?.let {
                val hoursUnit = stringResource(R.string.unit_hours)
                Text(stringResource(R.string.weekly_summary_sleep, "%.1f".format(it)))
                summary.avgSleepLastWeek?.let { prev ->
                    Text(
                        stringResource(R.string.weekly_summary_prev_week, "${"%.1f".format(prev)} $hoursUnit"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            summary.avgScoreThisWeek?.let {
                Text(stringResource(R.string.weekly_summary_score, it.toInt()))
                summary.avgScoreLastWeek?.let { prev ->
                    Text(
                        stringResource(R.string.weekly_summary_prev_week, prev.toInt().toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { shareWeek(asPdf = false) }) {
                    Text(stringResource(R.string.share_as_image))
                }
                OutlinedButton(onClick = { shareWeek(asPdf = true) }) {
                    Text(stringResource(R.string.share_as_pdf))
                }
            }
        }
    }
}

/**
 * Samme prinsipp som WeeklySummaryCard, bare for hele måneden - sammenligner mot
 * forrige måned. Vises kun siste dag i måneden, siden det da er hele månedens data
 * som skal med i sammenligningen.
 */
@Composable
fun MonthlySummaryCard(all: List<HealthDay>) {
    val today = LocalDate.now()
    if (today != YearMonth.from(today).atEndOfMonth()) return

    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val stepsGoal = GoalPreferences.getStepsGoal(context)
    val summary = remember(all, sleepGoalMinutes, stepsGoal) {
        buildMonthlySummary(all, today, sleepGoalMinutes, stepsGoal)
    } ?: return

    val coroutineScope = rememberCoroutineScope()
    val monthStart = YearMonth.from(today).atDay(1)
    val monthDays = remember(all, today) {
        all.filter { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
            date != null && !date.isBefore(monthStart) && !date.isAfter(today)
        }
    }
    val periodLabel = "${monthStart.format(dateFormatter)} – ${today.format(dateFormatter)}"
    val summaryTitle = stringResource(R.string.monthly_summary_title)

    fun shareMonth(asPdf: Boolean) {
        coroutineScope.launch {
            val file = withContext(Dispatchers.IO) {
                if (asPdf) {
                    createSummaryPdfFile(context, monthDays, summaryTitle, periodLabel)
                } else {
                    createSummaryImageFile(context, monthDays, summaryTitle, periodLabel)
                }
            }
            shareFile(context, file, if (asPdf) "application/pdf" else "image/png")
        }
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.monthly_summary_title), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            Text(stringResource(R.string.monthly_summary_steps, "%,d".format(summary.stepsThisMonth)))
            summary.stepsLastMonth?.let {
                Text(
                    stringResource(R.string.monthly_summary_prev_month, "%,d".format(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            summary.avgSleepThisMonth?.let {
                val hoursUnit = stringResource(R.string.unit_hours)
                Text(stringResource(R.string.monthly_summary_sleep, "%.1f".format(it)))
                summary.avgSleepLastMonth?.let { prev ->
                    Text(
                        stringResource(R.string.monthly_summary_prev_month, "${"%.1f".format(prev)} $hoursUnit"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            summary.avgScoreThisMonth?.let {
                Text(stringResource(R.string.monthly_summary_score, it.toInt()))
                summary.avgScoreLastMonth?.let { prev ->
                    Text(
                        stringResource(R.string.monthly_summary_prev_month, prev.toInt().toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { shareMonth(asPdf = false) }) {
                    Text(stringResource(R.string.share_as_image))
                }
                OutlinedButton(onClick = { shareMonth(asPdf = true) }) {
                    Text(stringResource(R.string.share_as_pdf))
                }
            }
        }
    }
}

/**
 * Viser akkumulert avvik fra ditt personlige søvnmål de siste 14 dagene - positivt
 * betyr gjeld (sovet mindre enn målet i sum), negativt betyr overskudd. Skjules helt
 * hvis det ikke finnes søvndata i vinduet ennå.
 */
@Composable
fun SleepDebtCard(all: List<HealthDay>) {
    val context = LocalContext.current
    val debt = remember(all) { calculateSleepDebt(context, all) } ?: return

    val (labelRes, valuePrefix) = when {
        debt.netHours > 0.5 -> R.string.sleep_debt_title to "+"
        debt.netHours < -0.5 -> R.string.sleep_surplus_title to ""
        else -> R.string.sleep_balanced_title to ""
    }
    val hoursText = "%.1f".format(kotlin.math.abs(debt.netHours))

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(labelRes), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (kotlin.math.abs(debt.netHours) > 0.5) {
                Text(
                    stringResource(R.string.sleep_debt_value, "$valuePrefix$hoursText"),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.sleep_debt_description, "%.1f".format(debt.goalHours)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Pulsgraf for hele dagen så langt - hentet LIVE fra Health Connect hver gang kortet
 * vises (se HealthConnectManager.readIntradayHeartRate), aldri lagret i vår egen
 * database. Health Connect har allerede denne detaljerte historikken selv.
 */
@Composable
fun HeartRateChartCard(healthConnectManager: HealthConnectManager, refreshKey: Int = 0) {
    var samples by remember { mutableStateOf<List<Pair<Instant, Long>>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    // Nøkkel på refreshKey: lastes på nytt etter hver dra-ned-oppdatering, ikke bare
    // første gang kortet vises. Beholder forrige graf mens den nye hentes (ingen blinking).
    LaunchedEffect(refreshKey) {
        loadFailed = false
        try {
            samples = healthConnectManager.readIntradayHeartRate(LocalDate.now())
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.heart_rate_chart_title), fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(12.dp))

            val currentSamples = samples
            when {
                loadFailed -> Text(
                    stringResource(R.string.heart_rate_chart_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                currentSamples == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }

                currentSamples.isEmpty() -> Text(
                    stringResource(R.string.heart_rate_chart_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    val zone = ZoneId.systemDefault()
                    val points = currentSamples.map { (instant, bpm) ->
                        val time = instant.atZone(zone).toLocalTime()
                        (time.hour + time.minute / 60f) to bpm.toFloat()
                    }

                    IntradayHeartRateChart(points = points)

                    Spacer(Modifier.height(8.dp))

                    val bpmValues = points.map { it.second }
                    Text(
                        stringResource(
                            R.string.heart_rate_chart_summary,
                            bpmValues.average().toInt(),
                            bpmValues.min().toInt(),
                            bpmValues.max().toInt()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * X-akse: klokketime (0-24) som flyttall. Y-akse: bpm, auto-skalert til dagens spenn
 * med litt luft. Støtter trykk-og-hold (og dra langs kurven) for å lese av nøyaktig
 * klokkeslett og puls på et punkt - se pointerInput-blokken.
 */
@Composable
private fun IntradayHeartRateChart(points: List<Pair<Float, Float>>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val chartDescription = stringResource(R.string.heart_rate_chart_content_description)
    val sorted = remember(points) { points.sortedBy { it.first } }

    var touchedIndex by remember(sorted) { mutableStateOf<Int?>(null) }

    val minBpm = remember(sorted) { sorted.minOf { it.second } - 5f }
    val maxBpm = remember(sorted) { sorted.maxOf { it.second } + 5f }
    val bpmRange = (maxBpm - minBpm).coerceAtLeast(1f)

    fun nearestIndex(touchX: Float, widthPx: Float): Int {
        val targetHour = (touchX / widthPx).coerceIn(0f, 1f) * 24f
        return sorted.indices.minByOrNull { kotlin.math.abs(sorted[it].first - targetHour) } ?: 0
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = chartDescription }
                .pointerInput(sorted) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touchedIndex = nearestIndex(down.position.x, size.width.toFloat())
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null) {
                                if (change.pressed) {
                                    touchedIndex = nearestIndex(change.position.x, size.width.toFloat())
                                    change.consume()
                                }
                                pressed = change.pressed
                            } else {
                                pressed = false
                            }
                        }
                        touchedIndex = null
                    }
                }
        ) {
            // Loddrette rutenett-linjer hver 6. time (00, 06, 12, 18, 24), som et enkelt
            // visuelt holdepunkt for hvor på dagen man er.
            for (hour in 0..24 step 6) {
                val x = (hour / 24f) * size.width
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }

            val path = Path()
            sorted.forEachIndexed { index, (hour, bpm) ->
                val x = (hour / 24f) * size.width
                val y = size.height - ((bpm - minBpm) / bpmRange) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

            touchedIndex?.let { idx ->
                val (hour, bpm) = sorted[idx]
                val x = (hour / 24f) * size.width
                val y = size.height - ((bpm - minBpm) / bpmRange) * size.height
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                drawCircle(lineColor, radius = 6f, center = Offset(x, y))
            }
        }

        touchedIndex?.let { idx ->
            val (hour, bpm) = sorted[idx]
            val h = hour.toInt()
            val m = ((hour - h) * 60).toInt()
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                shape = RoundedCornerShape(8.dp),
                color = lineColor
            ) {
                Text(
                    text = "%02d:%02d · %d bpm".format(h, m, bpm.toInt()),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Y-aksetall øverst/nederst, i høyre hjørne for å ikke kollidere med
        // trykk-og-hold-boksen (som vises øverst til venstre).
        Text(
            text = "${maxBpm.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        )
        Text(
            text = "${minBpm.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        )
    }
}
