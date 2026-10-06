package no.healthlog.app.garmin

import kotlin.math.roundToInt

/**
 * Skritt per minutt som regnes som moderat aktivitet (rask gange). Forskningen bruker
 * ca. 100 skritt/min som grense for moderat intensitet (Tudor-Locke m.fl.).
 */
const val ACTIVE_STEPS_PER_MINUTE = 100.0

/** Typisk tempo i rask gange, og "bakgrunnsskritt" resten av tiden i en lang periode. */
private const val BRISK_STEPS_PER_MINUTE = 110.0
private const val BACKGROUND_STEPS_PER_MINUTE = 20.0

/** Perioder opp til denne lengden er korte nok til at snittempoet sier noe direkte. */
private const val SHORT_PERIOD_SECONDS = 5 * 60L

/**
 * Aktive minutter fra skritt-periodene Health Connect leverer, som (SEKUNDER, skritt).
 *
 * Sekunder, ikke hele minutter: Garmin skriver mange korte perioder (under ett minutt).
 * Med varighet i hele minutter ble 30 sekunder regnet som 1 minutt, tempoet halvert,
 * og rask gange kom aldri over grensen - derfor ble det 0 aktive minutter.
 *
 * - Korte perioder (≤ 5 min) teller fullt når tempoet er minst 100 skritt/min.
 * - Lange perioder: anslår hvor mange minutter som må ha vært rask gange for å gi så
 *   mange skritt: (skritt - 20/min i bakgrunn) / (110 - 20), maks periodens lengde.
 * Periodene summeres som brøkdeler av minutter og rundes av til slutt.
 *
 * [threshold] kan settes i Innstillinger -> Mål (standard 100): Garmin skriver én periode
 * per minutt, så grensen avgjør direkte hvilke gangminutter som teller.
 */
fun activeMinutesFromSteps(records: List<Pair<Long, Long>>, threshold: Double = ACTIVE_STEPS_PER_MINUTE): Int =
    records.sumOf { (seconds, steps) ->
        val s = if (seconds <= 0) 60L else seconds // uten varighet: regn som ett minutt
        val minutes = s / 60.0
        if (s <= SHORT_PERIOD_SECONDS) {
            if (steps / minutes >= threshold) minutes else 0.0
        } else {
            ((steps - BACKGROUND_STEPS_PER_MINUTE * minutes) / (BRISK_STEPS_PER_MINUTE - BACKGROUND_STEPS_PER_MINUTE))
                .coerceIn(0.0, minutes)
        }
    }.roundToInt()
