package no.healthlog.app

import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import no.healthlog.app.billing.BillingManager
import no.healthlog.app.data.*
import no.healthlog.app.garmin.HealthConnectManager

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6B5F),
    secondary = Color(0xFF7C9A92),
    background = Color(0xFFF7F8FA),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCFC0),
    secondary = Color(0xFFA9C4BC),
    background = Color(0xFF12191A),
    surface = Color(0xFF1B2422)
)

class MainActivity : ComponentActivity() {

    // Bruker singleTop launch mode (se manifestet) + onNewIntent under, slik at et trykk
    // på widgetens "+"-knapp gjenbruker en allerede kjørende instans i stedet for å
    // stable opp en ny aktivitet oppå den gamle.
    private val openEntryFlow = MutableStateFlow(false)

    // Samme factory som setContent bruker - ViewModelProvider gir da tilbake SAMME
    // ViewModel-instans som Compose-skjermene, slik at onResume kan be den synke.
    private lateinit var vmFactory: VMFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = AppDatabase.getInstance(applicationContext)
        val repo = HealthRepository(db.healthDayDao())
        val healthConnectManager = HealthConnectManager(applicationContext)
        val billingManager = BillingManager(applicationContext)
        vmFactory = VMFactory(repo, applicationContext, healthConnectManager)
        // Ved skjermrotasjon o.l. leveres samme intent på nytt - en delt Garmin-fil skal
        // bare importeres første gang.
        handleIntent(intent, importSharedFiles = savedInstanceState == null)
        setContent {
            val vm: HealthViewModel = viewModel(factory = vmFactory)
            val openEntryOnLaunch by openEntryFlow.collectAsState()

            val useDarkTheme = when (vm.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MaterialTheme(
                colorScheme = if (useDarkTheme) DarkColors else LightColors
            ) {
                var showOnboarding by rememberSaveable {
                    mutableStateOf(!OnboardingPreferences.hasCompletedOnboarding(applicationContext))
                }

                if (showOnboarding) {
                    OnboardingScreen(onFinished = {
                        OnboardingPreferences.setCompleted(applicationContext)
                        showOnboarding = false
                    })
                } else {
                    HealthLogApp(
                        vm = vm,
                        billingManager = billingManager,
                        healthConnectManager = healthConnectManager,
                        openEntryOnLaunch = openEntryOnLaunch,
                        onEntryDialogConsumed = { openEntryFlow.value = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Henter ferske tall fra Health Connect hver gang appen kommer i forgrunnen
        // (begrenset til maks hvert 10. min - se HealthViewModel.syncRecentOnOpen).
        ViewModelProvider(this, vmFactory)[HealthViewModel::class.java].syncRecentOnOpen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, importSharedFiles = true)
    }

    private fun handleIntent(intent: Intent, importSharedFiles: Boolean) {
        if (importSharedFiles) {
            val shared = sharedFileUris(intent)
            if (shared.isNotEmpty()) {
                ViewModelProvider(this, vmFactory)[HealthViewModel::class.java].importGarminFiles(shared)
            }
        }

        // Health Connect sender denne når brukeren trykker "finn ut mer om hvordan
        // dataene dine brukes" i tillatelsesdialogen - åpner personvernerklæringen
        // direkte i nettleseren i stedet for å bare la MainActivity åpne som normalt.
        if (intent.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE") {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
            )
        }

        // App-snarveien (shortcuts.xml) setter denne via en <extra>-tag, som ikke er
        // garantert å bevare boolsk type - sjekker derfor begge representasjonene i
        // stedet for å stole blindt på getBooleanExtra alene.
        val openEntry = intent.getBooleanExtra(EXTRA_OPEN_ENTRY, false) ||
            intent.getStringExtra(EXTRA_OPEN_ENTRY) == "true"
        if (openEntry) {
            openEntryFlow.value = true
        }
    }

    /** Filene som er delt til / åpnet med appen (Garmin-eksport). */
    private fun sharedFileUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        ).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf { it.scheme == "content" })
        else -> emptyList()
    }

    companion object {
        /** Widgetens "+"-knapp og app-snarveien setter denne for å åpne rett til registrerings-dialogen. */
        const val EXTRA_OPEN_ENTRY = "open_entry_dialog"

        /** Delt med SettingsScreen sin egen "Personvernerklæring"-lenke, slik at URL-en kun står ett sted. */
        const val PRIVACY_POLICY_URL = "https://claude.ai/artifact/7LAkehfQ2qw1a5c11SvGbs"
    }
}
