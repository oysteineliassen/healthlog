package no.healthlog.app.garmin

/**
 * Skritt per minutt som regnes som moderat aktivitet (rask gange). Forskningen bruker
 * ca. 100 skritt/min som grense for moderat intensitet (Tudor-Locke m.fl.). Før var
 * grensen 40, som er rusling - da fylte vanlig hverdagsgange ukemålet nesten av seg selv.
 */
const val ACTIVE_STEPS_PER_MINUTE = 100.0

/**
 * Summerer varigheten av skritt-perioder med minst [ACTIVE_STEPS_PER_MINUTE] i snitt.
 * [records] er (minutter, skritt) per periode slik Health Connect leverer dem.
 */
fun activeMinutesFromSteps(records: List<Pair<Long, Long>>): Int =
    records.sumOf { (minutes, steps) ->
        val m = minutes.coerceAtLeast(1)
        if (steps.toDouble() / m >= ACTIVE_STEPS_PER_MINUTE) m else 0L
    }.toInt()
