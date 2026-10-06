package no.healthlog.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.healthlog.app.coach.FastingComparisonRow
import no.healthlog.app.coach.FastingMetric
import no.healthlog.app.coach.FastingPeriod
import no.healthlog.app.coach.FastingPhaseValue
import no.healthlog.app.coach.fastingComparison
import no.healthlog.app.coach.relevantFastingPeriod
import no.healthlog.app.data.HealthDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val shortDate = DateTimeFormatter.ofPattern("d.M.")

@Composable
private fun fastingMetricLabel(metric: FastingMetric): String = stringResource(
    when (metric) {
        FastingMetric.HRV -> R.string.fasting_metric_hrv
        FastingMetric.RESTING_HR -> R.string.fasting_metric_resting_hr
        FastingMetric.SLEEP_HOURS -> R.string.fasting_metric_sleep
        FastingMetric.STRESS -> R.string.fasting_metric_stress
        FastingMetric.BODY_BATTERY_MAX -> R.string.fasting_metric_body_battery
        FastingMetric.WEIGHT -> R.string.fasting_metric_weight
    }
)

private fun formatPhase(metric: FastingMetric, value: FastingPhaseValue?): String = when {
    value == null -> "–"
    metric == FastingMetric.SLEEP_HOURS || metric == FastingMetric.WEIGHT -> "%.1f".format(value.average)
    else -> "%.0f".format(value.average)
}

/**
 * Faste-kortet på I dag: vises fra 14 dager før til 14 dager etter en registrert
 * periode, med uka før / under / uka etter for de viktigste målingene.
 */
@Composable
fun FastingCard(all: List<HealthDay>, periods: List<FastingPeriod>) {
    val today = LocalDate.now()
    val period = relevantFastingPeriod(periods, today) ?: return
    val rows = remember(all, period, today) { fastingComparison(all, period, today) }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.fasting_card_title), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val status = when {
                today.isBefore(period.start) -> stringResource(
                    R.string.fasting_status_upcoming,
                    ChronoUnit.DAYS.between(today, period.start).toInt(),
                    period.days
                )
                today in period -> stringResource(
                    R.string.fasting_status_ongoing,
                    ChronoUnit.DAYS.between(period.start, today).toInt() + 1,
                    period.days
                )
                else -> stringResource(R.string.fasting_status_done, period.start.format(shortDate), period.end.format(shortDate))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FastingTable(rows)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.fasting_card_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FastingTable(rows: List<FastingComparisonRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Spacer(Modifier.weight(1.4f))
            listOf(R.string.fasting_col_before, R.string.fasting_col_during, R.string.fasting_col_after).forEach {
                Text(
                    stringResource(it),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        rows.filter { it.before != null || it.during != null || it.after != null }.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fastingMetricLabel(row.metric), modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                listOf(row.before, row.during, row.after).forEach { value ->
                    Text(
                        formatPhase(row.metric, value),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** Innstillinger: registrer/fjern fasteperioder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastingSettingsCard(vm: HealthViewModel) {
    var showAdd by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(
                stringResource(R.string.settings_fasting_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_fasting_description))
            Spacer(Modifier.height(10.dp))
            vm.fastingPeriods.forEach { period ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.fasting_period_row, period.start.format(shortDate), period.end.format(shortDate), period.days),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.removeFastingPeriod(period) }) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            }
            OutlinedButton(onClick = { showAdd = true }) {
                Text(stringResource(R.string.action_add_fasting_period))
            }
        }
    }

    if (showAdd) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        var daysText by remember { mutableStateOf("5") }
        val days = daysText.trim().toIntOrNull()?.takeIf { it in 1..60 }
        DatePickerDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = {
                TextButton(
                    enabled = days != null && pickerState.selectedDateMillis != null,
                    onClick = {
                        val start = Instant.ofEpochMilli(pickerState.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.addFastingPeriod(FastingPeriod(start, days!!))
                        showAdd = false
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            Column {
                DatePicker(state = pickerState, title = {
                    Text(stringResource(R.string.fasting_pick_start), modifier = Modifier.padding(start = 24.dp, top = 16.dp))
                })
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it },
                    label = { Text(stringResource(R.string.fasting_days_label)) },
                    singleLine = true,
                    isError = days == null,
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
