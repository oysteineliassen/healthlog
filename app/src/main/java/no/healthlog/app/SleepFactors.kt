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
