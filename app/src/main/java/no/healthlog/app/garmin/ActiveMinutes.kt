package no.healthlog.app.garmin

import kotlin.math.roundToLong

/**
 * Skritt per minutt som regnes som moderat aktivitet (rask gange). Forskningen bruker
 * ca. 100 skritt/min som grense for moderat intensitet (Tudor-Locke m.fl.).
 */
const val ACTIVE_STEPS_PER_MINUTE = 100.0

/** Typisk tempo i rask gange, og "bakgrunnsskritt" resten av tiden i en lang bolk. */
private const val BRISK_STEPS_PER_MINUTE = 110.0
private const val BACKGROUND_STEPS_PER_MINUTE = 20.0

/** Bolker opp til denne lengden er korte nok til at snittempoet sier noe direkte. */
private const val SHORT_PERIOD_MINUTES = 5L

/**
 * Aktive minutter fra skritt-periodene Health Connect leverer, som (minutter, skritt).
 *
 * Korte perioder (≤ 5 min) teller fullt når snittet er minst 100 skritt/min. Garmin
 * skriver derimot ofte LANGE perioder (gjerne 15-60 min), der en rask tur på 20 min
 * drukner i snittet - da ble det 0 aktive minutter nesten hver dag. For lange perioder
 * anslås derfor hvor mange minutter som må ha vært rask gange for å gi så mange skritt:
 *   (skritt - 20/min i bakgrunn) / (110 - 20), begrenset til periodens lengde.
 * Eksempel: 60 min med 3000 skritt -> ca. 20 aktive minutter; 60 min med 1000 -> 0.
 */
fun activeMinutesFromSteps(records: List<Pair<Long, Long>>): Int =
    records.sumOf { (minutes, steps) ->
        val m = minutes.coerceAtLeast(1)
        if (m <= SHORT_PERIOD_MINUTES) {
            if (steps.toDouble() / m >= ACTIVE_STEPS_PER_MINUTE) m else 0L
        } else {
            val estimate = (steps - BACKGROUND_STEPS_PER_MINUTE * m) / (BRISK_STEPS_PER_MINUTE - BACKGROUND_STEPS_PER_MINUTE)
            estimate.coerceIn(0.0, m.toDouble()).roundToLong()
        }
    }.toInt()
