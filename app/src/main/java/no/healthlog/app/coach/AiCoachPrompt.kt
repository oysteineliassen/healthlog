package no.healthlog.app.coach

import no.healthlog.app.data.HealthDay
import no.healthlog.app.data.habitTagSet
import java.time.LocalDate
import java.util.Locale

/*
 * "Ekte" AI-trener uten egen AI-integrasjon: appen lager en ferdig prompt med dataene
 * dine som du limer inn i Claude, ChatGPT, Copilot o.l., og svaret limes tilbake og
 * vises på I dag. Ingenting sendes fra appen selv - du ser og bestemmer hva som deles.
 */

/** Overskriftene AI-en blir bedt om å bruke - samme rekkefølge vises i appen. */
val AI_COACH_SECTIONS = listOf(
    "Kort oppsummering",
    "Søvn",
    "Restitusjon og stress",
    "Trening",
    "Mønstre jeg bør vite om",
    "Tre konkrete råd for neste uke"
)

data class AiCoachGoals(
    val sleepGoalMinutes: Int,
    val stepsGoal: Int,
    val trainingGoalMinutes: Int,
    val weightGoalKg: Double?
)

private fun Double.fmt(decimals: Int = 1) = String.format(Locale.ROOT, "%.${decimals}f", this)

/** Fjerner linjeskift og skilletegn fra fritekst, så én dag holder seg på én linje. */
private fun clean(text: String) = text.replace(Regex("\\s+"), " ").replace(";", ",").trim()

fun buildAiCoachPrompt(
    days: List<HealthDay>,
    today: LocalDate,
    goals: AiCoachGoals,
    periodDays: Int = 28
): String {
    val from = today.minusDays(periodDays.toLong() - 1)
    val period = days
        .filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { d -> !d.isBefore(from) && !d.isAfter(today) } == true }
        .sortedBy { it.date }

    val sb = StringBuilder()
    sb.appendLine("Du er en erfaren trener og coach innen søvn, restitusjon og helse. Under er mine helsedata fra de siste $periodDays dagene, eksportert fra appen HealthLog (målinger fra Garmin-klokke via Health Connect og Garmin-filer, pluss egne notater).")
    sb.appendLine()
    sb.appendLine("Analyser dataene og gi meg personlige, konkrete innsikter basert på MINE tall - ikke generelle råd. Pek på sammenhenger du ser (f.eks. hva som går foran gode og dårlige netter), og si fra hvis datagrunnlaget er for tynt til å konkludere. Ikke still medisinske diagnoser.")
    sb.appendLine()
    sb.appendLine("Mine mål:")
    sb.appendLine("- Søvn: ${(goals.sleepGoalMinutes / 60.0).fmt()} timer per natt")
    sb.appendLine("- Skritt: ${goals.stepsGoal} per dag")
    sb.appendLine("- Trening: ${goals.trainingGoalMinutes} minutter per uke")
    goals.weightGoalKg?.let { sb.appendLine("- Vekt: ${it.fmt()} kg") }
    sb.appendLine()
    sb.appendLine("Om dataene:")
    sb.appendLine("- Søvn, søvnscore, søvnfaser, HRV og hvilepuls står på datoen natten SLUTTER (altså natten før datoen).")
    sb.appendLine("- Stress og Body Battery (bb) er Garmins skala 0-100. Tom verdi = ikke målt.")
    sb.appendLine("- Dagtype: syk/reise/hviledag. Kosthold: light/moderate/heavy/fasting (fasting = faste eller fasteimiterende diett). Vaner er egne merker (f.eks. alkohol, sen middag).")
    sb.appendLine()
    sb.appendLine("Svar på norsk, under 450 ord, med nøyaktig disse overskriftene (markdown, ##):")
    AI_COACH_SECTIONS.forEach { sb.appendLine("## $it") }
    sb.appendLine()
    sb.appendLine("DATA (semikolonseparert):")
    sb.appendLine("dato;søvn_t;søvnscore;dyp_min;rem_min;våken_min;hrv_ms;hvilepuls;stress;bb_maks;bb_min;skritt;trening_min;treningstype;intensitet;dagtype;kosthold;vaner;vekt_kg")
    if (period.isEmpty()) {
        sb.appendLine("(ingen registrerte dager i perioden)")
    }
    period.forEach { d ->
        sb.appendLine(
            listOf(
                d.date,
                d.sleepMinutes?.let { (it / 60.0).fmt(2) },
                d.sleepScore,
                d.deepSleepMinutes,
                d.remSleepMinutes,
                d.sleepAwakeMinutes,
                d.hrvMs?.fmt(0),
                d.restingHeartRate,
                d.stress,
                d.bodyBatteryMax,
                d.bodyBatteryMin,
                d.steps,
                d.trainingMinutes,
                d.trainingType,
                d.trainingIntensity,
                d.dayTag,
                d.nutritionLevel,
                d.habitTagSet().sorted().joinToString(",").ifEmpty { null },
                d.weightKg?.fmt()
            ).joinToString(";") { it?.toString()?.let(::clean) ?: "" }
        )
    }

    val notes = period.mapNotNull { d ->
        val parts = listOfNotNull(
            d.sleepReflection?.takeIf { it.isNotBlank() }?.let { "Natten: ${clean(it)}" },
            d.morningNote?.takeIf { it.isNotBlank() }?.let { "Plan: ${clean(it)}" },
            d.eveningNote?.takeIf { it.isNotBlank() }?.let { "Kveld: ${clean(it)}" }
        )
        if (parts.isEmpty()) null else "${d.date}: ${parts.joinToString(" | ")}"
    }
    if (notes.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("MINE NOTATER (inkludert refleksjoner om hvorfor natten ble bra/dårlig):")
        notes.forEach { sb.appendLine(it) }
    }
    return sb.toString().trimEnd()
}

/** Én blokk i et AI-svar, for enkel visning i appen. */
sealed class AiAnswerBlock {
    data class Heading(val text: String) : AiAnswerBlock()
    data class Bullet(val text: String) : AiAnswerBlock()
    data class Paragraph(val text: String) : AiAnswerBlock()
}

/**
 * Leser et svar i markdown-lignende format (overskrifter, punktlister, avsnitt). Fet/
 * kursiv-markering fjernes - appen viser ren tekst. Tåler svar som ikke følger malen.
 */
fun parseAiAnswer(answer: String): List<AiAnswerBlock> {
    fun strip(s: String) = s.replace("**", "").replace("__", "").replace(Regex("(?<![\\w*])\\*(?!\\s)([^*]+)\\*"), "$1").trim()
    val blocks = mutableListOf<AiAnswerBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += AiAnswerBlock.Paragraph(strip(paragraph.toString()))
        paragraph.clear()
    }
    for (raw in answer.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> { flush(); blocks += AiAnswerBlock.Heading(strip(line.trimStart('#'))) }
            Regex("^([-*•]|\\d+[.)])\\s+.*").matches(line) -> {
                flush()
                blocks += AiAnswerBlock.Bullet(strip(line.replaceFirst(Regex("^([-*•]|\\d+[.)])\\s+"), "")))
            }
            // Hele linjen i fet skrift brukes ofte som overskrift når ## mangler.
            line.startsWith("**") && line.endsWith("**") && line.length > 4 -> {
                flush(); blocks += AiAnswerBlock.Heading(strip(line))
            }
            else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(line) }
        }
    }
    flush()
    return blocks
}
