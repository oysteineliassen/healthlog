package no.healthlog.app.garmin

import java.time.Duration
import java.time.Instant

/**
 * Fjerner duplikate treningsøkter: to økter regnes som samme økt når de overlapper med
 * minst [minOverlap] av den korteste øktens lengde. Den lengste beholdes. To ulike økter
 * rett etter hverandre (f.eks. oppvarming og styrke) overlapper ikke, og beholdes begge.
 */
fun <T> dedupeOverlappingSessions(
    sessions: List<T>,
    start: (T) -> Instant,
    end: (T) -> Instant,
    minOverlap: Double = 0.5
): List<T> {
    fun length(s: T) = Duration.between(start(s), end(s)).seconds.coerceAtLeast(1)
    val kept = mutableListOf<T>()
    for (candidate in sessions.sortedByDescending(::length)) {
        val duplicate = kept.any { other ->
            val overlapStart = maxOf(start(candidate), start(other))
            val overlapEnd = minOf(end(candidate), end(other))
            val overlap = Duration.between(overlapStart, overlapEnd).seconds
            overlap > 0 && overlap >= minOverlap * minOf(length(candidate), length(other))
        }
        if (!duplicate) kept += candidate
    }
    return kept.sortedBy(start)
}
