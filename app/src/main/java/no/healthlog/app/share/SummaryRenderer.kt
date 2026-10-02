package no.healthlog.app.share

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import no.healthlog.app.R
import no.healthlog.app.data.HealthDay

data class SummaryRow(val label: String, val value: String)

/**
 * Bygger radene i sammendraget: endring for målinger det gir mening å se utvikling på
 * (vekt, midjemål), snitt for de øvrige. Rader utelates helt hvis det ikke finnes nok
 * data til å regne dem ut, i stedet for å vise "–".
 */
fun buildSummaryRows(context: Context, days: List<HealthDay>): List<SummaryRow> {
    fun average(selector: (HealthDay) -> Number?): Double? {
        val values = days.mapNotNull { selector(it)?.toDouble() }
        return if (values.isEmpty()) null else values.average()
    }

    fun change(selector: (HealthDay) -> Number?): Double? {
        val sorted = days.sortedBy { it.date }
        val values = sorted.mapNotNull { selector(it)?.toDouble() }
        return if (values.size < 2) null else values.last() - values.first()
    }

    val rows = mutableListOf<SummaryRow>()

    change { it.weightKg }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_weight_change), "%+.1f kg".format(it)))
    }
    change { it.waistCm }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_waist_change), "%+.1f cm".format(it)))
    }
    average { it.hrvMs }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_hrv_avg), "%.0f ms".format(it)))
    }
    average { it.restingHeartRate }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_resting_hr_avg), "%.0f bpm".format(it)))
    }
    average { it.sleepMinutes?.let { minutes -> minutes / 60.0 } }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_sleep_avg), "%.1f timer".format(it)))
    }
    average { it.sleepScore }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_sleep_score_avg), "%.0f poeng".format(it)))
    }
    average { it.bodyBatteryMax }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_body_battery_high_avg), "%.0f".format(it)))
    }
    average { it.bodyBatteryMin }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_body_battery_low_avg), "%.0f".format(it)))
    }
    average { it.stress }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_stress_avg), "%.0f poeng".format(it)))
    }
    average { it.steps }?.let {
        rows.add(SummaryRow(context.getString(R.string.summary_row_steps_avg), "%,.0f".format(it)))
    }

    return rows
}

private const val MARGIN = 48f
private const val ROW_HEIGHT = 56f

/** Regner ut hvor høy bitmapen/PDF-siden må være for at alle radene skal få plass. */
fun calculateSummaryHeight(rowCount: Int): Int =
    (170f + rowCount * ROW_HEIGHT + 70f).toInt()

/**
 * Tegner selve rapporten med vanlig android.graphics.Canvas - fungerer identisk om
 * canvasen kommer fra en Bitmap (PNG-eksport) eller fra en PdfDocument.Page (PDF-eksport),
 * så denne funksjonen trenger ikke vite hvilket format den til slutt havner i.
 */
fun renderSummary(context: Context, canvas: Canvas, width: Int, title: String, periodLabel: String, rows: List<SummaryRow>) {
    canvas.drawColor(Color.WHITE)

    val titlePaint = Paint().apply {
        color = Color.parseColor("#188A80")
        textSize = 42f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val periodPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 26f
        isAntiAlias = true
    }
    val labelPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 28f
        isAntiAlias = true
    }
    val valuePaint = Paint().apply {
        color = Color.BLACK
        textSize = 28f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    val linePaint = Paint().apply {
        color = Color.parseColor("#DDDDDD")
        strokeWidth = 2f
    }
    val footerPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 20f
        isAntiAlias = true
    }

    var y = 60f
    canvas.drawText(title, MARGIN, y, titlePaint)
    y += 40f
    canvas.drawText(periodLabel, MARGIN, y, periodPaint)
    y += 40f
    canvas.drawLine(MARGIN, y, width - MARGIN, y, linePaint)
    y += 50f

    if (rows.isEmpty()) {
        canvas.drawText(context.getString(R.string.summary_no_data), MARGIN, y, labelPaint)
        y += ROW_HEIGHT
    } else {
        rows.forEach { row ->
            canvas.drawText(row.label, MARGIN, y, labelPaint)
            canvas.drawText(row.value, width - MARGIN, y, valuePaint)
            y += ROW_HEIGHT
            canvas.drawLine(MARGIN, y - 36f, width - MARGIN, y - 36f, linePaint)
        }
    }

    y += 20f
    canvas.drawText(context.getString(R.string.summary_footer), MARGIN, y, footerPaint)
}
