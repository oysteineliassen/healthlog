package no.healthlog.app

import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthViewModelTest {

    @Test
    fun `calculateHealthScore returns 0 for null day`() {
        assertEquals(0, calculateHealthScore(null))
    }

    @Test
    fun `calculateHealthScore returns 100 when all criteria are met exactly at threshold`() {
        val day = HealthDay(
            date = "2026-09-16",
            sleepMinutes = 420,
            hrvMs = 40.0,
            stress = 30,
            steps = 8000,
            bodyBatteryMax = 75
        )
        assertEquals(100, calculateHealthScore(day))
    }

    @Test
    fun `calculateHealthScore returns 0 when all fields are null`() {
        val day = HealthDay(date = "2026-09-16")
        assertEquals(0, calculateHealthScore(day))
    }

    @Test
    fun `calculateHealthScore counts only the sleep criterion`() {
        val day = HealthDay(date = "2026-09-16", sleepMinutes = 500)
        assertEquals(20, calculateHealthScore(day))
    }

    @Test
    fun `calculateHealthScore treats values just below threshold as not met`() {
        val day = HealthDay(
            date = "2026-09-16",
            sleepMinutes = 419,
            hrvMs = 39.9,
            stress = 31,
            steps = 7999,
            bodyBatteryMax = 74
        )
        assertEquals(0, calculateHealthScore(day))
    }

    @Test
    fun `weightChange returns null with fewer than two measurements`() {
        assertNull(weightChange(emptyList()))
        assertNull(weightChange(listOf(HealthDay(date = "2026-09-16", weightKg = 80.0))))
    }

    @Test
    fun `weightChange returns difference between first and last of last 14 days`() {
        val days = (1..5).map { i ->
            HealthDay(date = "2026-09-0$i", weightKg = 80.0 + i)
        }
        // Sortert stigende: 81, 82, 83, 84, 85 -> siste minus første = 4.0
        assertEquals(4.0, weightChange(days)!!, 0.0001)
    }

    @Test
    fun `weightChange sorts by date regardless of input order`() {
        val days = listOf(
            HealthDay(date = "2026-09-03", weightKg = 83.0),
            HealthDay(date = "2026-09-01", weightKg = 80.0),
            HealthDay(date = "2026-09-02", weightKg = 81.0)
        )
        assertEquals(3.0, weightChange(days)!!, 0.0001)
    }

    @Test
    fun `weightChange ignores days without a weight value`() {
        val days = listOf(
            HealthDay(date = "2026-09-01", weightKg = 80.0),
            HealthDay(date = "2026-09-02", weightKg = null),
            HealthDay(date = "2026-09-03", weightKg = 82.0)
        )
        assertEquals(2.0, weightChange(days)!!, 0.0001)
    }
}
