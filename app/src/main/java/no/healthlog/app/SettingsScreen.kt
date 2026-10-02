package no.healthlog.app

import no.healthlog.app.coach.AiCoachPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import no.healthlog.app.billing.BillingManager
import no.healthlog.app.backup.BackupPreferences
import no.healthlog.app.backup.BackupSyncScheduler
import no.healthlog.app.backup.notifyIfBackupRepeatedlyFailing
import no.healthlog.app.garmin.HealthConnectManager
import no.healthlog.app.garmin.HealthConnectSyncScheduler
import no.healthlog.app.reminder.ReminderPreferences
import no.healthlog.app.reminder.ReminderScheduler
import no.healthlog.app.reminder.ReminderType
import no.healthlog.app.reminder.WeeklySummaryPreferences
import no.healthlog.app.reminder.WeeklySummaryScheduler
import java.time.LocalDate
import java.time.LocalTime

private const val TAG = "HealthConnectDebug"

/** Fast navn - filen døpes ikke om etter opprettelse (se BackupSyncWorker for hvorfor). */
private const val AUTO_BACKUP_FILE_NAME = "healthlog-auto-backup.json"

private val backupTimeFormatter: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d. MMM HH:mm", java.util.Locale.forLanguageTag("nb-NO"))

private fun formatBackupTime(timeMillis: Long): String =
    java.time.Instant.ofEpochMilli(timeMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(backupTimeFormatter)

private fun displayNameFor(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: HealthViewModel, billingManager: BillingManager) {
    val context = LocalContext.current
    val healthConnectManager = remember { HealthConnectManager(context) }
    val coroutineScope = rememberCoroutineScope()
    val isAutoSyncOn by HealthConnectSyncScheduler.isScheduled(context).collectAsState(initial = false)

    // dumpsys viste at disse ligger under "runtime permissions" på denne telefonen –
    // altså vanlige Android-tillatelser (samme mekanisme som kamera/posisjon), siden Health
    // Connect er del av selve plattformen fra Android 14. Da er standard
    // RequestMultiplePermissions mer pålitelig enn Health Connect-bibliotekets egen
    // PermissionController-kontrakt, som er ment for den eldre frittstående app-varianten.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.d(TAG, "Permission-callback (standard): $result")
        val allGranted = healthConnectManager.permissions.all { result[it] == true }
        if (allGranted) {
            coroutineScope.launch {
                val snapshot = healthConnectManager.readDay(LocalDate.now())
                Log.d(TAG, "Lest snapshot: $snapshot")
                vm.syncFromHealthConnect(LocalDate.now().toString(), snapshot)
            }
        } else {
            vm.showMessage(context.getString(R.string.msg_health_connect_access_denied))
        }
    }

    // Egen launcher for bakgrunnstillatelsen – bes om separat fra de fire grunnleggende,
    // kun når brukeren faktisk skrur på automatisk synk.
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.d(TAG, "Bakgrunnstillatelse-callback: $result")
        if (result[healthConnectManager.backgroundPermission] == true) {
            HealthConnectSyncScheduler.schedule(context)
            vm.showMessage(context.getString(R.string.msg_auto_sync_on))
        } else {
            vm.showMessage(context.getString(R.string.msg_background_access_denied))
        }
    }

    fun syncWithHealthConnect() {
        val status = healthConnectManager.sdkStatus()
        Log.d(TAG, "SDK-status: $status")
        if (!healthConnectManager.isAvailable()) {
            vm.showMessage(context.getString(R.string.msg_health_connect_unavailable_status, status))
            return
        }
        coroutineScope.launch {
            try {
                val already = healthConnectManager.hasAllPermissions()
                Log.d(TAG, "Har allerede alle tillatelser: $already")
                if (already && !healthConnectManager.hasAllOptionalPermissions() && healthConnectManager.shouldAskForOptionalPermissionsOnce()) {
                    // Eksisterende bruker som aldri er spurt om de valgfrie (HRV, kroppsfett) - spør én gang. Callbacken
                    // over synker som vanlig etterpå, uansett om HRV godtas eller ikke.
                    permissionLauncher.launch(healthConnectManager.permissionsToRequest.toTypedArray())
                } else if (already) {
                    val snapshot = healthConnectManager.readDay(LocalDate.now())
                    Log.d(TAG, "Lest snapshot: $snapshot")
                    vm.syncFromHealthConnect(LocalDate.now().toString(), snapshot)
                } else {
                    permissionLauncher.launch(healthConnectManager.permissionsToRequest.toTypedArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Feil mot Health Connect", e)
                vm.showMessage(context.getString(R.string.msg_health_connect_error, e.message))
            }
        }
    }

    fun onAutoSyncToggle(enabled: Boolean) {
        if (!enabled) {
            HealthConnectSyncScheduler.cancel(context)
            vm.showMessage(context.getString(R.string.msg_auto_sync_off))
            return
        }
        if (!healthConnectManager.isAvailable()) {
            vm.showMessage(context.getString(R.string.msg_health_connect_unavailable))
            return
        }
        if (!healthConnectManager.isBackgroundReadFeatureAvailable()) {
            vm.showMessage(context.getString(R.string.msg_background_sync_unsupported))
            return
        }
        coroutineScope.launch {
            if (!healthConnectManager.hasAllPermissions()) {
                vm.showMessage(context.getString(R.string.msg_sync_today_first_for_auto))
                return@launch
            }
            if (healthConnectManager.hasBackgroundPermission()) {
                HealthConnectSyncScheduler.schedule(context)
                vm.showMessage(context.getString(R.string.msg_auto_sync_on))
            } else {
                backgroundPermissionLauncher.launch(arrayOf(healthConnectManager.backgroundPermission))
            }
        }
    }

    val isMorningReminderOn by ReminderScheduler.isScheduled(context, ReminderType.MORNING).collectAsState(initial = false)
    val isEveningReminderOn by ReminderScheduler.isScheduled(context, ReminderType.EVENING).collectAsState(initial = false)

    var pendingPermissionType by remember { mutableStateOf<ReminderType?>(null) }

    val isWeeklySummaryOn by WeeklySummaryScheduler.isScheduled(context).collectAsState(initial = false)

    fun enableWeeklySummary() {
        WeeklySummaryPreferences.setEnabled(context, true)
        WeeklySummaryScheduler.schedule(context)
        vm.showMessage(context.getString(R.string.msg_reminder_on))
    }

    val weeklyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableWeeklySummary() else vm.showMessage(context.getString(R.string.msg_notification_permission_denied))
    }

    fun onWeeklySummaryToggle(enabled: Boolean) {
        if (!enabled) {
            WeeklySummaryPreferences.setEnabled(context, false)
            WeeklySummaryScheduler.cancel(context)
            vm.showMessage(context.getString(R.string.msg_reminder_off))
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) weeklyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else enableWeeklySummary()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val type = pendingPermissionType
        pendingPermissionType = null
        if (type != null) {
            if (granted) {
                ReminderPreferences.setEnabled(context, type, true)
                ReminderScheduler.schedule(context, type)
                vm.showMessage(context.getString(R.string.msg_reminder_on))
            } else {
                vm.showMessage(context.getString(R.string.msg_notification_permission_denied))
            }
        }
    }

    fun onReminderToggle(type: ReminderType, enabled: Boolean) {
        if (!enabled) {
            ReminderPreferences.setEnabled(context, type, false)
            ReminderScheduler.cancel(context, type)
            vm.showMessage(context.getString(R.string.msg_reminder_off))
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingPermissionType = type
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReminderPreferences.setEnabled(context, type, true)
            ReminderScheduler.schedule(context, type)
            vm.showMessage(context.getString(R.string.msg_reminder_on))
        }
    }

    fun backfillDays(days: Int) {
        if (!healthConnectManager.isAvailable()) {
            vm.showMessage(context.getString(R.string.msg_health_connect_unavailable))
            return
        }
        coroutineScope.launch {
            if (!healthConnectManager.hasAllPermissions()) {
                vm.showMessage(context.getString(R.string.msg_sync_today_first_for_history))
                permissionLauncher.launch(healthConnectManager.permissionsToRequest.toTypedArray())
                return@launch
            }
            val end = LocalDate.now()
            val start = end.minusDays((days - 1).toLong())
            val snapshots = healthConnectManager.readRange(start, end)
            Log.d(TAG, "Historikk lest: ${snapshots.size} dager")
            vm.syncRangeFromHealthConnect(snapshots)
        }
    }

    val isAutoBackupOn by BackupSyncScheduler.isScheduled(context).collectAsState(initial = false)
    var autoBackupUri by remember { mutableStateOf(BackupPreferences.get(context)) }
    val autoBackupFileName = autoBackupUri?.let { displayNameFor(context, it) }
    // Siste feilårsak, lagret av BackupSyncWorker/notifyIfBackupRepeatedlyFailing - vises
    // under bryteren slik at du ser HVORFOR den feilet uten å måtte fange logcat i akkurat
    // det (uforutsigbare) sekundet en bakgrunnsjobb kjører.
    var lastBackupError by remember { mutableStateOf(BackupPreferences.getLastError(context)) }
    var lastBackupSuccess by remember { mutableStateOf(BackupPreferences.getLastSuccessTime(context)) }
    val hasBackupWriteAccess = remember(autoBackupUri) { BackupPreferences.hasWriteAccess(context) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(vm.exportBackupJson().toByteArray())
                }
                vm.showMessage(context.getString(R.string.msg_backup_saved))
            } catch (e: Exception) {
                Log.e(TAG, "Feil ved eksport av sikkerhetskopi", e)
                vm.showMessage(context.getString(R.string.msg_backup_save_failed, e.message))
                return@rememberLauncherForActivityResult
            }

            // Fornyer også innholdet i auto-backup-filen. Egen try/catch
            // med bevisst stille feilhåndtering: dette er en sekundær bekvemmelighet, og
            // skal ALDRI kunne overskygge at sikkerhetskopien brukeren faktisk ba om over
            // allerede ble lagret korrekt. Noen leverandører (bl.a. Google Drive) er kjent
            // for ikke alltid å holde på skrivetilgangen pålitelig over tid selv med
            // takePersistableUriPermission - dette er da forventet og ufarlig å se i loggen.
            try {
                val currentAutoBackupUri = BackupPreferences.get(context)
                if (currentAutoBackupUri != null) {
                    context.contentResolver.openOutputStream(currentAutoBackupUri, "wt")?.use { stream ->
                        stream.write(vm.exportBackupJson().toByteArray())
                    }
                    BackupPreferences.recordSuccess(context)
                    lastBackupError = null
                    lastBackupSuccess = BackupPreferences.getLastSuccessTime(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kunne ikke fornye auto-backup-filen (skriving lyktes uansett)", e)
                notifyIfBackupRepeatedlyFailing(context, "${e.javaClass.simpleName}: ${e.message ?: "ingen detaljer"}")
                lastBackupError = BackupPreferences.getLastError(context)
            }
        }
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(vm.exportCsv().toByteArray())
                }
                vm.showMessage(context.getString(R.string.msg_csv_saved))
            } catch (e: Exception) {
                Log.e(TAG, "Feil ved CSV-eksport", e)
                vm.showMessage(context.getString(R.string.msg_csv_save_failed, e.message))
            }
        }
    }

    var pendingImportJson by remember { mutableStateOf<String?>(null) }

    var aiCoachEnabled by remember { mutableStateOf(AiCoachPreferences.isEnabled(context)) }

    val garminImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.importGarminFiles(uris)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                if (text != null) {
                    pendingImportJson = text
                } else {
                    vm.showMessage(context.getString(R.string.msg_file_empty))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Feil ved lesing av sikkerhetskopi", e)
                vm.showMessage(context.getString(R.string.msg_file_read_failed, e.message))
            }
        }
    }

    val autoBackupSetupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(vm.exportBackupJson().toByteArray())
                }
                BackupPreferences.set(context, uri)
                BackupPreferences.recordSuccess(context) // fersk plassering - nullstill gammel feilhistorikk
                lastBackupError = null
                lastBackupSuccess = BackupPreferences.getLastSuccessTime(context)
                autoBackupUri = uri
                BackupSyncScheduler.schedule(context)
                vm.showMessage(context.getString(R.string.msg_auto_backup_on))
            } catch (e: Exception) {
                Log.e(TAG, "Kunne ikke sette opp automatisk backup", e)
                vm.showMessage(context.getString(R.string.msg_auto_backup_setup_failed, e.message))
            }
        }
    }

    fun onAutoBackupToggle(enabled: Boolean) {
        if (!enabled) {
            BackupSyncScheduler.cancel(context)
            vm.showMessage(context.getString(R.string.msg_auto_backup_off))
            return
        }
        val existingUri = autoBackupUri
        if (existingUri != null) {
            BackupSyncScheduler.schedule(context)
            vm.showMessage(context.getString(R.string.msg_auto_backup_on))
        } else {
            autoBackupSetupLauncher.launch(AUTO_BACKUP_FILE_NAME)
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_garmin_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(stringResource(R.string.settings_garmin_description))

                    Spacer(Modifier.height(14.dp))

                    Button(onClick = { syncWithHealthConnect() }) {
                        Text(stringResource(R.string.action_sync_today))
                    }

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider()

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.label_auto_sync), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.desc_auto_sync),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isAutoSyncOn, onCheckedChange = { onAutoSyncToggle(it) })
                    }

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider()

                    Spacer(Modifier.height(16.dp))

                    Text(stringResource(R.string.label_fetch_history), fontWeight = FontWeight.SemiBold)

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { backfillDays(7) }) {
                            Text(stringResource(R.string.action_last_7_days))
                        }
                        OutlinedButton(onClick = { backfillDays(30) }) {
                            Text(stringResource(R.string.action_last_30_days))
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_goals_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        stringResource(R.string.settings_goals_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    var stepsGoalText by remember { mutableStateOf(GoalPreferences.getStepsGoal(context).toString()) }
                    var trainingGoalText by remember { mutableStateOf(GoalPreferences.getTrainingGoalMinutes(context).toString()) }
                    var sleepGoalHoursText by remember {
                        mutableStateOf((GoalPreferences.getSleepGoalMinutes(context) / 60).toString())
                    }
                    var weeklyActivityGoalText by remember {
                        mutableStateOf(GoalPreferences.getWeeklyActivityGoal(context).toString())
                    }
                    var weightGoalText by remember {
                        mutableStateOf(GoalPreferences.getWeightGoalKg(context)?.let { "%.1f".format(it) } ?: "")
                    }
                    var maxHeartRateText by remember {
                        mutableStateOf(GoalPreferences.getMaxHeartRate(context)?.toString() ?: "")
                    }

                    OutlinedTextField(
                        value = stepsGoalText,
                        onValueChange = { stepsGoalText = it },
                        label = { Text(stringResource(R.string.settings_goals_steps_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = trainingGoalText,
                        onValueChange = { trainingGoalText = it },
                        label = { Text(stringResource(R.string.settings_goals_training_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = sleepGoalHoursText,
                        onValueChange = { sleepGoalHoursText = it },
                        label = { Text(stringResource(R.string.settings_goals_sleep_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = weeklyActivityGoalText,
                        onValueChange = { weeklyActivityGoalText = it },
                        label = { Text(stringResource(R.string.settings_goals_weekly_activity_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = weightGoalText,
                        onValueChange = { weightGoalText = it },
                        label = { Text(stringResource(R.string.settings_goals_weight_label)) },
                        supportingText = { Text(stringResource(R.string.settings_goals_weight_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = maxHeartRateText,
                        onValueChange = { maxHeartRateText = it },
                        label = { Text(stringResource(R.string.settings_goals_max_hr_label)) },
                        supportingText = { Text(stringResource(R.string.settings_goals_max_hr_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    Button(onClick = {
                        stepsGoalText.trim().toIntOrNull()?.let { GoalPreferences.setStepsGoal(context, it) }
                        trainingGoalText.trim().toIntOrNull()?.let { GoalPreferences.setTrainingGoalMinutes(context, it) }
                        sleepGoalHoursText.trim().toIntOrNull()?.let { GoalPreferences.setSleepGoalMinutes(context, it * 60) }
                        weeklyActivityGoalText.trim().toIntOrNull()?.takeIf { it > 0 }?.let { GoalPreferences.setWeeklyActivityGoal(context, it) }
                        // Tomt felt fjerner vektmålet; ugyldige tall ignoreres som for de andre målene.
                        val weightText = weightGoalText.trim().replace(',', '.')
                        if (weightText.isEmpty()) {
                            GoalPreferences.setWeightGoalKg(context, null)
                        } else {
                            weightText.toDoubleOrNull()?.takeIf { it in ValidationRanges.weightKg }?.let { GoalPreferences.setWeightGoalKg(context, it) }
                        }
                        // Makspuls: tomt fjerner den (standard brukes), urimelige tall ignoreres.
                        val maxHr = maxHeartRateText.trim()
                        if (maxHr.isEmpty()) {
                            GoalPreferences.setMaxHeartRate(context, null)
                        } else {
                            maxHr.toIntOrNull()?.takeIf { it in 120..230 }?.let { GoalPreferences.setMaxHeartRate(context, it) }
                        }
                        vm.showMessage(context.getString(R.string.msg_goals_saved))
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_appearance_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = vm.themeMode == ThemeMode.SYSTEM,
                            onClick = { vm.updateThemeMode(ThemeMode.SYSTEM) },
                            label = { Text(stringResource(R.string.theme_system)) }
                        )
                        FilterChip(
                            selected = vm.themeMode == ThemeMode.LIGHT,
                            onClick = { vm.updateThemeMode(ThemeMode.LIGHT) },
                            label = { Text(stringResource(R.string.theme_light)) }
                        )
                        FilterChip(
                            selected = vm.themeMode == ThemeMode.DARK,
                            onClick = { vm.updateThemeMode(ThemeMode.DARK) },
                            label = { Text(stringResource(R.string.theme_dark)) }
                        )
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_reminder_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(stringResource(R.string.settings_reminder_description))

                    Spacer(Modifier.height(16.dp))

                    ReminderRow(
                        title = stringResource(R.string.label_morning_reminder),
                        isOn = isMorningReminderOn,
                        onToggle = { onReminderToggle(ReminderType.MORNING, it) },
                        type = ReminderType.MORNING,
                        context = context
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    ReminderRow(
                        title = stringResource(R.string.label_evening_reminder),
                        isOn = isEveningReminderOn,
                        onToggle = { onReminderToggle(ReminderType.EVENING, it) },
                        type = ReminderType.EVENING,
                        context = context
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.label_weekly_summary_notification), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.desc_weekly_summary_notification),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isWeeklySummaryOn, onCheckedChange = { onWeeklySummaryToggle(it) })
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_ai_coach_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = aiCoachEnabled, onCheckedChange = {
                            aiCoachEnabled = it
                            AiCoachPreferences.setEnabled(context, it)
                        })
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(stringResource(R.string.settings_ai_coach_description))
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_garmin_files_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(stringResource(R.string.settings_garmin_files_description))

                    Spacer(Modifier.height(14.dp))

                    OutlinedButton(onClick = {
                        // Garmin-filer har ingen fast MIME-type på Android - zip og "ukjent".
                        garminImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }) {
                        Text(stringResource(R.string.action_import_garmin))
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_backup_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(stringResource(R.string.settings_backup_description))

                    Spacer(Modifier.height(14.dp))

                    Button(onClick = {
                        exportLauncher.launch("healthlog-backup-${LocalDate.now()}.json")
                    }) {
                        Text(stringResource(R.string.action_create_backup))
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("application/json"))
                    }) {
                        Text(stringResource(R.string.action_restore_backup))
                    }

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider()

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.label_auto_backup), fontWeight = FontWeight.SemiBold)
                            Text(
                                if (autoBackupFileName != null)
                                    stringResource(R.string.desc_auto_backup_on_format, autoBackupFileName)
                                else
                                    stringResource(R.string.desc_auto_backup_off),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isAutoBackupOn, onCheckedChange = { onAutoBackupToggle(it) })
                    }

                    if (autoBackupUri != null && !hasBackupWriteAccess) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.desc_auto_backup_access_lost),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    lastBackupSuccess?.let { timeMillis ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.desc_auto_backup_last_success, formatBackupTime(timeMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    lastBackupError?.let { (message, timeMillis) ->
                        Spacer(Modifier.height(10.dp))
                        val whenText = if (timeMillis > 0L) formatBackupTime(timeMillis) else null
                        Text(
                            text = stringResource(R.string.desc_auto_backup_last_error, whenText ?: "-", message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (autoBackupUri != null) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            autoBackupSetupLauncher.launch(AUTO_BACKUP_FILE_NAME)
                        }) {
                            Text(stringResource(R.string.action_change_backup_location))
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_export_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(stringResource(R.string.settings_export_description))

                    Spacer(Modifier.height(14.dp))

                    OutlinedButton(onClick = {
                        csvExportLauncher.launch("healthlog-export-${LocalDate.now()}.csv")
                    }) {
                        Text(stringResource(R.string.action_export_csv))
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.settings_privacy_title),
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(stringResource(R.string.settings_privacy_description))

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.action_read_privacy_policy),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.PRIVACY_POLICY_URL))
                            )
                        }
                    )
                }
            }
        }

        // MIDLERTIDIG - fjern denne item-blokken igjen når du har bekreftet at
        // testrapporten faktisk dukker opp i Firebase-konsollen (Crashlytics ->
        // Non-fatals). Ikke ment å bli værende i en ferdig app.
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "Test krasjrapportering (midlertidig)",
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        "Sender en test-hendelse til Firebase Crashlytics. Lukk appen helt og åpne den igjen - da lastes den opp. Sjekk etter 5-10 min i Crashlytics, med hendelsestype-filteret satt til \"Non-fatal\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedButton(onClick = {
                        val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                        android.util.Log.d("HealthLogTest", "Sender testrapport til Crashlytics...")
                        crashlytics.log("Testknapp trykket i Innstillinger")
                        crashlytics.recordException(Exception("HealthLog testrapport - kan slettes"))
                        crashlytics.sendUnsentReports()
                        android.util.Log.d("HealthLogTest", "sendUnsentReports() kalt uten å kaste feil.")
                        vm.showMessage("Testrapport sendt (se Logcat med tag HealthLogTest for bekreftelse).")
                    }) {
                        Text("Send testrapport")
                    }
                }
            }
        }
    }

    if (pendingImportJson != null) {
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            title = { Text(stringResource(R.string.action_restore_backup)) },
            text = {
                Text(stringResource(R.string.confirm_restore_message))
            },
            confirmButton = {
                Button(onClick = {
                    vm.importBackup(pendingImportJson!!)
                    pendingImportJson = null
                }) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportJson = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Én rad for enten morgen- eller kveldspåminnelsen - av/på-bryter, og et tidspunkt-
 * valg som bare vises når raden faktisk er skrudd på.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderRow(
    title: String,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    type: ReminderType,
    context: Context
) {
    Text(title, fontWeight = FontWeight.SemiBold)

    Spacer(Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.label_send_reminder))
        Switch(checked = isOn, onCheckedChange = onToggle)
    }

    if (isOn) {
        Spacer(Modifier.height(6.dp))

        var showTimePicker by remember { mutableStateOf(false) }
        val reminderTime = remember(showTimePicker) { ReminderPreferences.getTime(context, type) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.label_reminder_time))
            TextButton(onClick = { showTimePicker = true }) {
                Text("%02d:%02d".format(reminderTime.hour, reminderTime.minute))
            }
        }

        if (showTimePicker) {
            val timePickerState = rememberTimePickerState(
                initialHour = reminderTime.hour,
                initialMinute = reminderTime.minute,
                is24Hour = true
            )
            Dialog(onDismissRequest = { showTimePicker = false }) {
                Surface(shape = RoundedCornerShape(28.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TimePicker(state = timePickerState)

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showTimePicker = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            TextButton(onClick = {
                                ReminderPreferences.setTime(
                                    context,
                                    type,
                                    LocalTime.of(timePickerState.hour, timePickerState.minute)
                                )
                                ReminderScheduler.schedule(context, type)
                                showTimePicker = false
                            }) {
                                Text(stringResource(R.string.action_ok))
                            }
                        }
                    }
                }
            }
        }
    }
}
