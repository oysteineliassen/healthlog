package no.healthlog.app.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveMinutesTest {

    @Test
    fun `short periods use the pace directly`() {
        assertEquals(5, activeMinutesFromSteps(listOf(5L to 550L)))   // 110/min
        assertEquals(0, activeMinutesFromSteps(listOf(5L to 300L)))   // 60/min
        assertEquals(1, activeMinutesFromSteps(listOf(0L to 150L)))   // 0 min regnes som 1
    }

    @Test
    fun `a brisk walk inside a long Garmin period is not lost in the average`() {
        // 60 min: 20 min rask gange (2200) + 40 min rolig (800) = 3000 skritt, snitt 50/min.
        assertEquals(20, activeMinutesFromSteps(listOf(60L to 3000L)))
    }

    @Test
    fun `ordinary pottering in a long period gives no active minutes`() {
        assertEquals(0, activeMinutesFromSteps(listOf(60L to 1000L)))
    }

    @Test
    fun `a long period of continuous brisk walking counts fully, but never more than its length`() {
        assertEquals(15, activeMinutesFromSteps(listOf(15L to 2000L)))
    }
}
