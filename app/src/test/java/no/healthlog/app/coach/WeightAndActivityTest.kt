package no.healthlog.app.coach

import no.healthlog.app.TrainingIntensity
import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeightAndActivityTest {

    private val today = LocalDate.of(2026, 9, 27) // søndag

    /** Veiing hver 2. dag i 28 dager, som går ned [perWeek] kg per uke og ender på [endKg] i dag. */
    private fun weighIns(endKg: Double, perWeek: Double) = (0..27 step 2).map { ago ->
        HealthDay(date = today.minusDays(ago.toLong()).toString(), weightKg = endKg + perWeek / 7 * ago)
    }

    @Test
    fun `rolling average smooths day to day swings`() {
        val d = LocalDate.of(2026, 9, 1)
        val points = listOf(d to 80.0, d.plusDays(1) to 82.0, d.plusDays(2) to 80.0, d.plusDays(10) to 90.0)
        val smoothed = rollingAverage(points).toMap()
        assertEquals(81.0, smoothed.getValue(d.plusDays(1)), 0.001)
        assertEquals(80.667, smoothed.getValue(d.plusDays(2)), 0.001)
        assertEquals(90.0, smoothed.getValue(d.plusDays(10)), 0.001) // de gamle er utenfor vinduet
    }

    @Test
    fun `projects a date when the trend moves towards the goal`() {
        val p = projectWeight(weighIns(endKg = 85.0, perWeek = 0.5), today, goalKg = 80.0)!!
        assertEquals(WeightProjectionStatus.ON_TRACK, p.status)
        assertEquals(-0.5, p.weeklyChangeKg, 0.01)
        // Snittet siste 7 dager ligger litt over 85; ca. 10 uker igjen.
        val weeks = java.time.temporal.ChronoUnit.WEEKS.between(today, p.eta)
        assertTrue("uker: $weeks", weeks in 9..11)
    }

    @Test
    fun `trend away from the goal gives no date`() {
        val p = projectWeight(weighIns(endKg = 85.0, perWeek = -0.4), today, goalKg = 80.0)!! // går opp
        assertEquals(WeightProjectionStatus.AWAY, p.status)
        assertNull(p.eta)
    }

    @Test
    fun `goal already reached`() {
        val p = projectWeight(weighIns(endKg = 80.1, perWeek = 0.0), today, goalKg = 80.0)!!
        assertEquals(WeightProjectionStatus.REACHED, p.status)
    }

    @Test
    fun `too few weigh-ins gives no projection`() {
        val few = weighIns(85.0, 0.5).take(3)
        assertNull(projectWeight(few, today, 80.0))
    }

    @Test
    fun `a run is not counted twice, and hard training counts double`() {
        val run = HealthDay(date = "2026-09-22", activeMinutes = 40, trainingMinutes = 40, trainingIntensity = TrainingIntensity.MODERATE)
        assertEquals(40, activityMinutesForDay(run))
        val hard = HealthDay(date = "2026-09-23", activeMinutes = 20, trainingMinutes = 30, trainingIntensity = TrainingIntensity.VIGOROUS)
        assertEquals(60, activityMinutesForDay(hard))
    }

    @Test
    fun `weekly activity only counts monday to today`() {
        val days = listOf(
            HealthDay(date = "2026-09-20", activeMinutes = 100), // forrige søndag - telles ikke
            HealthDay(date = "2026-09-21", activeMinutes = 30),  // mandag
            HealthDay(date = "2026-09-25", trainingMinutes = 45, trainingIntensity = TrainingIntensity.VIGOROUS),
            HealthDay(date = "2026-09-27", activeMinutes = 35)
        )
        val week = weeklyActivity(days, today, goalMinutes = 150)
        assertEquals(155, week.minutes)
        assertTrue(week.isComplete)
        assertEquals(0, week.daysLeft)
        val wednesday = weeklyActivity(days, LocalDate.of(2026, 9, 23), 150)
        assertEquals(30, wednesday.minutes)
        assertFalse(wednesday.isComplete)
        assertEquals(4, wednesday.daysLeft)
    }
}
