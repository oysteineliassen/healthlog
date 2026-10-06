package no.healthlog.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import no.healthlog.app.billing.BillingManager
import no.healthlog.app.garmin.HealthConnectManager
import no.healthlog.app.data.HealthDay
import no.healthlog.app.data.habitTagSet
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthLogApp(
    vm: HealthViewModel,
    billingManager: BillingManager,
    healthConnectManager: HealthConnectManager,
    openEntryOnLaunch: Boolean = false,
    onEntryDialogConsumed: () -> Unit = {}
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showEntry by rememberSaveable { mutableStateOf(false) }
    val days by vm.days.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Widgetens "+"-knapp åpner appen med et flagg om at registrerings-dialogen skal
    // vises med det samme - forbrukes umiddelbart så det ikke trigger på nytt ved en
    // vanlig konfigurasjonsendring (f.eks. skjermrotasjon) senere i samme økt.
    LaunchedEffect(openEntryOnLaunch) {
        if (openEntryOnLaunch) {
            tab = 0
            showEntry = true
            onEntryDialogConsumed()
        }
    }

    // VIKTIG: days.firstOrNull() gir bare den NYESTE eksisterende raden - hvis dagens
    // dato ennå ikke har noen rad, er det gårsdagens data, ikke "dagens" (tom). Må
    // derfor matche eksplisitt på dagens datostreng, ellers forhåndsutfylles
    // Registrer-dialogen (inkl. dagbok) med gårsdagens verdier på en ny dag.
    val todayDateString = LocalDate.now().toString()
    val todayEntry = days.firstOrNull { it.date == todayDateString }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    tab == 0,
                    { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text(stringResource(R.string.nav_today)) })
                NavigationBarItem(
                    tab == 1,
                    { tab = 1 },
                    icon = { Icon(Icons.Default.Insights, null) },
                    label = { Text(stringResource(R.string.nav_trends)) }
                )

                NavigationBarItem(
                    tab == 2,
                    { tab = 2 },
                    icon = { Icon(Icons.Default.Favorite, null) },
                    label = { Text(stringResource(R.string.nav_history)) }
                )

                NavigationBarItem(
                    tab == 3,
                    { tab = 3 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )

                NavigationBarItem(
                    tab == 4,
                    { tab = 4 },
                    icon = { Icon(Icons.Default.EmojiEvents, null) },
                    label = { Text(stringResource(R.string.nav_records)) }
                )
            }
        },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(onClick = { showEntry = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.fab_register_description))
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            val tabContent: @Composable () -> Unit = { when (tab) {
                0 -> TodayScreen(todayEntry, days, billingManager, healthConnectManager, refreshKey = vm.refreshCount, fastingPeriods = vm.fastingPeriods)
                1 -> TrendsScreen(days, billingManager)
                2 -> HistoryScreen(
                    days = days,

                    onSave = { date, input ->
                        vm.saveManual(date, input)
                    },

                    onDelete = { date ->
                        vm.deleteDay(date)
                    },

                    onResetField = { date, field ->
                        vm.resetFieldToHealthConnect(date, field)
                    }
                )
                3 -> SettingsScreen(vm, billingManager)
                4 -> RecordsScreen(days, billingManager)
            } }

            // Dra ned for å hente ferske data fra Health Connect - på alle fanene som
            // viser data, men ikke Innstillinger (der gir det ingen mening).
            if (tab != 3) {
                PullToRefreshBox(
                    isRefreshing = vm.isRefreshing,
                    onRefresh = { vm.refreshFromHealthConnect() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    tabContent()
                }
            } else {
                tabContent()
            }
        }
    }
    if (showEntry) EntryDialog(
        initial = todayEntry,

        onDismiss = {
            showEntry = false
        },

        onSave = { input ->

            vm.saveManual(LocalDate.now().toString(), input)

            showEntry = false
        },

        onDelete = {

            vm.deleteDay(
                LocalDate.now().toString()
            )

            showEntry = false
        }
    )
    val undoLabel = stringResource(R.string.action_undo)
    vm.message?.let {
        LaunchedEffect(it) {
            val deletedDay = vm.lastDeletedDay
            val result = snackbarHostState.showSnackbar(
                message = it,
                actionLabel = if (deletedDay != null) undoLabel else null,
                duration = if (deletedDay != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (deletedDay != null) {
                if (result == SnackbarResult.ActionPerformed) {
                    vm.undoDelete()
                } else {
                    vm.clearLastDeleted()
                }
            }
            vm.clearMessage()
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text(stringResource(R.string.delete_confirm_title))
        },

        text = {
            Text(
                stringResource(R.string.delete_confirm_message)
            )
        },

        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },

        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun EntryDialog(
    initial: HealthDay?,
    onDismiss: () -> Unit,
    onSave: (ManualEntryInput) -> Unit,
    onDelete: () -> Unit
) {
    // Nøkkelen initial?.date: åpnes dialogen fra et varsel mens appen starter, er
    // dagens rad ikke lest fra databasen ennå (initial = null). Når den kommer, byttes
    // nøkkelen fra null til datoen og feltene fylles ut på nytt - ellers sto dialogen
    // tom, og Lagre kunne slette notater du allerede hadde skrevet i dag.
    var morningNote by rememberSaveable(initial?.date) { mutableStateOf(initial?.morningNote ?: "") }
    var dayTag by rememberSaveable(initial?.date) { mutableStateOf(initial?.dayTag) }
    var nutritionLevel by rememberSaveable(initial?.date) { mutableStateOf(initial?.nutritionLevel) }
    var habitTags by rememberSaveable(initial?.date) { mutableStateOf(initial.habitTagSet().toList()) }
    var eveningNote by rememberSaveable(initial?.date) { mutableStateOf(initial?.eveningNote ?: "") }
    var sleepReflection by rememberSaveable(initial?.date) { mutableStateOf(initial?.sleepReflection ?: "") }
    var weight by rememberSaveable(initial?.date) { mutableStateOf(initial?.weightKg?.toString() ?: "") }
    var waist by rememberSaveable(initial?.date) { mutableStateOf(initial?.waistCm?.toString() ?: "") }
    var hrv by rememberSaveable(initial?.date) { mutableStateOf(initial?.hrvMs?.toString() ?: "") }
    var stress by rememberSaveable(initial?.date) { mutableStateOf(initial?.stress?.toString() ?: "") }
    var bodyBatteryMax by rememberSaveable(initial?.date) { mutableStateOf(initial?.bodyBatteryMax?.toString() ?: "") }
    var bodyBatteryMin by rememberSaveable(initial?.date) { mutableStateOf(initial?.bodyBatteryMin?.toString() ?: "") }
    var sleepScore by rememberSaveable(initial?.date) { mutableStateOf(initial?.sleepScore?.toString() ?: "") }
    var showDeleteConfirm by rememberSaveable(initial?.date) {
        mutableStateOf(false)
    }

    val weightValue = weight.trim().replace(',', '.').toDoubleOrNull()
    val waistValue = waist.trim().replace(',', '.').toDoubleOrNull()
    val hrvValue = hrv.trim().replace(',', '.').toDoubleOrNull()
    val stressValue = stress.trim().toIntOrNull()
    val bodyBatteryMaxValue = bodyBatteryMax.trim().toIntOrNull()
    val bodyBatteryMinValue = bodyBatteryMin.trim().toIntOrNull()
    val sleepScoreValue = sleepScore.trim().toIntOrNull()

    val weightIsValid = weight.isBlank() || (weightValue != null && weightValue in ValidationRanges.weightKg)
    val waistIsValid = waist.isBlank() || (waistValue != null && waistValue in ValidationRanges.waistCm)
    val hrvIsValid = hrv.isBlank() || (hrvValue != null && hrvValue in ValidationRanges.hrvMs)
    val stressIsValid = stress.isBlank() || (stressValue != null && stressValue in ValidationRanges.percentage)
    val bodyBatteryMaxIsValid = bodyBatteryMax.isBlank() || (bodyBatteryMaxValue != null && bodyBatteryMaxValue in ValidationRanges.percentage)
    val bodyBatteryMinIsValid = bodyBatteryMin.isBlank() || (bodyBatteryMinValue != null && bodyBatteryMinValue in ValidationRanges.percentage)
    val sleepScoreIsValid = sleepScore.isBlank() || (sleepScoreValue != null && sleepScoreValue in ValidationRanges.percentage)

    val formIsValid =
        weightIsValid && waistIsValid && hrvIsValid &&
            stressIsValid && bodyBatteryMaxIsValid && bodyBatteryMinIsValid && sleepScoreIsValid

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.entry_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    text = stringResource(R.string.nutrition_section_label),
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

                OutlinedTextField(
                    value = morningNote,
                    onValueChange = { morningNote = it },
                    label = { Text(stringResource(R.string.label_morning_plan)) },
                    placeholder = { Text(stringResource(R.string.placeholder_morning_plan)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    weight,
                    { weight = it },
                    label = { Text(stringResource(R.string.label_weight)) },
                    singleLine = true,
                    isError = !weightIsValid,
                    supportingText = {
                        if (!weightIsValid) Text(stringResource(R.string.error_weight_range))
                    }
                )
                OutlinedTextField(
                    waist,
                    { waist = it },
                    label = { Text(stringResource(R.string.label_waist)) },
                    singleLine = true,
                    isError = !waistIsValid,
                    supportingText = {
                        if (!waistIsValid) Text(stringResource(R.string.error_waist_range))
                    }
                )
                OutlinedTextField(
                    sleepScore,
                    { sleepScore = it },
                    label = { Text(stringResource(R.string.label_sleep_score)) },
                    singleLine = true,
                    isError = !sleepScoreIsValid,
                    supportingText = {
                        if (!sleepScoreIsValid) Text(stringResource(R.string.error_percentage_range))
                    }
                )
                OutlinedTextField(
                    hrv,
                    { hrv = it },
                    label = { Text(stringResource(R.string.label_hrv)) },
                    singleLine = true,
                    isError = !hrvIsValid,
                    supportingText = {
                        if (!hrvIsValid) Text(stringResource(R.string.error_hrv_range))
                    }
                )
                OutlinedTextField(
                    bodyBatteryMax,
                    { bodyBatteryMax = it },
                    label = { Text(stringResource(R.string.label_body_battery_high)) },
                    singleLine = true,
                    isError = !bodyBatteryMaxIsValid,
                    supportingText = {
                        if (!bodyBatteryMaxIsValid) Text(stringResource(R.string.error_percentage_range))
                    }
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = eveningNote,
                    onValueChange = { eveningNote = it },
                    label = { Text(stringResource(R.string.label_evening_summary)) },
                    placeholder = { Text(stringResource(R.string.placeholder_evening_summary)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    stress,
                    { stress = it },
                    label = { Text(stringResource(R.string.label_stress)) },
                    singleLine = true,
                    isError = !stressIsValid,
                    supportingText = {
                        if (!stressIsValid) Text(stringResource(R.string.error_percentage_range))
                    }
                )
                OutlinedTextField(
                    bodyBatteryMin,
                    { bodyBatteryMin = it },
                    label = { Text(stringResource(R.string.label_body_battery_low)) },
                    singleLine = true,
                    isError = !bodyBatteryMinIsValid,
                    supportingText = {
                        if (!bodyBatteryMinIsValid) Text(stringResource(R.string.error_percentage_range))
                    }
                )
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
                            dayTag = dayTag,
                            nutritionLevel = nutritionLevel,
                            habitTags = habitTags
                        )
                    )
                },
                enabled = formIsValid
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
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
