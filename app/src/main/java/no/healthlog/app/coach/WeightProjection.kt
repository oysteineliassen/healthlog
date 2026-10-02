package no.healthlog.app.coach

import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 7-dagers glidende snitt: for hver dato, snittet av alle målinger fra og med 6 dager før
 * til og med den datoen. Vekten svinger lett 1-2 kg fra dag til dag (væske, mat, salt),
 * så snittet viser den faktiske retningen mye tydeligere enn enkeltmålingene.
 */
fun rollingAverage(points: List<Pair<LocalDate, Double>>, windowDays: Long = 7): List<Pair<LocalDate, Double>> {
    val sorted = points.sortedBy { it.first }
    return sorted.map { (date, _) ->
        val from = date.minusDays(windowDays - 1)
        val inWindow = sorted.filter { !it.first.isBefore(from) && !it.first.isAfter(date) }
        date to inWindow.map { it.second }.average()
    }
}

enum class WeightProjectionStatus {
    /** Ikke noe vektmål satt - bare trenden vises. */
    NO_GOAL,
    /** Innenfor 0,3 kg av målet. */
    REACHED,
    /** Trenden går mot målet - [WeightProjection.eta] er satt. */
    ON_TRACK,
    /** Går mot målet, men så sakte at det ville tatt over to år. */
    TOO_SLOW,
    /** Trenden går bort fra målet (eller står stille). */
    AWAY
}

data class WeightProjection(
    /** Nyeste verdi av det glidende snittet. */
    val currentTrendKg: Double,
    /** Endring per uke ut fra de siste 4 ukene (negativ = ned). */
    val weeklyChangeKg: Double,
    val goalKg: Double?,
    val eta: LocalDate?,
    val status: WeightProjectionStatus
)

/**
 * Anslag for når du når [goalKg] med dagens tempo. Tempoet er en rett linje (minste
 * kvadraters metode) gjennom målingene de siste 28 dagene - robust mot enkeltdager
 * og enkel å forklare. Null når det er for lite data: minst 4 veiinger spredt over
 * minst 10 dager.
 */
fun projectWeight(allDays: List<HealthDay>, today: LocalDate, goalKg: Double?): WeightProjection? {
    val weights = allDays.mapNotNull { day ->
        val date = parseDate(day) ?: return@mapNotNull null
        val kg = day.weightKg ?: return@mapNotNull null
        if (date.isAfter(today)) null else date to kg
    }
    val recent = weights.filter { !it.first.isBefore(today.minusDays(27)) }.sortedBy { it.first }
    if (recent.size < 4) return null
    if (ChronoUnit.DAYS.between(recent.first().first, recent.last().first) < 10) return null

    val xs = recent.map { ChronoUnit.DAYS.between(today, it.first).toDouble() }
    val ys = recent.map { it.second }
    val meanX = xs.average()
    val meanY = ys.average()
    val denominator = xs.sumOf { (it - meanX) * (it - meanX) }
    val slopePerDay = if (denominator == 0.0) 0.0 else xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) } / denominator

    val current = rollingAverage(weights).last().second
    val weekly = slopePerDay * 7

    if (goalKg == null) return WeightProjection(current, weekly, null, null, WeightProjectionStatus.NO_GOAL)

    val remaining = goalKg - current
    if (abs(remaining) < 0.3) return WeightProjection(current, weekly, goalKg, null, WeightProjectionStatus.REACHED)

    val towardsGoal = slopePerDay != 0.0 && (remaining > 0) == (slopePerDay > 0)
    if (!towardsGoal || abs(weekly) < 0.05) {
        return WeightProjection(current, weekly, goalKg, null, WeightProjectionStatus.AWAY)
    }
    val daysToGoal = remaining / slopePerDay
    if (daysToGoal > 730) return WeightProjection(current, weekly, goalKg, null, WeightProjectionStatus.TOO_SLOW)
    return WeightProjection(current, weekly, goalKg, today.plusDays(daysToGoal.toLong()), WeightProjectionStatus.ON_TRACK)
}
