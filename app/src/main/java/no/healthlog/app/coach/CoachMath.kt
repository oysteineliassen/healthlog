package no.healthlog.app.coach

import no.healthlog.app.DayTags
import no.healthlog.app.TrainingIntensity
import no.healthlog.app.data.HealthDay
import no.healthlog.app.data.habitTagSet
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Felles regnestykker for AI-treneren og Dagsform-kortet. Ren Kotlin uten Android-
 * avhengigheter, slik at alt her kan enhetstestes (se CoachEngineTest/ReadinessTest).
 */

internal fun parseDate(day: HealthDay): LocalDate? = runCatching { LocalDate.parse(day.date) }.getOrNull()

internal fun byDate(allDays: List<HealthDay>): Map<LocalDate, HealthDay> =
    allDays.mapNotNull { day -> parseDate(day)?.let { it to day } }.toMap()

/**
 * Dager merket syk eller reise holdes utenfor "din normal" og sammenhenger - en uke
 * med influensa skal ikke trekke ned HRV-normalen din eller få det til å se ut som at
 * trening gir dårligere søvn. Hviledager er derimot vanlige dager og telles med.
 */
internal fun HealthDay.isAtypical(): Boolean = dayTag == DayTags.SICK || dayTag == DayTags.TRAVEL

/** Snitt og standardavvik - null hvis det er færre enn [minCount] verdier å bygge på. */
internal data class Baseline(val mean: Double, val stdDev: Double, val count: Int)

internal fun baselineOf(values: List<Double>, minCount: Int = 7): Baseline? {
    if (values.size < minCount) return null
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    return Baseline(mean, sqrt(variance), values.size)
}

/**
 * "Din normal" for en måling: de siste [windowDays] dagene FØR [today] (i dag selv er
 * det vi sammenligner, så den kan ikke være med i sin egen normal), uten syk/reise-dager.
 */
internal fun normalFor(
    byDate: Map<LocalDate, HealthDay>,
    today: LocalDate,
    windowDays: Long = 28,
    minCount: Int = 7,
    selector: (HealthDay) -> Double?
): Baseline? {
    val from = today.minusDays(windowDays)
    val values = byDate
        .filter { (date, day) -> date.isBefore(today) && !date.isBefore(from) && !day.isAtypical() }
        .values
        .mapNotNull(selector)
    return baselineOf(values, minCount)
}

/**
 * Treningsbelastning for én dag: minutter vektet med intensitet, slik at 30 min hard
 * økt teller mer enn 30 min rolig gåtur. Ukjent intensitet får en middels vekt.
 */
internal fun trainingLoad(day: HealthDay): Double {
    val minutes = day.trainingMinutes ?: return 0.0
    val factor = when (day.trainingIntensity) {
        TrainingIntensity.LIGHT -> 1.0
        TrainingIntensity.MODERATE -> 1.5
        TrainingIntensity.VIGOROUS -> 2.0
        else -> 1.3
    }
    return minutes * factor
}

/**
 * Siste 7 dager sammenlignet med en normal uke (snittet av de 4 ukene før det).
 * [changePercent] er positiv når du har trent mer enn normalt, negativ når mindre.
 */
internal data class TrainingLoadStatus(
    val last7: Double,
    val normalWeek: Double,
    val changePercent: Int,
    val hadAtypicalDaysLast7: Boolean
)

internal fun trainingLoadStatus(byDate: Map<LocalDate, HealthDay>, today: LocalDate): TrainingLoadStatus? {
    val last7Dates = (0L..6L).map { today.minusDays(it) }
    val previous28Dates = (7L..34L).map { today.minusDays(it) }

    // Krever nesten fullstendige data i begge perioder - ellers kan en uke uten synk
    // se ut som en uke uten trening.
    if (last7Dates.count { it in byDate } < 5) return null
    if (previous28Dates.count { it in byDate } < 21) return null

    val last7 = last7Dates.sumOf { date -> byDate[date]?.let(::trainingLoad) ?: 0.0 }
    val normalWeek = previous28Dates.sumOf { date -> byDate[date]?.let(::trainingLoad) ?: 0.0 } / 4.0

    // Under ca. 1 time rolig trening i en normal uke blir prosentene meningsløse
    // (fra 20 til 40 min er "+100 %", men ikke en reell belastningsøkning).
    if (normalWeek < 60.0) return null

    val change = ((last7 - normalWeek) / normalWeek * 100).toInt()
    val atypical = last7Dates.any { byDate[it]?.isAtypical() == true }
    return TrainingLoadStatus(last7, normalWeek, change, atypical)
}

/** Resultatet av en gruppe-sammenligning, med antall dager bak hvert snitt. */
internal data class GroupComparison(
    val withMean: Double,
    val withDays: Int,
    val withoutMean: Double,
    val withoutDays: Int
)

/**
 * Sammenligner [selector] mellom dager som oppfyller [condition] og dager som ikke gjør
 * det. Tre forbedringer mot den opprinnelige, enkle versjonen:
 * - Bare siste [windowDays] dager, og nyere dager veier tyngre (halveringstid
 *   [halfLifeDays]) - vanene dine endrer seg, og et mønster fra i vår skal ikke
 *   overstyre hvordan det er nå.
 * - Syk/reise-dager holdes utenfor.
 * - Krever minst [minGroupSize] dager med verdi i hver gruppe (før var det 3, som er
 *   for lite til å skille et mønster fra tilfeldigheter).
 */
internal fun compareGroups(
    byDate: Map<LocalDate, HealthDay>,
    today: LocalDate,
    condition: (HealthDay) -> Boolean,
    minGroupSize: Int = 5,
    windowDays: Long = 180,
    halfLifeDays: Double = 45.0,
    selector: (HealthDay) -> Double?
): GroupComparison? {
    val from = today.minusDays(windowDays)
    val withValues = mutableListOf<Pair<Double, Double>>()    // (verdi, vekt)
    val withoutValues = mutableListOf<Pair<Double, Double>>()

    for ((date, day) in byDate) {
        if (date.isAfter(today) || date.isBefore(from) || day.isAtypical()) continue
        val value = selector(day) ?: continue
        val age = ChronoUnit.DAYS.between(date, today).toDouble()
        val weight = 0.5.pow(age / halfLifeDays)
        if (condition(day)) withValues += value to weight else withoutValues += value to weight
    }
    if (withValues.size < minGroupSize || withoutValues.size < minGroupSize) return null

    fun weightedMean(values: List<Pair<Double, Double>>): Double =
        values.sumOf { it.first * it.second } / values.sumOf { it.second }

    return GroupComparison(weightedMean(withValues), withValues.size, weightedMean(withoutValues), withoutValues.size)
}

/**
 * Som [compareGroups], men for vane-merker: sammenligner verdien DAGEN ETTER dager med
 * merket [tag] mot dagen etter dager uten. Søvn lagres på dagen natten slutter, så
 * "Alkohol" registrert mandag kveld påvirker søvnen/HRV-en som ligger på tirsdag.
 *
 * Bare dager der du faktisk tok stilling til merkene (habitTags != null) regnes med -
 * en dag du ikke registrerte noe er ukjent, ikke "uten alkohol".
 */
internal fun compareNextDay(
    byDate: Map<LocalDate, HealthDay>,
    today: LocalDate,
    tag: String,
    minGroupSize: Int = 5,
    windowDays: Long = 180,
    halfLifeDays: Double = 45.0,
    selector: (HealthDay) -> Double?
): GroupComparison? {
    val from = today.minusDays(windowDays)
    val withValues = mutableListOf<Pair<Double, Double>>()
    val withoutValues = mutableListOf<Pair<Double, Double>>()

    for ((date, day) in byDate) {
        if (date.isBefore(from) || !date.isBefore(today)) continue
        if (day.habitTags == null || day.isAtypical()) continue
        val next = byDate[date.plusDays(1)] ?: continue
        if (next.isAtypical()) continue
        val value = selector(next) ?: continue
        val age = ChronoUnit.DAYS.between(date, today).toDouble()
        val weight = 0.5.pow(age / halfLifeDays)
        if (tag in day.habitTagSet()) withValues += value to weight else withoutValues += value to weight
    }
    if (withValues.size < minGroupSize || withoutValues.size < minGroupSize) return null

    fun weightedMean(values: List<Pair<Double, Double>>): Double =
        values.sumOf { it.first * it.second } / values.sumOf { it.second }

    return GroupComparison(weightedMean(withValues), withValues.size, weightedMean(withoutValues), withoutValues.size)
}
