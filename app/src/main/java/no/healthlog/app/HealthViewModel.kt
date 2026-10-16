package no.healthlog.app

import no.healthlog.app.export.TallerkenDay
import android.content.ClipData
import android.content.ClipboardManager
import no.healthlog.app.coach.AiCoachPreferences
import no.healthlog.app.coach.FastingPeriod
import no.healthlog.app.coach.FastingPreferences
import no.healthlog.app.backup.SettingsBackup
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.healthlog.app.garmin.decodeFit
import no.healthlog.app.garmin.extractFitFiles
import no.healthlog.app.garmin.summarizeGarminFiles
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZoneId
import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import no.healthlog.app.data.*
import no.healthlog.app.garmin.HealthConnectDaySnapshot
import no.healthlog.app.garmin.HealthConnectManager
import no.healthlog.app.garmin.upsertSnapshot
import no.healthlog.app.widget.HealthWidgetProvider
import java.time.LocalDate
import kotlin.math.roundToInt

/** Øvre grense per valgt fil - en dagseksport er under 100 kB, hele kontoen kan være flere GB. */
private const val GARMIN_MAX_FILE_BYTES = 100L * 1024 * 1024

private fun readCapped(input: InputStream, maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > maxBytes) throw IllegalArgumentException("Filen er for stor (over ${maxBytes / 1024 / 1024} MB)")
    }
    return out.toByteArray()
}

data class ManualEntryInput(
    val weightKg: Double?,
    val waistCm: Double?,
    val hrvMs: Double?,
    val stress: Int?,
    val bodyBatteryMax: Int?,
    val bodyBatteryMin: Int?,
    val sleepScore: Int?,
    val morningNote: String?,
    val eveningNote: String?,
    val sleepReflection: String?,
    val sleepFactors: List<String>,
    val dayTag: String?,
    val nutritionLevel: String?,
    // null = ikke med i dialogen (behold det som er lagret), tom liste = ingen merker.
    val habitTags: List<String>? = null,
    // Kun fylt ut fra EditHistoryDialog (Historikk) - EntryDialog ("+" på I dag-fanen)
    // sender alltid null for disse, siden de normalt kommer automatisk fra Health
    // Connect og ikke skal la seg endre derfra.
    val steps: Int? = null,
    val activeMinutes: Int? = null,
    val sleepMinutes: Int? = null,
    val restingHeartRate: Int? = null,
    val avgHeartRate: Int? = null,
    val trainingMinutes: Int? = null,
    val trainingType: String? = null,
    val trainingIntensity: String? = null
)

class HealthViewModel(
    private val repo: HealthRepository,
    private val appContext: Context,
    private val healthConnectManager: HealthConnectManager
) : ViewModel() {
    val days =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var message by mutableStateOf<String?>(null)
        private set

    /** Kopi av raden som sist ble slettet, slik at UI kan tilby en "Angre"-handling. */
    var lastDeletedDay by mutableStateOf<HealthDay?>(null)
        private set

    var themeMode by mutableStateOf(ThemePreferences.get(appContext))
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        ThemePreferences.set(appContext, mode)
    }

    /**
     * Fanger uventede feil (f.eks. disk full, I/O-feil) i stedet for å la appen krasje.
     * Brukes av alle viewModelScope-kallene under, bortsett fra importBackup som allerede
     * har sin egen, mer spesifikke feilhåndtering.
     */
    private fun safeLaunch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                message = appContext.getString(R.string.error_generic, e.message ?: e::class.simpleName)
            }
        }
    }

    fun exportBackupJson(): String =
        healthDaysToJson(days.value, SettingsBackup.export(appContext))

    fun exportCsv(): String =
        healthDaysToCsv(days.value)

    /**
     * Leser valgte Garmin-filer (.zip fra "Eksporter" i Garmin Connect, eller enkelt-
     * .fit-filer) og fletter dataene inn. Kjører utenfor hovedtråden - en zip kan
     * inneholde mange filer.
     */
    fun importGarminFiles(uris: List<Uri>) {
        safeLaunch {
            val days = withContext(Dispatchers.IO) {
                val fitFiles = uris.flatMap { uri ->
                    val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        readCapped(input, GARMIN_MAX_FILE_BYTES)
                    } ?: return@flatMap emptyList()
                    extractFitFiles(bytes)
                }
                val decoded = fitFiles.mapNotNull { runCatching { decodeFit(it) }.getOrNull() }
                summarizeGarminFiles(decoded, ZoneId.systemDefault())
            }
            if (days.isEmpty()) {
                message = appContext.getString(R.string.garmin_import_none)
                return@safeLaunch
            }
            val count = repo.importGarminDays(days)
            message = appContext.getString(R.string.garmin_import_done, count)
            HealthWidgetProvider.requestUpdate(appContext)
        }
    }

    fun importBackup(json: String) {
        viewModelScope.launch {
            try {
                val parsed = jsonToHealthDays(json)
                val count = repo.importBackup(parsed)
                val settings = jsonToBackupSettings(json)
                SettingsBackup.restore(appContext, settings)
                message = appContext.getString(
                    if (settings.isEmpty()) R.string.backup_restored else R.string.backup_restored_with_settings,
                    count
                )
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                message = appContext.getString(R.string.backup_read_failed, e.message)
            }
        }
    }

    fun saveManual(date: String, input: ManualEntryInput) {
        safeLaunch {
            val old = repo.get(date)

            // Marker et felt som "beskyttet mot Health Connect-synk" kun når verdien
            // FAKTISK er endret fra det som lå der fra før - ikke bare fordi dialogen
            // sendte med samme forhåndsutfylte tall igjen uten at du rørte det.
            val overrides = old.manualOverrideSet().toMutableSet()
            fun markIfChanged(field: String, newValue: Any?, oldValue: Any?) {
                if (newValue != null && newValue != oldValue) overrides.add(field)
            }
            markIfChanged("weightKg", input.weightKg, old?.weightKg)
            markIfChanged("steps", input.steps, old?.steps)
            markIfChanged("activeMinutes", input.activeMinutes, old?.activeMinutes)
            markIfChanged("sleepMinutes", input.sleepMinutes, old?.sleepMinutes)
            markIfChanged("restingHeartRate", input.restingHeartRate, old?.restingHeartRate)
            markIfChanged("avgHeartRate", input.avgHeartRate, old?.avgHeartRate)
            markIfChanged("trainingMinutes", input.trainingMinutes, old?.trainingMinutes)
            markIfChanged("trainingType", input.trainingType, old?.trainingType)
            markIfChanged("trainingIntensity", input.trainingIntensity, old?.trainingIntensity)

            repo.upsert(
                (old ?: HealthDay(date = date)).copy(
                    weightKg = input.weightKg ?: old?.weightKg,
                    waistCm = input.waistCm ?: old?.waistCm,
                    hrvMs = input.hrvMs ?: old?.hrvMs,
                    stress = input.stress ?: old?.stress,
                    bodyBatteryMax = input.bodyBatteryMax ?: old?.bodyBatteryMax,
                    bodyBatteryMin = input.bodyBatteryMin ?: old?.bodyBatteryMin,
                    sleepScore = input.sleepScore ?: old?.sleepScore,
                    steps = input.steps ?: old?.steps,
                    activeMinutes = input.activeMinutes ?: old?.activeMinutes,
                    sleepMinutes = input.sleepMinutes ?: old?.sleepMinutes,
                    restingHeartRate = input.restingHeartRate ?: old?.restingHeartRate,
                    avgHeartRate = input.avgHeartRate ?: old?.avgHeartRate,
                    trainingMinutes = input.trainingMinutes ?: old?.trainingMinutes,
                    trainingType = input.trainingType ?: old?.trainingType,
                    trainingIntensity = input.trainingIntensity ?: old?.trainingIntensity,
                    // Merkes ikke sammen med gammel verdi som de andre feltene: tømmer du
                    // et av dagboktekst-feltene (eller fjerner en tagg) og lagrer, skal
                    // det faktisk slettes.
                    morningNote = input.morningNote,
                    eveningNote = input.eveningNote,
                    sleepReflection = input.sleepReflection,
                    sleepFactors = input.sleepFactors.toSet().toSleepFactorsString(),
                    dayTag = input.dayTag,
                    nutritionLevel = input.nutritionLevel,
                    habitTags = input.habitTags?.toSet()?.toHabitTagsString() ?: old?.habitTags,
                    manualOverrides = overrides.toManualOverridesString(),
                    source = "manual"
                )
            )
            message = appContext.getString(R.string.saved)
            HealthWidgetProvider.requestUpdate(appContext)

            // Deler vekt tilbake til Health Connect kun når den faktisk ble registrert
            // manuelt akkurat nå (ikke når feltet bare beholdes uendret fra før). Feiler
            // stille (manglende tillatelse, Health Connect utilgjengelig osv.) - den
            // lokale lagringen over skal aldri påvirkes av at dette eventuelt ikke går.
            if (input.weightKg != null) {
                runCatching {
                    healthConnectManager.writeWeight(LocalDate.parse(date), input.weightKg)
                }
            }
        }
    }

    /** Når synk-ved-åpning sist faktisk kjørte - hindrer ny synk ved hvert eneste app-bytte. */
    private var lastOpenSyncMillis = 0L

    /**
     * Stille synk av de siste dagene når appen åpnes/kommer i forgrunnen, slik at tallene
     * alltid er ferske når du ser på dem - uavhengig av når bakgrunnsjobben sist kjørte
     * (den har ingen fast tid, se HealthConnectSyncWorker). Gjør ingenting uten tillatelser,
     * viser ingen melding, og kjører maks hvert 10. minutt.
     */
    /** Fasteperiodene (lagret i innstillingene) - observerbar så kortene oppdateres. */
    var fastingPeriods by mutableStateOf(FastingPreferences.get(appContext))
        private set

    fun addFastingPeriod(period: FastingPeriod) {
        val updated = (fastingPeriods.filterNot { it.start == period.start } + period).sortedBy { it.start }
        FastingPreferences.set(appContext, updated)
        fastingPeriods = updated
        applyFastingPeriods()
    }

    fun removeFastingPeriod(period: FastingPeriod) {
        val updated = fastingPeriods - period
        FastingPreferences.set(appContext, updated)
        fastingPeriods = updated
        // Fjern faste-markeringen som perioden satte (dager du har valgt noe annet på røres ikke).
        safeLaunch {
            period.dates().forEach { date ->
                val day = repo.get(date.toString()) ?: return@forEach
                if (day.nutritionLevel == NutritionLevel.FASTING && updated.none { date in it }) {
                    repo.upsert(day.copy(nutritionLevel = null))
                }
            }
        }
    }

    /**
     * Markerer dagene i fasteperiodene som "Faste" (kosthold) fram til og med i dag, så
     * de holdes utenfor normalen i coachen. Framtidige dager markeres når de kommer.
     * Dager der du selv har valgt et annet kosthold endres ikke.
     */
    fun applyFastingPeriods() {
        val today = LocalDate.now()
        safeLaunch {
            fastingPeriods.flatMap { it.dates() }.filter { !it.isAfter(today) }.forEach { date ->
                val day = repo.get(date.toString())
                if (day == null) {
                    repo.upsert(HealthDay(date = date.toString(), nutritionLevel = NutritionLevel.FASTING))
                } else if (day.nutritionLevel == null) {
                    repo.upsert(day.copy(nutritionLevel = NutritionLevel.FASTING))
                }
            }
        }
    }

    /** Fra søndagsvarselet: kopier ukens AI-prompt (alle data lest direkte, ikke fra UI-tilstanden). */
    fun copyAiPromptToClipboard() {
        safeLaunch {
            val all = repo.getAllOnce()
            val prompt = buildAiPromptForApp(appContext, all, AiCoachPreferences.getPeriodDays(appContext))
            appContext.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("HealthLog AI-prompt", prompt))
            message = appContext.getString(R.string.ai_coach_prompt_copied)
        }
    }

    /** Diagnose av skritt-dataene for en dag (se HealthConnectManager.stepsDiagnostics). */
    var stepsDiagnostics by mutableStateOf<String?>(null)
        private set

    fun loadStepsDiagnostics(date: LocalDate) {
        safeLaunch { stepsDiagnostics = healthConnectManager.stepsDiagnostics(date) }
    }

    fun clearStepsDiagnostics() {
        stepsDiagnostics = null
    }

    /** Tallerken-eksport: egne vekt- og midjemål fra databasen vinner over Health Connect. */
    suspend fun buildTallerkenJson(): String {
        val manual = repo.getAllOnce()
            .filter { it.weightKg != null || it.waistCm != null }
            .mapNotNull { d ->
                runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { TallerkenDay(it, weightKg = d.weightKg, waistCm = d.waistCm) }
            }
        return healthConnectManager.buildTallerkenExport(manual)
    }

    /**
     * Måling fra Huawei-vekta (scale/ScaleScreen): vekt og eventuelt fettprosent på dagens
     * rad, slik manuell registrering gjør. Feltene merkes som manuelt satt, så en senere
     * Health Connect-synk ikke overskriver dem, og vekta deles til Health Connect.
     */
    fun saveScaleReading(weightKg: Float, fatPercent: Float?) {
        safeLaunch {
            val date = LocalDate.now().toString()
            val old = repo.get(date)
            val overrides = old.manualOverrideSet().toMutableSet().apply {
                add("weightKg")
                if (fatPercent != null) add("bodyFatPercent")
            }
            // Avrundet til én desimal, slik vekta viser det (Float -> Double gir ellers 82.30000305).
            val weight = Math.round(weightKg * 10.0) / 10.0
            val fat = fatPercent?.let { Math.round(it * 10.0) / 10.0 }
            repo.upsert(
                (old ?: HealthDay(date = date)).copy(
                    weightKg = weight,
                    bodyFatPercent = fat ?: old?.bodyFatPercent,
                    manualOverrides = overrides.toManualOverridesString()
                )
            )
            message = appContext.getString(R.string.scale_saved)
            HealthWidgetProvider.requestUpdate(appContext)
            runCatching { healthConnectManager.writeWeight(LocalDate.parse(date), weight) }
        }
    }

    fun syncRecentOnOpen() {
        applyFastingPeriods()
        val now = System.currentTimeMillis()
        if (now - lastOpenSyncMillis < 10 * 60 * 1000L) return
        lastOpenSyncMillis = now
        viewModelScope.launch {
            try {
                if (!healthConnectManager.isAvailable()) return@launch
                if (!healthConnectManager.hasAllPermissions()) return@launch
                pullRecentDays()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                lastOpenSyncMillis = 0L // prøv igjen neste gang appen åpnes
            }
        }
    }

    /** true mens dra-ned-for-å-oppdatere henter data - styrer spinneren øverst. */
    var isRefreshing by mutableStateOf(false)
        private set

    /** Økes etter hver vellykket oppdatering, slik at kort som henter sine egne data
     * direkte fra Health Connect (f.eks. pulsgrafen på I dag) vet at de skal laste på nytt. */
    var refreshCount by mutableStateOf(0)
        private set

    /**
     * Dra ned for å oppdatere. Samme henting som ved åpning, men uten 10-minutters-
     * sperren, og med tilbakemelding hvis tilgang mangler eller noe feiler - her har
     * brukeren aktivt bedt om det, så en stille feil ville bare sett ut som at ingenting skjedde.
     */
    fun refreshFromHealthConnect() {
        if (isRefreshing) return
        isRefreshing = true
        viewModelScope.launch {
            try {
                if (!healthConnectManager.isAvailable()) {
                    message = appContext.getString(R.string.msg_health_connect_unavailable)
                    return@launch
                }
                if (!healthConnectManager.hasAllPermissions()) {
                    message = appContext.getString(R.string.msg_refresh_needs_permission)
                    return@launch
                }
                pullRecentDays()
                lastOpenSyncMillis = System.currentTimeMillis()
                refreshCount++
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                message = appContext.getString(R.string.error_generic, e.message ?: e::class.simpleName)
            } finally {
                isRefreshing = false
            }
        }
    }

    /** Henter og lagrer de tre siste dagene - felles for synk ved åpning og dra-ned-oppdatering. */
    private suspend fun pullRecentDays() {
        val today = LocalDate.now()
        // Samme vindu som bakgrunnsjobben: tre dager bakover fanger opp søvn som
        // startet kvelden før og skritt som Garmin synker inn med forsinkelse.
        healthConnectManager.readRange(today.minusDays(2), today).forEach { (date, snapshot) ->
            repo.upsertSnapshot(date.toString(), snapshot)
        }
        HealthWidgetProvider.requestUpdate(appContext)
    }

    fun syncFromHealthConnect(date: String, snapshot: HealthConnectDaySnapshot) {
        safeLaunch {
            repo.upsertSnapshot(date, snapshot)
            message = appContext.getString(R.string.health_connect_fetched_today)
            HealthWidgetProvider.requestUpdate(appContext)
        }
    }

    fun syncRangeFromHealthConnect(snapshots: Map<LocalDate, HealthConnectDaySnapshot>) {
        safeLaunch {
            snapshots.forEach { (date, snapshot) ->
                repo.upsertSnapshot(date.toString(), snapshot)
            }
            message = appContext.getString(R.string.health_connect_fetched_range, snapshots.size)
            HealthWidgetProvider.requestUpdate(appContext)
        }
    }

    /**
     * Fjerner en tidligere manuell rettelse for ett enkelt felt (kalt fra "tilbakestill"-
     * knappen i EditHistoryDialog), og henter umiddelbart et ferskt tall for akkurat den
     * dagen fra Health Connect - uten dette ville feltet stått tomt/uendret helt til
     * neste ordinære synk skjer av seg selv.
     */
    fun resetFieldToHealthConnect(date: String, field: String) {
        safeLaunch {
            repo.clearManualOverride(date, field)
            val localDate = LocalDate.parse(date)
            // readDay (ikke en direkte readRange(localDate, localDate)) utvider selv
            // spørringsvinduet bakover én dag ved behov, slik at en søvnøkt som startet
            // kvelden før fortsatt fanges opp riktig - se HealthConnectManager.readDay.
            val snapshot = healthConnectManager.readDay(localDate)
            repo.upsertSnapshot(date, snapshot)
            message = appContext.getString(R.string.msg_field_reset_to_health_connect)
            HealthWidgetProvider.requestUpdate(appContext)
        }
    }

    fun deleteDay(date: String) {
        safeLaunch {
            val deleted = repo.get(date)
            repo.delete(date)
            lastDeletedDay = deleted
            message = appContext.getString(R.string.entry_deleted)
            HealthWidgetProvider.requestUpdate(appContext)
        }
    }

    /** Setter tilbake raden som sist ble slettet - kalt fra "Angre"-knappen i snackbaren. */
    fun undoDelete() {
        val day = lastDeletedDay ?: return
        lastDeletedDay = null
        safeLaunch {
            repo.upsert(day)
            HealthWidgetProvider.requestUpdate(appContext)
        }
    }

    /** Gjør slettingen endelig - kalt når "Angre"-snackbaren forsvinner uten å bli trykket. */
    fun clearLastDeleted() {
        lastDeletedDay = null
    }


    fun clearMessage() {
        message = null
    }

    fun showMessage(text: String) {
        message = text
    }
}

/**
 * Gradert uttelling mellom [floor] (0 poeng) og [goal] (full uttelling) - i stedet for
 * alt-eller-ingenting. Under [floor] gis INGEN poeng i det hele tatt: den delen av
 * skalaen skal representere en reell helserisiko (f.eks. svært lite søvn), ikke bare
 * "litt under målet", og skal derfor ikke gi noen trøstepremie.
 */
private fun gradedScore(value: Double, floor: Double, goal: Double, maxPoints: Int): Int {
    if (goal <= floor) return if (value >= goal) maxPoints else 0
    val fraction = ((value - floor) / (goal - floor)).coerceIn(0.0, 1.0)
    return (fraction * maxPoints).roundToInt()
}

/** Samme prinsipp som gradedScore, men for omvendte skalaer der LAVERE er bedre (stress). */
private fun gradedScoreInverted(value: Double, goal: Double, ceiling: Double, maxPoints: Int): Int {
    if (ceiling <= goal) return if (value <= goal) maxPoints else 0
    val fraction = ((ceiling - value) / (ceiling - goal)).coerceIn(0.0, 1.0)
    return (fraction * maxPoints).roundToInt()
}

enum class HealthScoreCriterionType { SLEEP, HRV, STRESS, STEPS, BODY_BATTERY }

data class HealthScoreCriterion(val type: HealthScoreCriterionType, val points: Int, val maxPoints: Int = 20)
data class HealthScoreBreakdown(val total: Int, val criteria: List<HealthScoreCriterion>)

/**
 * sleepGoalMinutes/stepsGoal har standardverdier som matcher GoalPreferences sine egne
 * standarder (8t/8000 skritt) - brukt kun som et sikkerhetsnett hvis en fremtidig
 * kallested glemmer å sende dem inn. Alle faktiske stedene i appen (I dag, Trender,
 * Historikk-kalenderen, Rekorder, årsoversikt, AI-treneren, uke-/månedsoppsummering,
 * periode-sammenligning) sender nå inn DINE egne innstilte mål.
 *
 * Hvert kriterium gir gradert uttelling mellom en "faregrense" (0 poeng) og målet (full
 * uttelling) i stedet for alt-eller-ingenting - f.eks. gir 4 timer søvn ved et 8-timers
 * mål ingen poeng i det hele tatt (under 75%-grensa), i stedet for halv pott.
 *
 * Selve nedbrytningen (per-kriterium poeng) er skilt ut i egen funksjon, brukt av
 * detaljvisningen (trykk på Helse-score-kortet) - garanterer at tallene der alltid
 * stemmer nøyaktig overens med selve totalsummen, siden begge deler samme utregning.
 */
fun calculateHealthScoreBreakdown(day: HealthDay?, sleepGoalMinutes: Int = 480, stepsGoal: Int = 8000): HealthScoreBreakdown {
    if (day == null) {
        return HealthScoreBreakdown(0, HealthScoreCriterionType.entries.map { HealthScoreCriterion(it, 0) })
    }

    // Søvn: 0 poeng ved 75% av målet eller lavere (f.eks. 6t ved 8t-mål) - under den
    // grensa regnes søvnmangelen som en reell helserisiko, ikke bare "litt lite".
    val sleepPoints = gradedScore(
        value = (day.sleepMinutes ?: 0).toDouble(),
        floor = sleepGoalMinutes * 0.75,
        goal = sleepGoalMinutes.toDouble(),
        maxPoints = 20
    )

    // HRV: ingen justerbart mål ennå - beholder 40ms som "godt" nivå fra før, gradert
    // ned til 20ms (under det regnes som markant forhøyet stressnivå).
    val hrvPoints = gradedScore(value = day.hrvMs ?: 0.0, floor = 20.0, goal = 40.0, maxPoints = 20)

    // Stress: omvendt skala - lavere er bedre. Full uttelling ved 30 eller under (som
    // før), null uttelling ved 70 eller over.
    val stressPoints = gradedScoreInverted(
        value = (day.stress ?: 100).toDouble(),
        goal = 30.0,
        ceiling = 70.0,
        maxPoints = 20
    )

    // Skritt: 0 poeng ved 30% av målet eller lavere (f.eks. 2400 ved 8000-mål) - under
    // det regnes dagen som i praksis stillesittende.
    val stepsPoints = gradedScore(
        value = (day.steps ?: 0).toDouble(),
        floor = stepsGoal * 0.3,
        goal = stepsGoal.toDouble(),
        maxPoints = 20
    )

    // Body Battery: ingen justerbart mål ennå - beholder 75 som "godt" nivå fra før,
    // gradert ned til 40 (markant utladet).
    val bodyBatteryPoints = gradedScore(
        value = (day.bodyBatteryMax ?: 0).toDouble(),
        floor = 40.0,
        goal = 75.0,
        maxPoints = 20
    )

    val criteria = listOf(
        HealthScoreCriterion(HealthScoreCriterionType.SLEEP, sleepPoints),
        HealthScoreCriterion(HealthScoreCriterionType.HRV, hrvPoints),
        HealthScoreCriterion(HealthScoreCriterionType.STRESS, stressPoints),
        HealthScoreCriterion(HealthScoreCriterionType.STEPS, stepsPoints),
        HealthScoreCriterion(HealthScoreCriterionType.BODY_BATTERY, bodyBatteryPoints)
    )
    return HealthScoreBreakdown(criteria.sumOf { it.points }, criteria)
}

fun calculateHealthScore(day: HealthDay?, sleepGoalMinutes: Int = 480, stepsGoal: Int = 8000): Int =
    calculateHealthScoreBreakdown(day, sleepGoalMinutes, stepsGoal).total

fun healthScoreStatus(context: Context, score: Int): String = when {
    score >= 80 -> context.getString(R.string.health_score_status_great)
    score >= 60 -> context.getString(R.string.health_score_status_good)
    score >= 40 -> context.getString(R.string.health_score_status_ok)
    else -> context.getString(R.string.health_score_status_poor)
}

fun weightChange(days: List<HealthDay>): Double? {

    val values =
        days
            .sortedBy { it.date }
            .takeLast(14)
            .mapNotNull { it.weightKg }

    if (values.size < 2) return null

    return values.last() - values.first()
}

class VMFactory(
    private val repo: HealthRepository,
    private val appContext: Context,
    private val healthConnectManager: HealthConnectManager
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HealthViewModel(repo, appContext, healthConnectManager) as T
    }
}
