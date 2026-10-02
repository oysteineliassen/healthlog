package no.healthlog.app.share

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import no.healthlog.app.R
import no.healthlog.app.data.HealthDay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ClinicalRow(val label: String, val value: String)
data class ClinicalSection(val title: String, val rows: List<ClinicalRow>)

const val CLINICAL_WIDTH = 850
private const val CLINICAL_MARGIN = 56f
private const val CLINICAL_ROW_HEIGHT = 34f
private const val CLINICAL_SECTION_GAP = 28f
private const val CLINICAL_SECTION_HEADER_HEIGHT = 44f

/**
 * Snitt for perioden, gruppert i seksjoner som ligner mer på et vanlig helsedokument
 * enn appens egen fargerike stil - ment for utskrift eller visning til lege. En seksjon
 * utelates helt hvis ingen av dens felt har data i perioden.
 */
fun buildClinicalSections(context: Context, days: List<HealthDay>): List<ClinicalSection> {
    fun average(selector: (HealthDay) -> Number?): Double? {
        val values = days.mapNotNull { selector(it)?.toDouble() }
        return if (values.isEmpty()) null else values.average()
    }

    fun row(labelRes: Int, value: Double?, format: String): ClinicalRow? =
        value?.let { ClinicalRow(context.getString(labelRes), format.format(it)) }

    val sections = mutableListOf<ClinicalSection>()

    val vitals = listOfNotNull(
        row(R.string.clinical_row_weight, average { it.weightKg }, "%.1f kg"),
        row(R.string.clinical_row_waist, average { it.waistCm }, "%.1f cm"),
        row(R.string.clinical_row_resting_hr, average { it.restingHeartRate }, "%.0f bpm"),
        row(R.string.clinical_row_hrv, average { it.hrvMs }, "%.0f ms")
    )
    if (vitals.isNotEmpty()) {
        sections.add(ClinicalSection(context.getString(R.string.clinical_section_vitals), vitals))
    }

    val sleep = listOfNotNull(
        row(R.string.clinical_row_sleep_duration, average { it.sleepMinutes?.let { m -> m / 60.0 } }, "%.1f timer"),
        row(R.string.clinical_row_sleep_score, average { it.sleepScore }, "%.0f")
    )
    if (sleep.isNotEmpty()) {
        sections.add(ClinicalSection(context.getString(R.string.clinical_section_sleep), sleep))
    }

    val activity = listOfNotNull(
        average { it.steps }?.let { ClinicalRow(context.getString(R.string.clinical_row_steps_avg), "%,.0f".format(it)) },
        average { it.trainingMinutes }?.let { ClinicalRow(context.getString(R.string.clinical_row_training_avg), "%.0f min".format(it)) }
    )
    if (activity.isNotEmpty()) {
        sections.add(ClinicalSection(context.getString(R.string.clinical_section_activity), activity))
    }

    val wellbeing = listOfNotNull(
        row(R.string.clinical_row_stress, average { it.stress }, "%.0f"),
        row(R.string.clinical_row_body_battery_high, average { it.bodyBatteryMax }, "%.0f"),
        row(R.string.clinical_row_body_battery_low, average { it.bodyBatteryMin }, "%.0f")
    )
    if (wellbeing.isNotEmpty()) {
        sections.add(ClinicalSection(context.getString(R.string.clinical_section_wellbeing), wellbeing))
    }

    return sections
}

fun calculateClinicalHeight(sections: List<ClinicalSection>): Int {
    val totalRows = sections.sumOf { it.rows.size }
    return (170f +
        sections.size * CLINICAL_SECTION_HEADER_HEIGHT +
        totalRows * CLINICAL_ROW_HEIGHT +
        sections.size * CLINICAL_SECTION_GAP +
        60f).toInt()
}

/** Rent svart-hvitt, ingen branding eller farger - kun struktur, seksjonstitler og tall. */
fun renderClinicalSummary(
    context: Context,
    canvas: Canvas,
    title: String,
    periodLabel: String,
    dayCount: Int,
    sections: List<ClinicalSection>
) {
    canvas.drawColor(Color.WHITE)

    val titlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 34f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val metaPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 22f
        isAntiAlias = true
    }
    val sectionPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val labelPaint = Paint().apply {
        color = Color.BLACK
        textSize = 22f
        isAntiAlias = true
    }
    val valuePaint = Paint().apply {
        color = Color.BLACK
        textSize = 22f
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    val linePaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 1.5f
    }
    val footerPaint = Paint().apply {
        color = Color.GRAY
        textSize = 16f
        isAntiAlias = true
    }

    var y = 56f

    canvas.drawText(title, CLINICAL_MARGIN, y, titlePaint)
    y += 34f
    canvas.drawText(periodLabel, CLINICAL_MARGIN, y, metaPaint)
    y += 26f
    canvas.drawText(context.getString(R.string.clinical_entry_count, dayCount), CLINICAL_MARGIN, y, metaPaint)
    y += 20f
    canvas.drawLine(CLINICAL_MARGIN, y, CLINICAL_WIDTH - CLINICAL_MARGIN, y, linePaint)
    y += 40f

    if (sections.isEmpty()) {
        canvas.drawText(context.getString(R.string.summary_no_data), CLINICAL_MARGIN, y, labelPaint)
        y += CLINICAL_ROW_HEIGHT
    } else {
        sections.forEach { section ->
            canvas.drawText(section.title, CLINICAL_MARGIN, y, sectionPaint)
            y += 30f
            section.rows.forEach { row ->
                canvas.drawText(row.label, CLINICAL_MARGIN, y, labelPaint)
                canvas.drawText(row.value, CLINICAL_WIDTH - CLINICAL_MARGIN, y, valuePaint)
                y += CLINICAL_ROW_HEIGHT
                canvas.drawLine(CLINICAL_MARGIN, y - 24f, CLINICAL_WIDTH - CLINICAL_MARGIN, y - 24f, linePaint)
            }
            y += CLINICAL_SECTION_GAP
        }
    }

    val generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    canvas.drawText(context.getString(R.string.clinical_footer, generatedAt), CLINICAL_MARGIN, y, footerPaint)
}
