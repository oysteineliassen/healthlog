package no.healthlog.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Faste årsaker til en god/dårlig natt, ved siden av fritekst-refleksjonen. Lagres med
 * id-ene under (ikke tekst), så coachen kan regne på dem og språket kan byttes.
 */
object SleepFactors {
    const val LATE_MEAL = "late_meal"
    const val CAFFEINE = "caffeine"
    const val ALCOHOL = "alcohol"
    const val SCREEN = "screen"
    const val STRESS = "stress"
    const val LATE_BEDTIME = "late_bedtime"
    const val KIDS = "kids"
    const val NOISE = "noise"
    const val HEAT = "heat"
    const val ILLNESS = "illness"

    val all = listOf(LATE_MEAL, CAFFEINE, ALCOHOL, SCREEN, STRESS, LATE_BEDTIME, KIDS, NOISE, HEAT, ILLNESS)
}

@Composable
fun sleepFactorLabel(id: String): String = when (id) {
    SleepFactors.LATE_MEAL -> stringResource(R.string.sleep_factor_late_meal)
    SleepFactors.CAFFEINE -> stringResource(R.string.sleep_factor_caffeine)
    SleepFactors.ALCOHOL -> stringResource(R.string.sleep_factor_alcohol)
    SleepFactors.SCREEN -> stringResource(R.string.sleep_factor_screen)
    SleepFactors.STRESS -> stringResource(R.string.sleep_factor_stress)
    SleepFactors.LATE_BEDTIME -> stringResource(R.string.sleep_factor_late_bedtime)
    SleepFactors.KIDS -> stringResource(R.string.sleep_factor_kids)
    SleepFactors.NOISE -> stringResource(R.string.sleep_factor_noise)
    SleepFactors.HEAT -> stringResource(R.string.sleep_factor_heat)
    SleepFactors.ILLNESS -> stringResource(R.string.sleep_factor_illness)
    else -> id
}

@Composable
fun SleepFactorSelector(selected: List<String>, onChange: (List<String>) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SleepFactors.all.forEach { id ->
            FilterChip(
                selected = id in selected,
                onClick = { onChange(if (id in selected) selected - id else selected + id) },
                label = { Text(sleepFactorLabel(id)) }
            )
        }
    }
}

/**
 * Hvilke søvn-årsaker gårsdagens vane-merker peker på (vane-merkene er fritekst, så det
 * matches på kjente ord). Brukes til å foreslå årsaker om morgenen - et merke som
 * "Alkohol" i går kveld gir forslaget "Alkohol" for natten som fulgte.
 */
fun suggestedSleepFactors(habitTags: Set<String>): Set<String> {
    val keywords = mapOf(
        SleepFactors.ALCOHOL to listOf("alkohol", "vin", "øl", "alcohol", "wine", "beer"),
        SleepFactors.LATE_MEAL to listOf("sen middag", "sent måltid", "sen mat", "kveldsmat", "late meal", "late dinner"),
        SleepFactors.CAFFEINE to listOf("koffein", "kaffe", "cola", "energidrikk", "caffeine", "coffee"),
        SleepFactors.SCREEN to listOf("skjerm", "mobil", "tv", "screen", "phone"),
        SleepFactors.STRESS to listOf("stress"),
        SleepFactors.LATE_BEDTIME to listOf("sen leggetid", "sent i seng", "sent oppe", "late bed")
    )
    val tags = habitTags.map { it.lowercase().trim() }
    return keywords.filter { (_, words) -> tags.any { tag -> words.any { w -> containsWholeWord(tag, w) } } }.keys
}

/**
 * Hele ord, ikke delstrenger - ellers ville "tv" truffet "utvikling" og "vin" "vinter".
 * Bevisst uten regex: Unicode-ordgrenser i regex ((?U)) finnes i vanlig Java, men ikke
 * på Android, og krasjet registreringsvinduet. Character.isLetterOrDigit tar æ/ø/å.
 */
internal fun containsWholeWord(text: String, word: String): Boolean {
    var from = 0
    while (true) {
        val i = text.indexOf(word, from)
        if (i < 0) return false
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + word.length)
        val startOk = before == null || !before.isLetterOrDigit()
        val endOk = after == null || !after.isLetterOrDigit()
        if (startOk && endOk) return true
        from = i + 1
    }
}

/** Forslag fra gårsdagens vaner: én knapp per årsak som ikke allerede er valgt. */
@Composable
fun SleepFactorSuggestions(previousDayHabits: Set<String>, selected: List<String>, onAdd: (String) -> Unit) {
    val suggestions = suggestedSleepFactors(previousDayHabits).filter { it !in selected }
    if (suggestions.isEmpty()) return
    androidx.compose.foundation.layout.Column {
        Text(
            stringResource(R.string.sleep_factor_suggestion_label),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { id ->
                androidx.compose.material3.AssistChip(
                    onClick = { onAdd(id) },
                    label = { Text("+ " + sleepFactorLabel(id)) }
                )
            }
        }
    }
}

/** Overskrift som deler registreringen i "Natten som var" og "Dagen". */
@Composable
fun EntrySectionHeader(text: String) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
    )
}
