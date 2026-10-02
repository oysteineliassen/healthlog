package no.healthlog.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import no.healthlog.app.R
import no.healthlog.app.data.HealthDay
import java.io.File
import java.io.FileOutputStream

private const val SUMMARY_WIDTH = 800

fun createSummaryImageFile(
    context: Context,
    days: List<HealthDay>,
    title: String,
    periodLabel: String
): File {
    val rows = buildSummaryRows(context, days)
    val height = calculateSummaryHeight(rows.size)

    val bitmap = Bitmap.createBitmap(SUMMARY_WIDTH, height, Bitmap.Config.ARGB_8888)
    renderSummary(context, Canvas(bitmap), SUMMARY_WIDTH, title, periodLabel, rows)

    val file = File(sharedDir(context), "healthlog-sammendrag.png")
    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    return file
}

fun createSummaryPdfFile(
    context: Context,
    days: List<HealthDay>,
    title: String,
    periodLabel: String
): File {
    val rows = buildSummaryRows(context, days)
    val height = calculateSummaryHeight(rows.size)

    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(SUMMARY_WIDTH, height, 1).create())
    renderSummary(context, page.canvas, SUMMARY_WIDTH, title, periodLabel, rows)
    document.finishPage(page)

    val file = File(sharedDir(context), "healthlog-sammendrag.pdf")
    FileOutputStream(file).use { out -> document.writeTo(out) }
    document.close()
    return file
}

fun createClinicalPdfFile(
    context: Context,
    days: List<HealthDay>,
    title: String,
    periodLabel: String
): File {
    val sections = buildClinicalSections(context, days)
    val height = calculateClinicalHeight(sections)

    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(CLINICAL_WIDTH, height, 1).create())
    renderClinicalSummary(context, page.canvas, title, periodLabel, days.size, sections)
    document.finishPage(page)

    val file = File(sharedDir(context), "healthlog-klinisk-sammendrag.pdf")
    FileOutputStream(file).use { out -> document.writeTo(out) }
    document.close()
    return file
}

fun createRecordsImageFile(context: Context, days: List<HealthDay>, title: String, periodLabel: String): File {
    val sections = buildRecordsSections(context, days)
    val height = calculateClinicalHeight(sections)

    val bitmap = Bitmap.createBitmap(CLINICAL_WIDTH, height, Bitmap.Config.ARGB_8888)
    renderClinicalSummary(context, Canvas(bitmap), title, periodLabel, days.size, sections)

    val file = File(sharedDir(context), "healthlog-rekorder.png")
    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    return file
}

fun createRecordsPdfFile(context: Context, days: List<HealthDay>, title: String, periodLabel: String): File {
    val sections = buildRecordsSections(context, days)
    val height = calculateClinicalHeight(sections)

    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(CLINICAL_WIDTH, height, 1).create())
    renderClinicalSummary(context, page.canvas, title, periodLabel, days.size, sections)
    document.finishPage(page)

    val file = File(sharedDir(context), "healthlog-rekorder.pdf")
    FileOutputStream(file).use { out -> document.writeTo(out) }
    document.close()
    return file
}

fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, context.getString(R.string.share_chooser_title)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

internal fun sharedDir(context: Context): File =
    File(context.cacheDir, "shared").apply { mkdirs() }
