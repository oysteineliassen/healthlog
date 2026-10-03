package no.healthlog.app.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveMinutesTest {

    @Test
    fun `brisk walking counts, strolling does not`() {
        // 15 min med 1600 skritt (107/min) teller, 15 min med 900 skritt (60/min) ikke.
        assertEquals(15, activeMinutesFromSteps(listOf(15L to 1600L, 15L to 900L)))
    }

    @Test
    fun `exactly 100 steps per minute is the limit`() {
        assertEquals(10, activeMinutesFromSteps(listOf(10L to 1000L)))
        assertEquals(0, activeMinutesFromSteps(listOf(10L to 999L)))
    }

    @Test
    fun `zero length period is treated as one minute`() {
        assertEquals(1, activeMinutesFromSteps(listOf(0L to 150L)))
    }
}
