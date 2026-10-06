package no.healthlog.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import no.healthlog.app.data.HealthDay
import no.healthlog.app.data.habitTagSet
import no.healthlog.app.data.sleepFactorSet
import no.healthlog.app.data.manualOverrideSet
import no.healthlog.app.data.trainingSessionList
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", norwegianLocale)

private fun monthLabel(month: YearMonth): String =
    month.format(monthFormatter).replaceFirstChar { it.titlecase(norwegianLocale) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    days: List<HealthDay>,
    onSave: (String, ManualEntryInput) -> Unit,
    onDelete: (String) -> Unit,
    onResetField: (String, String) -> Unit
) {
    val sorted = days.sortedByDescending { it.date }

    val byDateForCalendar = remember(days) {
        days.mapNotNull { day ->
            runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
        }.toMap()
    }

    var selectedTagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTrainingTypes by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var selectedHabitFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val allHabitTags = remember(sorted) { sorted.flatMap { it.habitTagSet() }.distinct().sorted() }

    val filteredSorted = remember(sorted, selectedTagFilter, searchQuery, selectedTrainingTypes, selectedHabitFilter) {
        sorted.filter { day ->
            (selectedTagFilter == null || day.dayTag == selectedTagFilter) &&
                (searchQuery.isBlank() ||
                    day.morningNote?.contains(searchQuery, ignoreCase = true) == true ||
                    day.eveningNote?.contains(searchQuery, ignoreCase = true) == true ||
                    day.sleepReflection?.contains(searchQuery, ignoreCase = true) == true) &&
                (selectedTrainingTypes.isEmpty() || day.trainingType in selectedTrainingTypes) &&
                (selectedHabitFilter == null || selectedHabitFilter in day.habitTagSet())
        }
    }

    val groupedByMonth = remember(filteredSorted) {
        filteredSorted.groupBy { day ->
            runCatching { YearMonth.from(LocalDate.parse(day.date)) }.getOrNull()
        }
    }
    val sortedMonths = remember(groupedByMonth) {
        groupedByMonth.keys.filterNotNull().sortedDescending()
    }

    var expandedMonths by remember { mutableStateOf(setOf(YearMonth.now())) }

    var dayPendingDelete by remember {
        mutableStateOf<HealthDay?>(null)
    }

    var selectedDay by remember {
        mutableStateOf<HealthDay?>(null)
    }


    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(
            top = 28.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            var showDatePicker by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.DateRange, contentDescription = stringResource(R.string.action_jump_to_date))
                }
            }

            if (showDatePicker) {
                val pickerState = rememberDatePickerState()
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                selectedDay = byDateForCalendar[date] ?: HealthDay(date = date.toString())
                            }
                            showDatePicker = false
                        }) {
                            Text(stringResource(R.string.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                ) {
                    DatePicker(state = pickerState)
                }
            }

            Text(
                text = stringResource(R.string.history_entry_count, filteredSorted.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                },
                singleLine = true
            )

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTagFilter == null,
                    onClick = { selectedTagFilter = null },
                    label = { Text(stringResource(R.string.history_filter_all)) }
                )
                DayTags.all.forEach { tag ->
                    FilterChip(
                        selected = selectedTagFilter == tag,
                        onClick = { selectedTagFilter = if (selectedTagFilter == tag) null else tag },
                        label = { Text(dayTagLabel(tag)) }
                    )
                }
            }

            // Egne vane-merker - vises bare når minst ett merke er brukt.
            if (allHabitTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allHabitTags.forEach { tag ->
                        FilterChip(
                            selected = selectedHabitFilter == tag,
                            onClick = { selectedHabitFilter = if (selectedHabitFilter == tag) null else tag },
                            label = { Text(tag) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            var trainingFilterExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { trainingFilterExpanded = true }) {
                    Text(
                        if (selectedTrainingTypes.isEmpty()) {
                            stringResource(R.string.history_filter_training_button)
                        } else {
                            stringResource(R.string.history_filter_training_button_count, selectedTrainingTypes.size)
                        }
                    )
                }
                DropdownMenu(
                    expanded = trainingFilterExpanded,
                    onDismissRequest = { trainingFilterExpanded = false }
                ) {
                    TrainingType.all.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = type in selectedTrainingTypes,
                                        onCheckedChange = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(trainingTypeLabel(type))
                                }
                            },
                            onClick = {
                                selectedTrainingTypes = if (type in selectedTrainingTypes) {
                                    selectedTrainingTypes - type
                                } else {
                                    selectedTrainingTypes + type
                                }
                            }
                        )
                    }
                    if (selectedTrainingTypes.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_filter_training_clear)) },
                            onClick = { selectedTrainingTypes = emptySet() }
                        )
                    }
                }
            }
        }

        item {
            var showCalendar by rememberSaveable { mutableStateOf(false) }
            val rotation by animateFloatAsState(if (showCalendar) 180f else 0f, label = "calendar_chevron")

            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showCalendar = !showCalendar }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.history_calendar_toggle),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (showCalendar) stringResource(R.string.month_collapse) else stringResource(R.string.month_expand),
                        modifier = Modifier.rotate(rotation)
                    )
                }

                if (showCalendar) {
                    Spacer(Modifier.height(8.dp))
                    CalendarHeatMap(
                        days = days,
                        onDayClick = { date ->
                            selectedDay = byDateForCalendar[date] ?: HealthDay(date = date.toString())
                        }
                    )
                }
            }
        }

        if (filteredSorted.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = if (selectedTagFilter != null || selectedHabitFilter != null) {
                            stringResource(R.string.history_empty_filtered)
                        } else {
                            stringResource(R.string.history_empty)
                        },
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        }

        sortedMonths.forEach { month ->
            val monthDays = groupedByMonth[month].orEmpty()
            val isExpanded = month in expandedMonths

            item(key = "header-$month") {
                MonthHeader(
                    month = month,
                    count = monthDays.size,
                    expanded = isExpanded,
                    onToggle = {
                        expandedMonths =
                            if (isExpanded) expandedMonths - month else expandedMonths + month
                    }
                )
            }

            if (isExpanded) {
                items(
                    items = monthDays,
                    key = { day -> day.date }
                ) { day ->
                    HistoryDayCard(
                        day = day,
                        onEdit = { selectedDay = day },
                        onDelete = { dayPendingDelete = day }
                    )
                }
            }
        }
    }

    dayPendingDelete?.let { selectedDayForDelete ->
        DeleteConfirmDialog(
            onConfirm = {
                onDelete(selectedDayForDelete.date)
                dayPendingDelete = null
            },
            onDismiss = {
                dayPendingDelete = null
            }
        )
    }

    selectedDay?.let { day ->
        EditHistoryDialog(
            day = day,

            onDismiss = {
                selectedDay = null
            },

            onSave = { input ->
                onSave(day.date, input)

                selectedDay = null
            },

            onDelete = {
                onDelete(day.date)
                selectedDay = null
            },

            onResetField = { field ->
                onResetField(day.date, field)
            }
        )
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggle() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${monthLabel(month)} · $count",
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.month_collapse) else stringResource(R.string.month_expand),
            modifier = Modifier.rotate(rotation)
        )
    }
}

@Composable
private fun HistoryDayCard(
    day: HealthDay,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            val formattedDate = runCatching {
                LocalDate
                    .parse(day.date)
                    .format(dateFormatter)
            }.getOrDefault(day.date)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formattedDate,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    day.dayTag?.let { tag ->
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = { },
                            enabled = false,
                            label = { Text(dayTagLabel(tag), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    day.nutritionLevel?.let { level ->
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = { },
                            enabled = false,
                            label = { Text(nutritionLevelLabel(level), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row {

                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.action_edit))
                    }

                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            val dayHabitTags = day.habitTagSet()
            if (dayHabitTags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.habit_day_summary, dayHabitTags.joinToString(" · ")),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }

            HorizontalDivider()

            Spacer(Modifier.height(10.dp))

            val placeholder = stringResource(R.string.value_placeholder)

            val factorLabels = day.sleepFactorSet().map { sleepFactorLabel(it) }
            if (!day.sleepReflection.isNullOrBlank() || factorLabels.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.diary_sleep_prefix,
                        listOfNotNull(factorLabels.joinToString(", ").ifEmpty { null }, day.sleepReflection?.takeIf { it.isNotBlank() })
                            .joinToString(" – ")
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic
                )
                Spacer(Modifier.height(8.dp))
            }

            if (!day.morningNote.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.diary_morning_prefix, day.morningNote),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic
                )
                Spacer(Modifier.height(8.dp))
            }

            if (!day.eveningNote.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.diary_evening_prefix, day.eveningNote),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic
                )
                Spacer(Modifier.height(10.dp))
            }

            Text(
                text = stringResource(
                    R.string.metric_weight,
                    day.weightKg?.let { "%.1f kg".format(it) } ?: placeholder
                )
            )

            Text(
                text = stringResource(
                    R.string.metric_waist,
                    day.waistCm?.let { "%.1f cm".format(it) } ?: placeholder
                )
            )

            Text(
                text = stringResource(
                    R.string.metric_resting_hr,
                    day.restingHeartRate?.let { "$it bpm" } ?: placeholder
                )
            )

            Text(
                text = stringResource(
                    R.string.metric_hrv,
                    day.hrvMs?.let { "${it.toInt()} ms" } ?: placeholder
                )
            )

            Text(
                text = stringResource(
                    R.string.metric_sleep,
                    day.sleepMinutes?.let { "${it / 60} t ${it % 60} min" } ?: placeholder
                )
            )

            val sleepStageParts = listOfNotNull(
                day.deepSleepMinutes?.let { "${stringResource(R.string.sleep_stage_deep_short)} ${if (it >= 60) "${it / 60}t ${it % 60}m" else "${it}m"}" },
                day.lightSleepMinutes?.let { "${stringResource(R.string.sleep_stage_light_short)} ${if (it >= 60) "${it / 60}t ${it % 60}m" else "${it}m"}" },
                day.remSleepMinutes?.let { "${stringResource(R.string.sleep_stage_rem_short)} ${if (it >= 60) "${it / 60}t ${it % 60}m" else "${it}m"}" },
                day.sleepAwakeMinutes?.let { "${stringResource(R.string.sleep_stage_awake_short)} ${if (it >= 60) "${it / 60}t ${it % 60}m" else "${it}m"}" }
            )
            if (sleepStageParts.isNotEmpty()) {
                Text(
                    text = sleepStageParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(
                    R.string.metric_steps,
                    day.steps?.let { "%,d".format(it) } ?: placeholder
                )
            )

            val sessions = day.trainingSessionList()
            if (sessions.size > 1) {
                Text(
                    text = stringResource(R.string.metric_label_training),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                sessions.forEach { session ->
                    val intensitySuffix = session.intensity?.let { " (${trainingIntensityLabel(it)})" } ?: ""
                    Text("• ${session.minutes} min, ${trainingTypeLabel(session.type)}$intensitySuffix")
                }
            } else {
                Text(
                    text = stringResource(
                        R.string.metric_training,
                        day.trainingMinutes?.let { minutes ->
                            val type = day.trainingType?.let { ", ${trainingTypeLabel(it)}" } ?: ""
                            val intensity = day.trainingIntensity?.let { " (${trainingIntensityLabel(it)})" } ?: ""
                            "$minutes min$type$intensity"
                        } ?: placeholder
                    )
                )
            }

            Text(
                text = stringResource(
                    R.string.metric_body_battery,
                    if (day.bodyBatteryMax != null || day.bodyBatteryMin != null)
                        "${day.bodyBatteryMax ?: "–"} / ${day.bodyBatteryMin ?: "–"}"
                    else placeholder
                )
            )

            Text(text = stringResource(R.string.metric_stress, day.stress?.toString() ?: placeholder))

            Text(text = stringResource(R.string.metric_sleep_score, day.sleepScore?.toString() ?: placeholder))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHistoryDialog(
    day: HealthDay,
    onDismiss: () -> Unit,
    onSave: (ManualEntryInput) -> Unit,
    onDelete: () -> Unit,
    onResetField: (String) -> Unit
) {
    var morningNote by rememberSaveable(day.date) {
        mutableStateOf(day.morningNote ?: "")
    }

    var sleepReflection by rememberSaveable(day.date) {
        mutableStateOf(day.sleepReflection ?: "")
    }

    var sleepFactors by rememberSaveable(day.date) {
        mutableStateOf(day.sleepFactorSet().toList())
    }

    var dayTag by rememberSaveable(day.date) {
        mutableStateOf(day.dayTag)
    }

    var nutritionLevel by rememberSaveable(day.date) {
        mutableStateOf(day.nutritionLevel)
    }

    var habitTags by rememberSaveable(day.date) {
        mutableStateOf(day.habitTagSet().toList())
    }

    var eveningNote by rememberSaveable(day.date) {
        mutableStateOf(day.eveningNote ?: "")
    }

    var weight by rememberSaveable(day.date) {
        mutableStateOf(day.weightKg?.toString() ?: "")
    }

    var waist by rememberSaveable(day.date) {
        mutableStateOf(day.waistCm?.toString() ?: "")
    }

    var hrv by rememberSaveable(day.date) {
        mutableStateOf(day.hrvMs?.toString() ?: "")
    }

    var stress by rememberSaveable(day.date) {
        mutableStateOf(day.stress?.toString() ?: "")
    }

    var bodyBatteryMax by rememberSaveable(day.date) {
        mutableStateOf(day.bodyBatteryMax?.toString() ?: "")
    }

    var bodyBatteryMin by rememberSaveable(day.date) {
        mutableStateOf(day.bodyBatteryMin?.toString() ?: "")
    }

    var sleepScore by rememberSaveable(day.date) {
        mutableStateOf(day.sleepScore?.toString() ?: "")
    }

    // Normalt automatisk fra Health Connect - redigerbare her (kun i Historikk, ikke i
    // dagens raske registreringsdialog) for å kunne rette en feilregistrering. En
    // faktisk endring beskyttes mot å bli overskrevet ved neste synk, se
    // HealthViewModel.saveManual.
    var stepsField by rememberSaveable(day.date) {
        mutableStateOf(day.steps?.toString() ?: "")
    }

    var activeMinutesField by rememberSaveable(day.date) {
        mutableStateOf(day.activeMinutes?.toString() ?: "")
    }

    var sleepMinutesField by rememberSaveable(day.date) {
        mutableStateOf(day.sleepMinutes?.toString() ?: "")
    }

    var restingHeartRateField by rememberSaveable(day.date) {
        mutableStateOf(day.restingHeartRate?.toString() ?: "")
    }

    var avgHeartRateField by rememberSaveable(day.date) {
        mutableStateOf(day.avgHeartRate?.toString() ?: "")
    }

    var trainingMinutesField by rememberSaveable(day.date) {
        mutableStateOf(day.trainingMinutes?.toString() ?: "")
    }

    var trainingTypeField by rememberSaveable(day.date) {
        mutableStateOf(day.trainingType)
    }

    var trainingIntensityField by rememberSaveable(day.date) {
        mutableStateOf(day.trainingIntensity)
    }

    // Lokal kopi av beskyttelsesstatusen, oppdatert med en gang du trykker
    // "Tilbakestill" - venter ikke på at den ytre listen skal friskes opp fra
    // databasen, som ellers kunne gitt en litt treg/forsinket UI-respons.
    var currentOverrides by remember(day.date) { mutableStateOf(day.manualOverrideSet()) }

    val resetHint: @Composable (String) -> Unit = { field ->
        if (field in currentOverrides) {
            TextButton(
                onClick = {
                    currentOverrides = currentOverrides - field
                    onResetField(field)
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(stringResource(R.string.action_reset_to_health_connect), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    var showDeleteConfirm by rememberSaveable {
        mutableStateOf(false)
    }
    val formattedDate = runCatching {
        LocalDate
            .parse(day.date)
            .format(dateFormatter)
    }.getOrDefault(day.date)

    val weightValue = weight.trim().replace(',', '.').toDoubleOrNull()
    val waistValue = waist.trim().replace(',', '.').toDoubleOrNull()
    val hrvValue = hrv.trim().replace(',', '.').toDoubleOrNull()
    val stressValue = stress.trim().toIntOrNull()
    val bodyBatteryMaxValue = bodyBatteryMax.trim().toIntOrNull()
    val bodyBatteryMinValue = bodyBatteryMin.trim().toIntOrNull()
    val sleepScoreValue = sleepScore.trim().toIntOrNull()
    val stepsValue = stepsField.trim().toIntOrNull()
    val activeMinutesValue = activeMinutesField.trim().toIntOrNull()
    val sleepMinutesValue = sleepMinutesField.trim().toIntOrNull()
    val restingHeartRateValue = restingHeartRateField.trim().toIntOrNull()
    val avgHeartRateValue = avgHeartRateField.trim().toIntOrNull()
    val trainingMinutesValue = trainingMinutesField.trim().toIntOrNull()

    val weightIsValid = weight.isBlank() || (weightValue != null && weightValue in ValidationRanges.weightKg)
    val waistIsValid = waist.isBlank() || (waistValue != null && waistValue in ValidationRanges.waistCm)
    val hrvIsValid = hrv.isBlank() || (hrvValue != null && hrvValue in ValidationRanges.hrvMs)
    val stressIsValid = stress.isBlank() || (stressValue != null && stressValue in ValidationRanges.percentage)
    val bodyBatteryMaxIsValid = bodyBatteryMax.isBlank() || (bodyBatteryMaxValue != null && bodyBatteryMaxValue in ValidationRanges.percentage)
    val bodyBatteryMinIsValid = bodyBatteryMin.isBlank() || (bodyBatteryMinValue != null && bodyBatteryMinValue in ValidationRanges.percentage)
    val sleepScoreIsValid = sleepScore.isBlank() || (sleepScoreValue != null && sleepScoreValue in ValidationRanges.percentage)
    val stepsIsValid = stepsField.isBlank() || (stepsValue != null && stepsValue >= 0)
    val activeMinutesIsValid = activeMinutesField.isBlank() || (activeMinutesValue != null && activeMinutesValue >= 0)
    val sleepMinutesIsValid = sleepMinutesField.isBlank() || (sleepMinutesValue != null && sleepMinutesValue in 0..1440)
    val restingHeartRateIsValid = restingHeartRateField.isBlank() || (restingHeartRateValue != null && restingHeartRateValue in 20..220)
    val avgHeartRateIsValid = avgHeartRateField.isBlank() || (avgHeartRateValue != null && avgHeartRateValue in 20..220)
    val trainingMinutesIsValid = trainingMinutesField.isBlank() || (trainingMinutesValue != null && trainingMinutesValue >= 0)

    val formIsValid =
        weightIsValid && waistIsValid && hrvIsValid &&
            stressIsValid && bodyBatteryMaxIsValid && bodyBatteryMinIsValid && sleepScoreIsValid &&
            stepsIsValid && activeMinutesIsValid && sleepMinutesIsValid && restingHeartRateIsValid && avgHeartRateIsValid && trainingMinutesIsValid

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Column {
                Text(stringResource(R.string.edit_dialog_title))

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DayTags.all.forEach { tag ->
                        FilterChip(
                            selected = dayTag == tag,
                            onClick = { dayTag = if (dayTag == tag) null else tag },
                            label = { Text(dayTagLabel(tag)) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.nutrition_section_label_history),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NutritionLevel.all.forEach { level ->
                        FilterChip(
                            selected = nutritionLevel == level,
                            onClick = { nutritionLevel = if (nutritionLevel == level) null else level },
                            label = { Text(nutritionLevelLabel(level)) }
                        )
                    }
                }

                HabitTagSelector(selected = habitTags, onChange = { habitTags = it })

                OutlinedTextField(
                    value = sleepReflection,
                    onValueChange = { sleepReflection = it },
                    label = { Text(stringResource(R.string.label_sleep_reflection)) },
                    placeholder = { Text(stringResource(R.string.placeholder_sleep_reflection)) },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )

                SleepFactorSelector(selected = sleepFactors, onChange = { sleepFactors = it })

                OutlinedTextField(
                    value = morningNote,
                    onValueChange = { morningNote = it },
                    label = { Text(stringResource(R.string.label_morning_plan)) },
                    placeholder = { Text(stringResource(R.string.placeholder_morning_plan_past)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = weight,
                    onValueChange = {
                        weight = it
                    },
                    label = {
                        Text(stringResource(R.string.label_weight))
                    },
                    singleLine = true,
                    isError = !weightIsValid,
                    supportingText = {
                        if (!weightIsValid) {
                            Text(stringResource(R.string.error_weight_range))
                        }
                    }
                )
                resetHint("weightKg")

                OutlinedTextField(
                    value = waist,
                    onValueChange = {
                        waist = it
                    },
                    label = {
                        Text(stringResource(R.string.label_waist))
                    },
                    singleLine = true,
                    isError = !waistIsValid,
                    supportingText = {
                        if (!waistIsValid) {
                            Text(stringResource(R.string.error_waist_range))
                        }
                    }
                )

                OutlinedTextField(
                    value = sleepScore,
                    onValueChange = { sleepScore = it },
                    label = { Text(stringResource(R.string.label_sleep_score)) },
                    singleLine = true,
                    isError = !sleepScoreIsValid,
                    supportingText = {
                        if (!sleepScoreIsValid) {
                            Text(stringResource(R.string.error_percentage_range))
                        }
                    }
                )

                OutlinedTextField(
                    value = hrv,
                    onValueChange = { hrv = it },
                    label = { Text(stringResource(R.string.label_hrv)) },
                    singleLine = true,
                    isError = !hrvIsValid,
                    supportingText = {
                        if (!hrvIsValid) {
                            Text(stringResource(R.string.error_hrv_range))
                        }
                    }
                )

                OutlinedTextField(
                    value = bodyBatteryMax,
                    onValueChange = { bodyBatteryMax = it },
                    label = { Text(stringResource(R.string.label_body_battery_high)) },
                    singleLine = true,
                    isError = !bodyBatteryMaxIsValid,
                    supportingText = {
                        if (!bodyBatteryMaxIsValid) {
                            Text(stringResource(R.string.error_percentage_range))
                        }
                    }
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = eveningNote,
                    onValueChange = { eveningNote = it },
                    label = { Text(stringResource(R.string.label_evening_summary)) },
                    placeholder = { Text(stringResource(R.string.placeholder_evening_summary_past)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = stress,
                    onValueChange = { stress = it },
                    label = { Text(stringResource(R.string.label_stress)) },
                    singleLine = true,
                    isError = !stressIsValid,
                    supportingText = {
                        if (!stressIsValid) {
                            Text(stringResource(R.string.error_percentage_range))
                        }
                    }
                )

                OutlinedTextField(
                    value = bodyBatteryMin,
                    onValueChange = { bodyBatteryMin = it },
                    label = { Text(stringResource(R.string.label_body_battery_low)) },
                    singleLine = true,
                    isError = !bodyBatteryMinIsValid,
                    supportingText = {
                        if (!bodyBatteryMinIsValid) {
                            Text(stringResource(R.string.error_percentage_range))
                        }
                    }
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Text(
                    text = stringResource(R.string.edit_health_connect_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.edit_health_connect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = stepsField,
                    onValueChange = { stepsField = it },
                    label = { Text(stringResource(R.string.metric_label_steps)) },
                    singleLine = true,
                    isError = !stepsIsValid,
                    supportingText = {
                        if (!stepsIsValid) Text(stringResource(R.string.error_generic_range))
                    }
                )
                resetHint("steps")

                OutlinedTextField(
                    value = activeMinutesField,
                    onValueChange = { activeMinutesField = it },
                    label = { Text(stringResource(R.string.metric_label_active_minutes)) },
                    singleLine = true,
                    isError = !activeMinutesIsValid,
                    supportingText = {
                        if (!activeMinutesIsValid) Text(stringResource(R.string.error_generic_range))
                    }
                )
                resetHint("activeMinutes")

                OutlinedTextField(
                    value = sleepMinutesField,
                    onValueChange = { sleepMinutesField = it },
                    label = { Text(stringResource(R.string.label_sleep_minutes)) },
                    singleLine = true,
                    isError = !sleepMinutesIsValid,
                    supportingText = {
                        if (!sleepMinutesIsValid) Text(stringResource(R.string.error_generic_range))
                    }
                )
                resetHint("sleepMinutes")

                OutlinedTextField(
                    value = restingHeartRateField,
                    onValueChange = { restingHeartRateField = it },
                    label = { Text(stringResource(R.string.metric_label_resting_hr)) },
                    singleLine = true,
                    isError = !restingHeartRateIsValid,
                    supportingText = {
                        if (!restingHeartRateIsValid) Text(stringResource(R.string.error_generic_range))
                    }
                )
                resetHint("restingHeartRate")

                OutlinedTextField(
                    value = avgHeartRateField,
                    onValueChange = { avgHeartRateField = it },
                    label = { Text(stringResource(R.string.metric_label_avg_heart_rate)) },
                    singleLine = true,
                    isError = !avgHeartRateIsValid,
                    supportingText = {
                        if (!avgHeartRateIsValid) Text(stringResource(R.string.error_generic_range))
                    }
                )
                resetHint("avgHeartRate")

                OutlinedTextField(
                    value = trainingMinutesField,
                    onValueChange = { trainingMinutesField = it },
                    label = { Text(stringResource(R.string.metric_label_training)) },
                    singleLine = true,
                    isError = !trainingMinutesIsValid,
                    supportingText = {
                        if (!trainingMinutesIsValid) Text(stringResource(R.string.error_generic_range))
                    }
                )
                resetHint("trainingMinutes")

                var trainingTypeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = trainingTypeExpanded,
                    onExpandedChange = { trainingTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = trainingTypeField?.let { trainingTypeLabel(it) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_training_type)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = trainingTypeExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = trainingTypeExpanded,
                        onDismissRequest = { trainingTypeExpanded = false }
                    ) {
                        TrainingType.all.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(trainingTypeLabel(type)) },
                                onClick = {
                                    trainingTypeField = type
                                    trainingTypeExpanded = false
                                }
                            )
                        }
                    }
                }
                resetHint("trainingType")

                Text(stringResource(R.string.label_training_intensity), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TrainingIntensity.LIGHT, TrainingIntensity.MODERATE, TrainingIntensity.VIGOROUS).forEach { level ->
                        FilterChip(
                            selected = trainingIntensityField == level,
                            onClick = {
                                trainingIntensityField = if (trainingIntensityField == level) null else level
                            },
                            label = { Text(trainingIntensityLabel(level)) }
                        )
                    }
                }
                resetHint("trainingIntensity")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ManualEntryInput(
                            weightKg = weightValue,
                            waistCm = waistValue,
                            hrvMs = hrvValue,
                            stress = stressValue,
                            bodyBatteryMax = bodyBatteryMaxValue,
                            bodyBatteryMin = bodyBatteryMinValue,
                            sleepScore = sleepScoreValue,
                            morningNote = morningNote.trim().ifBlank { null },
                            eveningNote = eveningNote.trim().ifBlank { null },
                            sleepReflection = sleepReflection.trim().ifBlank { null },
                            sleepFactors = sleepFactors,
                            dayTag = dayTag,
                            nutritionLevel = nutritionLevel,
                            habitTags = habitTags,
                            steps = stepsValue,
                            activeMinutes = activeMinutesValue,
                            sleepMinutes = sleepMinutesValue,
                            restingHeartRate = restingHeartRateValue,
                            avgHeartRate = avgHeartRateValue,
                            trainingMinutes = trainingMinutesValue,
                            trainingType = trainingTypeField,
                            trainingIntensity = trainingIntensityField
                        )
                    )
                },
                enabled = formIsValid
            ) {
                Text(stringResource(R.string.action_save_changes))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        showDeleteConfirm = true
                    }
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TextButton(
                    onClick = onDismiss
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },

            onDismiss = {
                showDeleteConfirm = false
            }
        )
    }
}
