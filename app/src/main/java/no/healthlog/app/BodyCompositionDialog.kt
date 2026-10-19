package no.healthlog.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import no.healthlog.app.coach.rollingAverage
import no.healthlog.app.data.HealthDay
import no.healthlog.app.scale.BodyComposition
import no.healthlog.app.scale.ScaleProfileStore
import no.healthlog.app.scale.bodyComposition
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/** Hvor langt tilbake grafene i kroppssammensetningen går. */
private const val COMPOSITION_HISTORY_DAYS = 180L

/**
 * Kroppssammensetning - vises ved trykk på vektgrafen i Trender, så de mange tallene ikke
 * tar plass ellers. Fullskjerm med BMI-barometer, tallene for siste veiing og en egen graf
 * per måling. Høyden hentes fra vekt-profilen (Vei deg).
 */
@Composable
fun BodyCompositionDialog(days: List<HealthDay>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val heightCm = remember { ScaleProfileStore(context).load()?.heightCm }
    // Én sammensetning per dag med veiing, eldst først.
    val history = remember(days, heightCm) {
        val cutoff = LocalDate.now().minusDays(COMPOSITION_HISTORY_DAYS)
        days.mapNotNull { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
            val weight = day.weightKg
            if (date == null || weight == null || date.isBefore(cutoff)) null
            else date to bodyComposition(weight, day.bodyFatPercent, heightCm)
        }.sortedBy { it.first }
    }
    val latest = remember(days, heightCm) {
        days.filter { it.weightKg != null }.maxByOrNull { it.date }?.let {
            it.date to bodyComposition(it.weightKg!!, it.bodyFatPercent, heightCm)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.body_comp_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
                if (latest == null) {
                    Text(stringResource(R.string.body_comp_no_weight))
                } else {
                    LatestCard(latest.first, latest.second, heightCm)
                    CompositionCharts(history)
                }
            }
        }
    }
}

@Composable
private fun LatestCard(date: String, c: BodyComposition, heightCm: Int?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d.M.yyyy")) }.getOrDefault(date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            c.bmi?.let {
                BmiGauge(it)
                Spacer(Modifier.height(8.dp))
            }
            Row2(stringResource(R.string.body_comp_weight), "%.1f kg".format(c.weightKg))
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
}

@Composable
private fun CompositionChart(
    history: List<Pair<LocalDate, BodyComposition>>,
    title: String,
    unit: String,
    decimals: Int,
    padding: Float,
    value: (BodyComposition) -> Double?
) {
    val points = history.mapNotNull { (date, c) -> value(c)?.let { TrendPoint(date, it.toFloat()) } }
    if (points.size < 2) return
    val smoothed = rollingAverage(points.map { it.date to it.value.toDouble() })
        .map { (date, v) -> TrendPoint(date, v.toFloat()) }
    TrendCard(
        title = title,
        points = points,
        unit = unit,
        decimals = decimals,
        axisRange = AxisRange.Padded(padding),
        smoothed = smoothed
    )
}

/** En graf per måling, med 7-dagers snitt som trendlinje (som vekt og fettprosent i Trender). */
@Composable
private fun CompositionCharts(history: List<Pair<LocalDate, BodyComposition>>) {
    Text(
        stringResource(R.string.body_comp_charts_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp)
    )
    CompositionChart(history, stringResource(R.string.body_comp_weight), "kg", 1, 1f) { it.weightKg }
    CompositionChart(history, stringResource(R.string.body_comp_fat_percent), "%", 1, 1f) { it.fatPercent }
    CompositionChart(history, stringResource(R.string.body_comp_bmi), "", 1, 0.5f) { it.bmi }
    CompositionChart(history, stringResource(R.string.body_comp_fat_mass), "kg", 1, 1f) { it.fatMassKg }
    CompositionChart(history, stringResource(R.string.body_comp_muscle_mass) + " *", "kg", 1, 1f) { it.muscleMassKg }
    CompositionChart(history, stringResource(R.string.body_comp_lean_mass), "kg", 1, 1f) { it.leanMassKg }
    CompositionChart(history, stringResource(R.string.body_comp_water) + " *", "%", 1, 1f) { it.waterPercent }
    CompositionChart(history, stringResource(R.string.body_comp_protein) + " *", "kg", 1, 0.5f) { it.proteinKg }
    CompositionChart(history, stringResource(R.string.body_comp_bone) + " *", "kg", 2, 0.1f) { it.boneMassKg }
    CompositionChart(history, stringResource(R.string.body_comp_bmr) + " *", "kcal", 0, 20f) { it.bmrKcal?.toDouble() }
    if (history.count { it.second.fatPercent != null } < 2) {
        Text(
            stringResource(R.string.body_comp_charts_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- BMI-barometer

private const val GAUGE_MIN = 15f
private const val GAUGE_MAX = 35f

private class BmiZone(val from: Float, val to: Float, val color: Color, val label: Int)

// WHO-grensene. Blå/grønn/oransje/rød som i Huawei Health.
private val bmiZones = listOf(
    BmiZone(GAUGE_MIN, 18.5f, Color(0xFF4A90E2), R.string.bmi_zone_low),
    BmiZone(18.5f, 25f, Color(0xFF43A047), R.string.bmi_zone_normal),
    BmiZone(25f, 30f, Color(0xFFFB8C00), R.string.bmi_zone_over),
    BmiZone(30f, GAUGE_MAX, Color(0xFFE53935), R.string.bmi_zone_obese),
)

/** Halvsirkel fra 15 til 35 med fire soner og en markør på [bmi]. */
@Composable
fun BmiGauge(bmi: Double, modifier: Modifier = Modifier) {
    val zone = bmiZones.firstOrNull { bmi < it.to } ?: bmiZones.last()
    val markerColor = MaterialTheme.colorScheme.onSurface

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.8f).aspectRatio(2f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.width * 0.07f
                val radius = (size.width - stroke) / 2f
                val center = Offset(size.width / 2f, size.height - stroke / 2f)
                val arcSize = Size(radius * 2, radius * 2)
                val topLeft = Offset(center.x - radius, center.y - radius)
                fun angleOf(v: Float) = 180f + 180f * ((v - GAUGE_MIN) / (GAUGE_MAX - GAUGE_MIN))
                // Litt luft mellom sonene, så de leses som egne felt.
                val gap = 1.2f
                bmiZones.forEach { z ->
                    drawArc(
                        z.color,
                        startAngle = angleOf(z.from) + gap / 2,
                        sweepAngle = angleOf(z.to) - angleOf(z.from) - gap,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt)
                    )
                }
                // Pil på innsiden av buen med spissen inntil kanten, så den ikke dekker
                // fargene og man ser nøyaktig om verdien er på, over eller under en grense.
                val a = Math.toRadians(angleOf(bmi.toFloat().coerceIn(GAUGE_MIN, GAUGE_MAX)).toDouble())
                val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                val side = Offset(-dir.y, dir.x)
                val tipR = radius - stroke / 2f - 2.dp.toPx()
                val length = stroke * 0.9f
                val halfWidth = stroke * 0.45f
                val tip = center + dir * tipR
                val base = center + dir * (tipR - length)
                val arrow = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(base.x + side.x * halfWidth, base.y + side.y * halfWidth)
                    lineTo(base.x - side.x * halfWidth, base.y - side.y * halfWidth)
                    close()
                }
                drawPath(arrow, markerColor)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("BMI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%.1f".format(bmi), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(zone.label), color = zone.color, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            bmiZones.forEach { z ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(z.color) }
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(z.label), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            stringResource(R.string.bmi_zone_limits),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
