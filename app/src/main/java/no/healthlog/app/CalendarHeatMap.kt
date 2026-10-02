package no.healthlog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val heatMapMonthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", norwegianLocale)
private val heatMapWeekdayLabels = listOf("M", "T", "O", "T", "F", "L", "S")

/** Bygger en 7-kolonners rutenett-liste for [month] - null representerer tomme celler før/etter selve måneden. */
private fun buildMonthGrid(month: YearMonth): List<LocalDate?> {
    val firstDay = month.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value - 1 // mandag = 1 -> 0 tomme celler foran
    val cells = mutableListOf<LocalDate?>()
    repeat(leadingBlanks) { cells.add(null) }
    for (day in 1..month.lengthOfMonth()) {
        cells.add(month.atDay(day))
    }
    while (cells.size % 7 != 0) cells.add(null)
    return cells
}

@Composable
private fun heatMapCellColor(score: Int?): Color {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    return when {
        score == null -> empty
        score < 40 -> base.copy(alpha = 0.2f)
        score < 60 -> base.copy(alpha = 0.4f)
        score < 80 -> base.copy(alpha = 0.65f)
        else -> base.copy(alpha = 1f)
    }
}

/** Hvite tall leses best på de mer mettede tealfargede cellene; mørkere tall ellers. */
@Composable
private fun heatMapTextColor(score: Int?): Color =
    if (score != null && score >= 60) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Månedsoversikt over registreringer, fargelagt etter helse-scoren den dagen - jo
 * fyldigere teal-farge, jo bedre score. Grå/tom celle betyr ingen registrering den
 * dagen. Uavhengig av eventuelt tagg-filter i selve historikklisten, siden dette skal
 * være et helhetsbilde, ikke et filtrert utsnitt.
 */
@Composable
fun CalendarHeatMap(days: List<HealthDay>, onDayClick: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val sleepGoalMinutes = GoalPreferences.getSleepGoalMinutes(context)
    val stepsGoal = GoalPreferences.getStepsGoal(context)

    var displayedMonth by rememberSaveable { mutableStateOf(YearMonth.now()) }

    val byDate = remember(days) {
        days.mapNotNull { day ->
            runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day }
        }.toMap()
    }
    val cells = remember(displayedMonth) { buildMonthGrid(displayedMonth) }
    val monthLabel = displayedMonth.atDay(1)
        .format(heatMapMonthFormatter)
        .replaceFirstChar { it.titlecase(norwegianLocale) }

    val previousMonthDescription = stringResource(R.string.heat_map_previous_month)
    val nextMonthDescription = stringResource(R.string.heat_map_next_month)

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { displayedMonth = displayedMonth.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = previousMonthDescription)
                }

                Text(monthLabel, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

                IconButton(onClick = { displayedMonth = displayedMonth.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = nextMonthDescription)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth()) {
                heatMapWeekdayLabels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                        ) {
                            if (date != null) {
                                val healthDay = byDate[date]
                                val score = healthDay?.let { calculateHealthScore(it, sleepGoalMinutes, stepsGoal) }
                                val cellDescription = if (healthDay != null) {
                                    stringResource(
                                        R.string.heat_map_cell_description,
                                        date.format(dateFormatter),
                                        score ?: 0
                                    )
                                } else {
                                    stringResource(R.string.heat_map_cell_description_empty, date.format(dateFormatter))
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(heatMapCellColor(score))
                                        .clickable { onDayClick(date) }
                                        .semantics { contentDescription = cellDescription }
                                ) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        color = heatMapTextColor(score),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
