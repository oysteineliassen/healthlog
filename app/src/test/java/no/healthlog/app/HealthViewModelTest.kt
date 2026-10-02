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
    fun `calculateHealthScore returns 100 when all criteria are met exactly at goal`() {
        val day = HealthDay(
            date = "2026-09-16",
            sleepMinutes = 480,
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
    fun `calculateHealthScore gives 0 points at or beyond the danger floor`() {
        val day = HealthDay(
            date = "2026-09-16",
            sleepMinutes = 360, // 75% av 8t-målet
            hrvMs = 20.0,
            stress = 70,
            steps = 2400, // 30% av 8000-målet
            bodyBatteryMax = 40
        )
        assertEquals(0, calculateHealthScore(day))
    }

    @Test
    fun `calculateHealthScore grades points between floor and goal`() {
        // Halvveis mellom faregrense (6t) og mål (8t) gir halv uttelling
        val day = HealthDay(date = "2026-09-16", sleepMinutes = 420)
        assertEquals(10, calculateHealthScore(day))
        // Rett under målet gir nesten full uttelling, ikke 0 som i gammel alt-eller-ingenting-modell
        assertEquals(20, calculateHealthScore(HealthDay(date = "2026-09-16", sleepMinutes = 479)))
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
