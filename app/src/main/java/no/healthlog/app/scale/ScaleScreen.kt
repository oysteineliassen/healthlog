package no.healthlog.app.scale

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Vei deg" screen. Shows a one-time profile form (height, birth year, sex — the scale needs
 * them for body fat), then connects and shows the result with a save button.
 *
 * @param lastWeightKg latest weight from HealthLog's database, used as a hint for the scale
 * @param onSave called when the user taps "Lagre"; write it to Room here
 */
@Composable
fun ScaleScreen(
    lastWeightKg: Float?,
    onSave: (Ah100Protocol.Reading) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tidligere veiinger, nyeste først - vises under målingen så du kan sammenligne. */
    previous: List<PreviousWeighIn> = emptyList(),
) {
    val context = LocalContext.current
    val store = remember { ScaleProfileStore(context) }
    var profile by remember { mutableStateOf(store.load()) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Vei deg", style = MaterialTheme.typography.headlineMedium)
        val p = profile
        if (p == null) {
            ProfileForm(onDone = { store.save(it); profile = it })
        } else {
            WeighIn(p, lastWeightKg, onSave, onClose, onEditProfile = { profile = null })
        }
        if (previous.isNotEmpty()) PreviousWeighIns(previous)
    }
}

@Composable
private fun WeighIn(
    profile: Ah100Protocol.Profile,
    lastWeightKg: Float?,
    onSave: (Ah100Protocol.Reading) -> Unit,
    onClose: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val context = LocalContext.current
    val client = remember(profile) { Ah100ScaleClient(context, profile, lastWeightKg) }
    DisposableEffect(client) { onDispose { client.stop() } }
    val state by client.state.collectAsState()

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (bluetoothOn(context)) client.start()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            if (bluetoothOn(context)) client.start()
            else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    fun begin() {
        when {
            !hasPermissions(context) -> permissions.launch(requiredPermissions())
            !bluetoothOn(context) -> enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> client.start()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                ScaleState.Idle -> {
                    Text("Trykk start og tråkk på vekten barbent.")
                    Button(onClick = ::begin) { Text("Start") }
                }
                ScaleState.Searching -> Busy("Tråkk på vekten så den våkner …")
                ScaleState.Connecting -> Busy("Kobler til …")
                ScaleState.Measuring -> Busy("Stå stille, måler fettprosent …")
                is ScaleState.Done -> {
                    val r = s.reading
                    Text(
                        String.format(Locale("nb", "NO"), "%.1f kg", r.weightKg),
                        fontSize = 48.sp, fontWeight = FontWeight.Bold,
                    )
                    r.fatPercent?.let { Text(String.format(Locale("nb", "NO"), "Fett: %.1f %%", it)) }
                        ?: Text("Fett: ikke målt (stå barbent)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = ::begin) { Text("Vei på nytt") }
                        Button(onClick = { onSave(r); onClose() }) { Text("Lagre") }
                    }
                }
                is ScaleState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = ::begin) { Text("Prøv igjen") }
                }
            }
        }
    }

    Text(
        "${profile.heightCm} cm · ${profile.age} år · ${if (profile.male) "mann" else "kvinne"}",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onEditProfile) { Text("Endre profil") }
}

data class PreviousWeighIn(val date: LocalDate, val weightKg: Double, val fatPercent: Double?)

@Composable
private fun PreviousWeighIns(previous: List<PreviousWeighIn>) {
    val nb = Locale("nb", "NO")
    val dateFormat = DateTimeFormatter.ofPattern("EEE d. MMM", nb)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tidligere veiinger", style = MaterialTheme.typography.titleMedium)
            previous.forEachIndexed { i, w ->
                // Endring mot veiingen før denne (neste i lista, siden nyeste står først).
                val change = previous.getOrNull(i + 1)?.let { w.weightKg - it.weightKg }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.date.format(dateFormat), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        change?.let { String.format(nb, "%+.1f", it) } ?: "",
                        Modifier.width(56.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        w.fatPercent?.let { String.format(nb, "%.1f %%", it) } ?: "–",
                        Modifier.width(64.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        String.format(nb, "%.1f kg", w.weightKg),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator(Modifier.size(48.dp))
    Text(text)
}

@Composable
private fun ProfileForm(onDone: (Ah100Protocol.Profile) -> Unit) {
    var height by rememberSaveable { mutableStateOf("") }
    var birthYear by rememberSaveable { mutableStateOf("") }
    var male by rememberSaveable { mutableStateOf(true) }
    val h = height.toIntOrNull()?.takeIf { it in 100..220 }
    val y = birthYear.toIntOrNull()?.takeIf { it in 1920..LocalDate.now().year - 10 }

    Text("Vekten trenger høyde, alder og kjønn for å regne ut fettprosent.")
    OutlinedTextField(
        height, { height = it.filter(Char::isDigit) }, label = { Text("Høyde (cm)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
    )
    OutlinedTextField(
        birthYear, { birthYear = it.filter(Char::isDigit) }, label = { Text("Fødselsår") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = male, onClick = { male = true })
        Text("Mann")
        Spacer(Modifier.size(16.dp))
        RadioButton(selected = !male, onClick = { male = false })
        Text("Kvinne")
    }
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = h != null && y != null,
        onClick = { onDone(Ah100Protocol.Profile(heightCm = h!!, age = LocalDate.now().year - y!!, male = male)) },
    ) { Text("Lagre profil") }
}

// ---------------------------------------------------------------- helpers

/** Stores height / birth year / sex so the age stays correct over the years. */
class ScaleProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("scale_profile", Context.MODE_PRIVATE)

    fun load(): Ah100Protocol.Profile? {
        val h = prefs.getInt("height", 0)
        val y = prefs.getInt("birthYear", 0)
        if (h == 0 || y == 0) return null
        return Ah100Protocol.Profile(h, LocalDate.now().year - y, prefs.getBoolean("male", true))
    }

    fun save(p: Ah100Protocol.Profile) = prefs.edit()
        .putInt("height", p.heightCm)
        .putInt("birthYear", LocalDate.now().year - p.age)
        .putBoolean("male", p.male)
        .apply()
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermissions(context: Context) = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun bluetoothOn(context: Context) =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
