package no.healthlog.app

import no.healthlog.app.coach.Baseline
import no.healthlog.app.coach.baselineOf
import no.healthlog.app.coach.isAtypical
import no.healthlog.app.data.HealthDay
import kotlin.math.max
import kotlin.math.min

data class PersonalGoal(val value: Float, val isPersonalized: Boolean)

private enum class GoalDirection { FLOOR, CEILING }

private const val MIN_READINGS_FOR_PERSONAL_GOAL = 14
private const val BASELINE_WINDOW_DAYS = 60

/**
 * Inspirert av Garmin Connect sin HRV Status: i stedet for ett fast tall for alle,
 * regnes en personlig grense ut fra dine egne siste målinger - gjennomsnittet ditt
 * pluss/minus den naturlige variasjonen din, altså kanten av det som er normalt for
 * AKKURAT deg (samme prinsipp som Garmins "Balanced"-sone). Brukes til HRV, søvn og
 * hvilepuls - se de tre spesifikke funksjonene under.
 *
 * [direction] avgjør om grensen skal være et gulv å holde seg over (HRV, søvn - der
 * høyere er bedre) eller et tak å holde seg under (hvilepuls - der lavere er bedre).
 * [absoluteFloor]/[absoluteCeiling] hindrer at uvanlig høy variasjon i egne data gir
 * et urimelig mål (f.eks. et søvnmål på 3 timer bare fordi noen netter var korte).
 *
 * Faller tilbake til [genericFallback] helt til det finnes nok data til at et
 * personlig mål faktisk gir mening - Garmin selv krever også noen ukers data før de
 * viser en egen baseline i stedet for en generisk status.
 */
/**
 * Snitt og variasjon over de siste [BASELINE_WINDOW_DAYS] dagene, uten syk-, reise- og
 * fastedager (samme regel som coachens "din normal") - en influensauke skal ikke dra
 * grensen ned. Null til det finnes [MIN_READINGS_FOR_PERSONAL_GOAL] målinger.
 */
private fun recentBaseline(allDays: List<HealthDay>, selector: (HealthDay) -> Double?): Baseline? {
    // Datoformatet (ÅÅÅÅ-MM-DD) sorterer riktig som ren tekst, så vi slipper å
    // parse til LocalDate bare for å finne de nyeste målingene.
    val recentValues = allDays
        .sortedByDescending { it.date }
        .take(BASELINE_WINDOW_DAYS)
        .filterNot { it.isAtypical() }
        .mapNotNull(selector)
    return baselineOf(recentValues, MIN_READINGS_FOR_PERSONAL_GOAL)
}

private fun calculatePersonalGoal(
    allDays: List<HealthDay>,
    selector: (HealthDay) -> Double?,
    direction: GoalDirection,
    genericFallback: Float,
    absoluteFloor: Float? = null,
    absoluteCeiling: Float? = null
): PersonalGoal {
    val baseline = recentBaseline(allDays, selector)
        ?: return PersonalGoal(genericFallback, isPersonalized = false)
    val mean = baseline.mean
    val stdDev = baseline.stdDev

    var value = when (direction) {
        GoalDirection.FLOOR -> mean - stdDev
        GoalDirection.CEILING -> mean + stdDev
    }
    if (absoluteFloor != null) value = max(absoluteFloor.toDouble(), value)
    if (absoluteCeiling != null) value = min(absoluteCeiling.toDouble(), value)

    return PersonalGoal(value.toFloat(), isPersonalized = true)
}

/** HRV: høyere er bedre - gulv-verdi, aldri under 15 ms uansett variasjon. */
fun calculateHrvGoal(allDays: List<HealthDay>): PersonalGoal =
    calculatePersonalGoal(
        allDays,
        selector = { it.hrvMs },
        direction = GoalDirection.FLOOR,
        genericFallback = 40f,
        absoluteFloor = 15f
    )

/**
 * Spennet HRV-poengene i helse-scoren graderes over: 0 poeng ved [floor], full uttelling
 * ved [goal]. [GENERIC] (20-40 ms) brukes til det finnes nok egne målinger.
 */
data class HrvScoreRange(val floor: Double, val goal: Double) {
    companion object {
        val GENERIC = HrvScoreRange(floor = 20.0, goal = 40.0)
    }
}

/**
 * Personlig HRV-spenn for helse-scoren: full uttelling ved snittet ditt, null poeng to
 * standardavvik under. Grensen i HRV-grafen (snitt minus ett avvik) havner da midt i
 * spennet - en normal dag for deg gir full pott, ikke et fast 40 ms-mål.
 */
fun personalHrvScoreRange(allDays: List<HealthDay>): HrvScoreRange {
    val b = recentBaseline(allDays) { it.hrvMs } ?: return HrvScoreRange.GENERIC
    // Minst 5 ms spenn, så svært jevne målinger ikke gjør poengene til alt-eller-ingenting.
    val floor = min(b.mean - 2 * b.stdDev, b.mean - 5).coerceAtLeast(10.0)
    return HrvScoreRange(floor = floor, goal = b.mean)
}

/** Søvn (timer): høyere er bedre - gulv-verdi, aldri under 6,5 timer uansett variasjon. */
fun calculateSleepGoal(allDays: List<HealthDay>): PersonalGoal =
    calculatePersonalGoal(
        allDays,
        selector = { it.sleepMinutes?.let { m -> m / 60.0 } },
        direction = GoalDirection.FLOOR,
        genericFallback = 7f,
        absoluteFloor = 6.5f
    )

/** Hvilepuls: lavere er bedre - tak-verdi, aldri over 75 bpm uansett variasjon. */
fun calculateRestingHrGoal(allDays: List<HealthDay>): PersonalGoal =
    calculatePersonalGoal(
        allDays,
        selector = { it.restingHeartRate?.toDouble() },
        direction = GoalDirection.CEILING,
        genericFallback = 60f,
        absoluteCeiling = 75f
    )

