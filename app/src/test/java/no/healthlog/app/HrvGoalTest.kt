package no.healthlog.app

import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HrvGoalTest {

    @Test
    fun `falls back to the generic goal with fewer than 14 HRV readings`() {
        val days = (1..10).map { i ->
            HealthDay(date = "2026-08-%02d".format(i), hrvMs = 45.0)
        }
        val goal = calculateHrvGoal(days)
        assertFalse(goal.isPersonalized)
        assertEquals(40f, goal.value, 0f)
    }

    @Test
    fun `uses a personalized goal once there are at least 14 readings`() {
        // Konstant 50.0 ms i 14 dager -> snitt 50, standardavvik 0 -> mål blir 50.
        val days = (1..14).map { i ->
            HealthDay(date = "2026-08-%02d".format(i), hrvMs = 50.0)
        }
        val goal = calculateHrvGoal(days)
        assertTrue(goal.isPersonalized)
        assertEquals(50f, goal.value, 0.01f)
    }

    @Test
    fun `personal goal accounts for variation, not just the raw average`() {
        // Veksler mellom 40 og 60 -> snitt 50, standardavvik 10 -> mål blir 40 (snitt - avvik).
        val days = (1..14).map { i ->
            HealthDay(date = "2026-08-%02d".format(i), hrvMs = if (i % 2 == 0) 60.0 else 40.0)
        }
        val goal = calculateHrvGoal(days)
        assertTrue(goal.isPersonalized)
        assertEquals(40f, goal.value, 0.5f)
    }

    @Test
    fun `personal goal never drops below the absolute floor`() {
        // Snitt 20, stort avvik -> snitt minus avvik ville blitt urimelig lavt/negativt.
        val days = (1..14).map { i ->
            HealthDay(date = "2026-08-%02d".format(i), hrvMs = if (i % 2 == 0) 50.0 else 5.0)
        }
        val goal = calculateHrvGoal(days)
        assertTrue(goal.value >= 15f)
    }

    @Test
    fun `only the most recent 60 readings are used for the baseline`() {
        // 70 gamle målinger (flere enn vindusstørrelsen) pluss 60 ferske - de gamle
        // skal da bli fullstendig utelatt, ikke bare delvis.
        val oldDays = (1..70).map { i ->
            HealthDay(date = "2025-01-%02d".format(i % 28 + 1) + "-old$i", hrvMs = 20.0)
        }
        val recentDays = (1..60).map { i ->
            HealthDay(date = "2026-08-%02d".format(i % 28 + 1) + "-new$i", hrvMs = 60.0)
        }
        val goal = calculateHrvGoal(oldDays + recentDays)
        // Med kun de 60 ferske 60-ms-dagene med i vinduet: snitt 60, avvik 0 -> mål 60.
        assertEquals(60f, goal.value, 0.5f)
    }
}
