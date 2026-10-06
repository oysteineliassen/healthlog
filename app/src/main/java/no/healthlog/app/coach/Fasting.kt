package no.healthlog.app.coach

import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** En planlagt eller gjennomført fasteperiode (f.eks. 5-dagers fasteimiterende diett). */
data class FastingPeriod(val start: LocalDate, val days: Int) {
    val end: LocalDate get() = start.plusDays(days.toLong() - 1)
    operator fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(end)
    fun dates(): List<LocalDate> = (0 until days).map { start.plusDays(it.toLong()) }
}

/** Lagres som "2026-10-12:5,2026-11-20:3". Ugyldige oppføringer hoppes over. */
fun parseFastingPeriods(text: String?): List<FastingPeriod> =
    text.orEmpty().split(",").mapNotNull { entry ->
        val parts = entry.trim().split(":")
        if (parts.size != 2) return@mapNotNull null
        val start = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
        val days = parts[1].toIntOrNull()?.takeIf { it in 1..60 } ?: return@mapNotNull null
        FastingPeriod(start, days)
    }.sortedBy { it.start }

fun List<FastingPeriod>.serialize(): String = sortedBy { it.start }.joinToString(",") { "${it.start}:${it.days}" }

/**
 * Perioden som er aktuell å vise på I dag: pågående, starter innen 14 dager, eller
 * sluttet for under 14 dager siden. Nærmeste vinner.
 */
fun relevantFastingPeriod(periods: List<FastingPeriod>, today: LocalDate): FastingPeriod? =
    periods
        .filter { !today.isBefore(it.start.minusDays(14)) && !today.isAfter(it.end.plusDays(14)) }
        .minByOrNull { p -> if (today in p) 0L else minOf(abs(ChronoUnit.DAYS.between(today, p.start)), abs(ChronoUnit.DAYS.between(today, p.end))) }

private fun abs(v: Long) = if (v < 0) -v else v

enum class FastingMetric(val nightly: Boolean) {
    HRV(true), RESTING_HR(true), SLEEP_HOURS(true), STRESS(false), BODY_BATTERY_MAX(false), WEIGHT(false)
}

private fun FastingMetric.valueOf(day: HealthDay): Double? = when (this) {
    FastingMetric.HRV -> day.hrvMs
    FastingMetric.RESTING_HR -> day.restingHeartRate?.toDouble()
    FastingMetric.SLEEP_HOURS -> day.sleepMinutes?.let { it / 60.0 }
    FastingMetric.STRESS -> day.stress?.toDouble()
    FastingMetric.BODY_BATTERY_MAX -> day.bodyBatteryMax?.toDouble()
    FastingMetric.WEIGHT -> day.weightKg
}

/** Snitt (og antall dager bak) for uka før, under fasten og uka etter. null = ingen data. */
data class FastingPhaseValue(val average: Double, val days: Int)

data class FastingComparisonRow(
    val metric: FastingMetric,
    val before: FastingPhaseValue?,
    val during: FastingPhaseValue?,
    val after: FastingPhaseValue?
)

/**
 * Sammenligner målingene uka før, under og uka etter [period], bare med dager fram til
 * [today]. Nattlige målinger (HRV, hvilepuls, søvn) lagres på dagen natten slutter,
 * så de forskyves én dag: nettene ETTER hver fastedag regnes som "under".
 */
fun fastingComparison(allDays: List<HealthDay>, period: FastingPeriod, today: LocalDate): List<FastingComparisonRow> {
    val byDate = allDays.mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }.toMap()
    fun phase(metric: FastingMetric, from: LocalDate, to: LocalDate): FastingPhaseValue? {
        val shift = if (metric.nightly) 1L else 0L
        val values = generateSequence(from.plusDays(shift)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to.plusDays(shift)) && !it.isAfter(today) }
            .mapNotNull { byDate[it]?.let(metric::valueOf) }
            .toList()
        return if (values.isEmpty()) null else FastingPhaseValue(values.average(), values.size)
    }
    return FastingMetric.entries.map { m ->
        FastingComparisonRow(
            m,
            before = phase(m, period.start.minusDays(7), period.start.minusDays(1)),
            during = phase(m, period.start, period.end),
            after = phase(m, period.end.plusDays(1), period.end.plusDays(7))
        )
    }
}

private fun FastingMetric.format(v: Double): String = when (this) {
    FastingMetric.SLEEP_HOURS, FastingMetric.WEIGHT -> String.format(java.util.Locale.ROOT, "%.1f", v)
    else -> String.format(java.util.Locale.ROOT, "%.0f", v)
}

/**
 * Tekstrapport for en faste til deling: én linje per måling med før/under/etter og
 * endringen fra før til under. [label] gir målingens navn på brukerens språk, [title]
 * og [columns] (før/under/etter) likeså.
 */
fun fastingReportText(
    period: FastingPeriod,
    rows: List<FastingComparisonRow>,
    title: String,
    columns: Triple<String, String, String>,
    label: (FastingMetric) -> String
): String {
    val sb = StringBuilder()
    sb.appendLine("$title: ${period.start} – ${period.end} (${period.days})")
    sb.appendLine()
    rows.filter { it.before != null || it.during != null || it.after != null }.forEach { row ->
        fun v(p: FastingPhaseValue?) = p?.let { row.metric.format(it.average) } ?: "–"
        val change = if (row.before != null && row.during != null) {
            val d = row.during.average - row.before.average
            " (" + (if (d >= 0) "+" else "") + row.metric.format(d) + ")"
        } else ""
        sb.appendLine("${label(row.metric)}: ${columns.first} ${v(row.before)} · ${columns.second} ${v(row.during)}$change · ${columns.third} ${v(row.after)}")
    }
    return sb.toString().trimEnd()
}

/** Prompt for en egen AI-gjennomgang av fasten, med dagsdata fra uka før til uka etter. */
fun buildFastingAiPrompt(allDays: List<HealthDay>, period: FastingPeriod, today: LocalDate, report: String): String {
    val from = period.start.minusDays(7)
    val to = minOf(period.end.plusDays(8), today)
    val days = allDays.filter { d ->
        runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { !it.isBefore(from) && !it.isAfter(to) } == true
    }.sortedBy { it.date }
    val sb = StringBuilder()
    sb.appendLine("Jeg har gjennomført en faste (fasteimiterende diett) ${period.start} – ${period.end} (${period.days} dager). Under er målinger fra Garmin-klokka mi fra uka før til uka etter, eksportert fra appen HealthLog.")
    sb.appendLine()
    sb.appendLine("Vurder hvordan kroppen responderte (HRV, hvilepuls, søvn, stress, Body Battery, vekt), hvor raskt jeg kom tilbake til normalen, og hva jeg bør tenke på neste gang. Søvn, HRV og hvilepuls står på datoen natten slutter. Ikke still medisinske diagnoser. Svar på norsk, under 350 ord, med overskriftene:")
    listOf("Kort oppsummering", "Under fasten", "Etter fasten", "Til neste gang").forEach { sb.appendLine("## $it") }
    sb.appendLine()
    sb.appendLine("OPPSUMMERING:")
    sb.appendLine(report)
    sb.appendLine()
    sb.appendLine("DAGSDATA (dato;faste;søvn_t;hrv_ms;hvilepuls;stress;bb_maks;vekt_kg;notat):")
    days.forEach { d ->
        val date = LocalDate.parse(d.date)
        val note = listOfNotNull(d.sleepReflection, d.eveningNote).joinToString(" | ").replace(Regex("\\s+"), " ").replace(";", ",")
        sb.appendLine(listOf(
            d.date,
            if (date in period) "ja" else "",
            d.sleepMinutes?.let { String.format(java.util.Locale.ROOT, "%.2f", it / 60.0) } ?: "",
            d.hrvMs?.let { String.format(java.util.Locale.ROOT, "%.0f", it) } ?: "",
            d.restingHeartRate?.toString() ?: "",
            d.stress?.toString() ?: "",
            d.bodyBatteryMax?.toString() ?: "",
            d.weightKg?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "",
            note
        ).joinToString(";"))
    }
    return sb.toString().trimEnd()
}
