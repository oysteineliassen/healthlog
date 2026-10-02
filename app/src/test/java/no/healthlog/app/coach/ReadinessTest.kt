package no.healthlog.app.coach

import no.healthlog.app.DayTags
import no.healthlog.app.TrainingIntensity
import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReadinessTest {

    private val today = LocalDate.of(2026, 9, 27)

    /**
     * 28 dager med stabil normal (HRV rundt 50, hvilepuls rundt 55, 7,5 t søvn), pluss
     * [todayEntry] som i dag.
     */
    private fun history(todayEntry: HealthDay?, extra: (Int, HealthDay) -> HealthDay = { _, d -> d }): List<HealthDay> {
        val past = (1..28).map { ago ->
            val d = HealthDay(
                date = today.minusDays(ago.toLong()).toString(),
                hrvMs = if (ago % 2 == 0) 48.0 else 52.0,
                restingHeartRate = if (ago % 2 == 0) 54 else 56,
                sleepMinutes = 450
            )
            extra(ago, d)
        }
        return past + listOfNotNull(todayEntry)
    }

    private fun todayWith(hrv: Double?, rhr: Int?, sleepMinutes: Int?) =
        HealthDay(date = today.toString(), hrvMs = hrv, restingHeartRate = rhr, sleepMinutes = sleepMinutes)

    @Test
    fun `rested day with good numbers is green and strong`() {
        val r = calculateReadiness(history(todayWith(58.0, 51, 500)), today, sleepGoalMinutes = 480)!!
        assertEquals(ReadinessLevel.GOOD, r.level)
        assertTrue(r.isStrong)
    }

    @Test
    fun `everything within normal is green but not strong`() {
        val r = calculateReadiness(history(todayWith(50.0, 55, 450)), today, sleepGoalMinutes = 480)!!
        assertEquals(ReadinessLevel.GOOD, r.level)
        assertFalse(r.isStrong)
    }

    @Test
    fun `one warning sign gives yellow`() {
        val r = calculateReadiness(history(todayWith(50.0, 61, 470)), today, sleepGoalMinutes = 480)!!
        assertEquals(ReadinessLevel.MODERATE, r.level)
        assertEquals(ReadinessStatus.WORSE, r.signals.first { it.type == ReadinessSignalType.RESTING_HR }.status)
    }

    @Test
    fun `low HRV, high resting heart rate and short sleep gives red`() {
        val r = calculateReadiness(history(todayWith(38.0, 62, 300)), today, sleepGoalMinutes = 480)!!
        assertEquals(ReadinessLevel.LOW, r.level)
        assertEquals(3, r.signals.count { it.status == ReadinessStatus.WORSE })
    }

    @Test
    fun `a couple of ms below normal is not treated as a warning`() {
        // Normalen er svært jevn (48/52) - uten minsteterskelen ville 46 ms blitt "dårligere".
        val r = calculateReadiness(history(todayWith(46.0, 55, 470)), today, sleepGoalMinutes = 480)!!
        assertEquals(ReadinessStatus.NORMAL, r.signals.first { it.type == ReadinessSignalType.HRV }.status)
    }

    @Test
    fun `sick days are left out of the normal`() {
        // En syk uke med høy hvilepuls ville løftet normalen - da hadde 61 i dag sett normalt ut.
        val data = history(todayWith(50.0, 61, 470)) { ago, d -> if (ago in 1..7) d.copy(restingHeartRate = 68, dayTag = DayTags.SICK) else d }
        val r = calculateReadiness(data, today, sleepGoalMinutes = 480)!!
        assertEquals(ReadinessStatus.WORSE, r.signals.first { it.type == ReadinessSignalType.RESTING_HR }.status)
    }

    @Test
    fun `needs at least two signals for today`() {
        assertNull(calculateReadiness(history(todayWith(null, null, 450)), today))
        assertNull(calculateReadiness(history(null), today))
    }

    @Test
    fun `needs a normal to compare HRV and resting heart rate against`() {
        val onlyFewDays = history(todayWith(50.0, 55, 450)).takeLast(5)
        // Bare søvn kan vurderes (mot målet) - for lite til en dagsform.
        assertNull(calculateReadiness(onlyFewDays, today))
    }

    @Test
    fun `a big jump in training load lowers the readiness`() {
        val data = (0..34).map { ago ->
            HealthDay(
                date = today.minusDays(ago.toLong()).toString(),
                hrvMs = if (ago == 0) 50.0 else if (ago % 2 == 0) 48.0 else 52.0,
                restingHeartRate = 55,
                sleepMinutes = 450,
                trainingMinutes = if (ago <= 6) 70 else 30,
                trainingIntensity = if (ago <= 6) TrainingIntensity.VIGOROUS else TrainingIntensity.MODERATE
            )
        }
        val r = calculateReadiness(data, today, sleepGoalMinutes = 480)!!
        assertEquals(ReadinessLevel.MODERATE, r.level)
        assertTrue(r.signals.any { it.type == ReadinessSignalType.TRAINING_LOAD })
    }
}
