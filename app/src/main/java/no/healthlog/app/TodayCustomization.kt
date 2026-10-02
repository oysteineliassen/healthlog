package no.healthlog.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Kortene på I dag som kan skjules og flyttes - i standardrekkefølgen. Overskriften står alltid øverst. */
val DEFAULT_TODAY_CARD_ORDER = listOf(
    "weekly_summary", "monthly_summary", "summary", "diary", "readiness", "coach", "ai_coach",
    "score_goals", "weekly_activity", "heart_rate", "sleep_debt", "weight_trend", "metrics"
)

/**
 * Lagret rekkefølge, der kort som er nye siden sist (lagt til i en app-oppdatering)
 * settes inn på sin naturlige plass, og kort som ikke finnes lenger fjernes.
 */
fun resolveTodayCardOrder(saved: List<String>?): List<String> {
    val result = saved.orEmpty().filter { it in DEFAULT_TODAY_CARD_ORDER }.distinct().toMutableList()
    // Nye kort settes inn rett etter kortet de står etter i standardrekkefølgen (ikke
    // bakerst), slik at et nytt kort havner der det naturlig hører hjemme.
    DEFAULT_TODAY_CARD_ORDER.forEachIndexed { index, id ->
        if (id in result) return@forEachIndexed
        val previous = DEFAULT_TODAY_CARD_ORDER.take(index).lastOrNull { it in result }
        val insertAt = if (previous == null) 0 else result.indexOf(previous) + 1
        result.add(insertAt, id)
    }
    return result
}

@Composable
fun todayCardLabel(id: String): String = stringResource(
    when (id) {
        "weekly_summary" -> R.string.today_card_weekly_summary
        "monthly_summary" -> R.string.today_card_monthly_summary
        "summary" -> R.string.today_card_summary
        "diary" -> R.string.today_card_diary
        "readiness" -> R.string.today_card_readiness
        "coach" -> R.string.today_card_coach
        "ai_coach" -> R.string.today_card_ai_coach
        "score_goals" -> R.string.today_card_score_goals
        "weekly_activity" -> R.string.today_card_weekly_activity
        "heart_rate" -> R.string.today_card_heart_rate
        "sleep_debt" -> R.string.today_card_sleep_debt
        "weight_trend" -> R.string.today_card_weight_trend
        else -> R.string.today_card_metrics
    }
)

/**
 * "Tilpass I dag": avkrysning for å vise/skjule, piler for å flytte. Piler framfor
 * dra-og-slipp her, siden en liste i en dialog er trang å dra i og pilene er
 * forutsigbare å treffe.
 */
@Composable
fun TodayCustomizeDialog(
    order: List<String>,
    hidden: Set<String>,
    onChange: (List<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.today_customize_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.today_customize_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(order, key = { _, id -> id }) { index, id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = id !in hidden,
                                onCheckedChange = { show -> onChange(order, if (show) hidden - id else hidden + id) }
                            )
                            Text(todayCardLabel(id), modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    onChange(order.toMutableList().apply { add(index - 1, removeAt(index)) }, hidden)
                                },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up))
                            }
                            IconButton(
                                onClick = {
                                    onChange(order.toMutableList().apply { add(index + 1, removeAt(index)) }, hidden)
                                },
                                enabled = index < order.lastIndex
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            TextButton(onClick = { onChange(DEFAULT_TODAY_CARD_ORDER, emptySet()) }) {
                Text(stringResource(R.string.action_reset_default))
            }
        }
    )
}
