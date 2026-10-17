package no.healthlog.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.healthlog.app.data.HealthDay
import no.healthlog.app.scale.ScaleProfileStore
import no.healthlog.app.scale.bodyComposition
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Kroppssammensetning for siste veiing - vises ved trykk på vektgrafen i Trender, så
 * de mange tallene ikke tar plass ellers. Høyden hentes fra vekt-profilen (Vei deg).
 */
@Composable
fun BodyCompositionDialog(days: List<HealthDay>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val latest = remember(days) { days.filter { it.weightKg != null }.maxByOrNull { it.date } }
    val heightCm = remember { ScaleProfileStore(context).load()?.heightCm }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.body_comp_title)) },
        text = {
            if (latest == null) {
                Text(stringResource(R.string.body_comp_no_weight))
            } else {
            val c = bodyComposition(latest.weightKg!!, latest.bodyFatPercent, heightCm)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    runCatching { LocalDate.parse(latest.date).format(DateTimeFormatter.ofPattern("d.M.yyyy")) }.getOrDefault(latest.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row2(stringResource(R.string.body_comp_weight), "%.1f kg".format(c.weightKg))
                c.bmi?.let { Row2(stringResource(R.string.body_comp_bmi), "%.1f".format(it)) }
                c.fatPercent?.let { Row2(stringResource(R.string.body_comp_fat_percent), "%.1f %%".format(it)) }
                c.fatMassKg?.let { Row2(stringResource(R.string.body_comp_fat_mass), "%.1f kg".format(it)) }
                c.leanMassKg?.let { Row2(stringResource(R.string.body_comp_lean_mass), "%.1f kg".format(it)) }
                c.muscleMassKg?.let { Row2(stringResource(R.string.body_comp_muscle_mass) + " *", "%.1f kg".format(it)) }
                c.waterKg?.let { Row2(stringResource(R.string.body_comp_water) + " *", "%.1f kg · %.1f %%".format(it, c.waterPercent)) }
                c.proteinKg?.let { Row2(stringResource(R.string.body_comp_protein) + " *", "%.1f kg".format(it)) }
                c.boneMassKg?.let { Row2(stringResource(R.string.body_comp_bone) + " *", "%.1f kg".format(it)) }
                c.bmrKcal?.let { Row2(stringResource(R.string.body_comp_bmr) + " *", "%d kcal/d".format(it)) }
                Spacer(Modifier.height(6.dp))
                if (c.fatPercent == null) {
                    Text(stringResource(R.string.body_comp_no_fat), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        stringResource(R.string.body_comp_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (heightCm == null) {
                    Text(
                        stringResource(R.string.body_comp_no_height),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
