package no.healthlog.app.garmin

import no.healthlog.app.TrainingIntensity

/** Brukes når du ikke har lagt inn makspuls selv (Innstillinger -> Mål). */
const val DEFAULT_MAX_HEART_RATE = 185

/**
 * Intensitet for en økt ut fra snittpulsen, etter ACSM sine grenser for pulsreserve
 * (Karvonen): andel av spennet mellom hvilepuls og makspuls.
 *   lett < 40 %, moderat 40-59 %, høy >= 60 %
 * Eksempel: hvile 56, maks 185 -> moderat fra 108 bpm, høy fra 134 bpm.
 *
 * Før var grensen "40 slag over hvilepuls" for høy - med hvilepuls 56 ble alt over
 * 96 bpm (f.eks. en rolig styrkeøkt) regnet som høy intensitet.
 *
 * Mangler hvilepulsen den dagen brukes andel av makspuls i stedet (lett < 64 %,
 * moderat 64-76 %, høy >= 77 %), men bare når du har lagt inn makspuls selv - et
 * anslått maks alene er for usikkert.
 */
fun classifyIntensity(bpmSamples: List<Long>, restingHr: Int?, maxHr: Int?): String? {
    if (bpmSamples.isEmpty()) return null
    val avg = bpmSamples.average()
    if (restingHr == null) {
        if (maxHr == null) return null
        val share = avg / maxHr
        return when {
            share < 0.64 -> TrainingIntensity.LIGHT
            share < 0.77 -> TrainingIntensity.MODERATE
            else -> TrainingIntensity.VIGOROUS
        }
    }
    val max = maxHr ?: DEFAULT_MAX_HEART_RATE
    if (max <= restingHr) return null
    val reserve = (avg - restingHr) / (max - restingHr)
    return when {
        reserve < 0.40 -> TrainingIntensity.LIGHT
        reserve < 0.60 -> TrainingIntensity.MODERATE
        else -> TrainingIntensity.VIGOROUS
    }
}
