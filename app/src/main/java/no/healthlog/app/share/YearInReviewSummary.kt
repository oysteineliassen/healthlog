package no.healthlog.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import no.healthlog.app.R
import no.healthlog.app.coach.YearInReviewData
import java.io.File
import java.io.FileOutputStream

/**
 * Samme data som YearInReviewCard viser i appen, bygget som ClinicalRow/ClinicalSection
 * slik at den kan gå gjennom samme rendering som resten av del-funksjonene i appen.
 * trainingTypeText sendes inn ferdig oversatt fra UI-laget, siden selve
 * treningstype-oversettelsen (trainingTypeLabel) er @Composable og ikke kan kalles her.
 */
fun buildYearInReviewSections(context: Context, data: YearInReviewData, trainingTypeText: String?): List<ClinicalSection> {
    val rows = listOfNotNull(
        ClinicalRow(context.getString(R.string.year_review_days_logged), data.daysLogged.toString()),
        ClinicalRow(context.getString(R.string.year_review_total_steps), "%,d".format(data.totalSteps)),
        data.avgSteps?.let { ClinicalRow(context.getString(R.string.year_review_avg_steps), "%,d".format(it.toLong())) },
        data.avgSleepHours?.let { ClinicalRow(context.getString(R.string.year_review_avg_sleep), "%.1f t".format(it)) },
        data.avgScore?.let { ClinicalRow(context.getString(R.string.year_review_avg_score), "%.0f".format(it)) },
        ClinicalRow(context.getString(R.string.year_review_total_training), "${data.totalTrainingMinutes} min"),
        trainingTypeText?.let { ClinicalRow(context.getString(R.string.year_review_most_common_training), it) },
        data.highestHrv?.let { ClinicalRow(context.getString(R.string.year_review_highest_hrv), "${it.toInt()} ms") },
        data.lowestRestingHr?.let { ClinicalRow(context.getString(R.string.year_review_lowest_resting_hr), "$it bpm") },
        if (data.longestStepsStreak >= 1) {
            ClinicalRow(context.getString(R.string.year_review_longest_steps_streak), context.getString(R.string.record_days_format, data.longestStepsStreak))
        } else {
            null
        }
    )

    return listOf(ClinicalSection(context.getString(R.string.year_review_title, data.year), rows))
}

fun createYearInReviewImageFile(context: Context, data: YearInReviewData, trainingTypeText: String?, title: String): File {
    val sections = buildYearInReviewSections(context, data, trainingTypeText)
    val height = calculateClinicalHeight(sections)

    val bitmap = Bitmap.createBitmap(CLINICAL_WIDTH, height, Bitmap.Config.ARGB_8888)
    renderClinicalSummary(context, Canvas(bitmap), title, "", data.daysLogged, sections)

    val file = File(sharedDir(context), "healthlog-arsoversikt-${data.year}.png")
    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    return file
}

fun createYearInReviewPdfFile(context: Context, data: YearInReviewData, trainingTypeText: String?, title: String): File {
    val sections = buildYearInReviewSections(context, data, trainingTypeText)
    val height = calculateClinicalHeight(sections)

    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(CLINICAL_WIDTH, height, 1).create())
    renderClinicalSummary(context, page.canvas, title, "", data.daysLogged, sections)
    document.finishPage(page)

    val file = File(sharedDir(context), "healthlog-arsoversikt-${data.year}.pdf")
    FileOutputStream(file).use { out -> document.writeTo(out) }
    document.close()
    return file
}
