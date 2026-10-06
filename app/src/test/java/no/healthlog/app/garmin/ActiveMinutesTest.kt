package no.healthlog.app.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveMinutesTest {

    @Test
    fun `short Garmin periods under a minute are measured in seconds`() {
        // 20 min rask gange levert som 40 perioder à 30 s med 55 skritt (110/min).
        // Før ble hver regnet som 1 min med 55 skritt/min -> 0 aktive minutter.
        assertEquals(20, activeMinutesFromSteps(List(40) { 30L to 55L }))
    }

    @Test
    fun `slow periods do not count`() {
        assertEquals(0, activeMinutesFromSteps(List(40) { 30L to 30L })) // 60/min
    }

    @Test
    fun `a brisk walk inside a long period is estimated`() {
        // 60 min: 20 min rask gange (2200) + 40 min rolig (800) = 3000 skritt.
        assertEquals(20, activeMinutesFromSteps(listOf(3600L to 3000L)))
        assertEquals(0, activeMinutesFromSteps(listOf(3600L to 1000L)))
        assertEquals(15, activeMinutesFromSteps(listOf(900L to 2000L))) // aldri mer enn lengden
    }

    @Test
    fun `period without duration counts as one minute`() {
        assertEquals(1, activeMinutesFromSteps(listOf(0L to 150L)))
    }
}
