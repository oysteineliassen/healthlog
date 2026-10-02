package no.healthlog.app

import no.healthlog.app.data.HealthDay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
private fun calculatePersonalGoal(
    allDays: List<HealthDay>,
    selector: (HealthDay) -> Double?,
    direction: GoalDirection,
    genericFallback: Float,
    absoluteFloor: Float? = null,
    absoluteCeiling: Float? = null
): PersonalGoal {
    // Datoformatet (ÅÅÅÅ-MM-DD) sorterer riktig som ren tekst, så vi slipper å
    // parse til LocalDate bare for å finne de nyeste målingene.
    val recentValues = allDays
        .sortedByDescending { it.date }
        .take(BASELINE_WINDOW_DAYS)
        .mapNotNull(selector)

    if (recentValues.size < MIN_READINGS_FOR_PERSONAL_GOAL) {
        return PersonalGoal(genericFallback, isPersonalized = false)
    }

    val mean = recentValues.average()
    val variance = recentValues.sumOf { (it - mean) * (it - mean) } / recentValues.size
    val stdDev = sqrt(variance)

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

