package no.healthlog.app.garmin

import kotlin.math.roundToInt

/**
 * Skritt per minutt som regnes som moderat aktivitet (rask gange). Forskningen bruker
 * ca. 100 skritt/min som grense for moderat intensitet (Tudor-Locke m.fl.).
 */
const val ACTIVE_STEPS_PER_MINUTE = 100.0

/** Moderat puls: minst 40 % av pulsreserven (ACSM), samme grense som classifyIntensity. */
private const val MODERATE_HEART_RATE_RESERVE = 0.40

/** Puls teller bare når du faktisk beveger deg - ikke høy puls av stress/kaffe i stolen. */
private const val MOVING_STEPS_PER_MINUTE = 40.0

/** Typisk tempo i rask gange, og "bakgrunnsskritt" resten av tiden i en lang periode. */
private const val BRISK_STEPS_PER_MINUTE = 110.0
private const val BACKGROUND_STEPS_PER_MINUTE = 20.0

/** Perioder opp til denne lengden er korte nok til at tempo og puls sier noe direkte. */
private const val SHORT_PERIOD_SECONDS = 5 * 60L

/** Én skritt-periode fra Health Connect, med snittpulsen i samme tidsrom (om den finnes). */
data class StepPeriod(val seconds: Long, val steps: Long, val avgHeartRate: Double? = null)

/**
 * Aktive minutter fra skritt-periodene (Garmin skriver én periode per minutt).
 *
 * Korte perioder (≤ 5 min) teller fullt når ENTEN
 * - tempoet er minst [threshold] skritt/min (rask gange), ELLER
 * - pulsen er minst 40 % av pulsreserven OG du går (≥ 40 skritt/min) - fanger opp
 *   motbakke, trapper og sekk, der skrittfrekvensen er lav men innsatsen høy.
 *   Krever hvilepuls for dagen; makspuls er [maxHeartRate] eller DEFAULT_MAX_HEART_RATE.
 *
 * Lange perioder (andre kilder): anslår minutter med rask gange ut fra antall skritt,
 * (skritt - 20/min) / (110 - 20), maks periodens lengde. Varighet i sekunder, summert
 * som brøkdeler av minutter og rundet av til slutt.
 */
fun activeMinutesFromSteps(
    periods: List<StepPeriod>,
    threshold: Double = ACTIVE_STEPS_PER_MINUTE,
    restingHeartRate: Int? = null,
    maxHeartRate: Int? = null
): Int {
    val max = maxHeartRate ?: DEFAULT_MAX_HEART_RATE
    val moderateHr = restingHeartRate?.takeIf { max > it }?.let { it + MODERATE_HEART_RATE_RESERVE * (max - it) }
    return periods.sumOf { p ->
        val s = if (p.seconds <= 0) 60L else p.seconds // uten varighet: regn som ett minutt
        val minutes = s / 60.0
        val pace = p.steps / minutes
        if (s <= SHORT_PERIOD_SECONDS) {
            val brisk = pace >= threshold
            val hardEffort = moderateHr != null && p.avgHeartRate != null &&
                p.avgHeartRate >= moderateHr && pace >= MOVING_STEPS_PER_MINUTE
            if (brisk || hardEffort) minutes else 0.0
        } else {
            ((p.steps - BACKGROUND_STEPS_PER_MINUTE * minutes) / (BRISK_STEPS_PER_MINUTE - BACKGROUND_STEPS_PER_MINUTE))
                .coerceIn(0.0, minutes)
        }
    }.roundToInt()
}
