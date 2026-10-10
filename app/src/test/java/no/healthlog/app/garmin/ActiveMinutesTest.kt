package no.healthlog.app.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveMinutesTest {

    private fun minutes(n: Int, steps: Long, hr: Double? = null) = List(n) { StepPeriod(60, steps, hr) }

    @Test
    fun `brisk minutes count and slow ones do not`() {
        assertEquals(20, activeMinutesFromSteps(minutes(20, 110)))
        assertEquals(0, activeMinutesFromSteps(minutes(20, 60)))
    }

    @Test
    fun `short periods under a minute are measured in seconds`() {
        assertEquals(20, activeMinutesFromSteps(List(40) { StepPeriod(30, 55) })) // 110/min
    }

    @Test
    fun `uphill walking with low cadence counts when heart rate is in the moderate zone`() {
        // Hvile 56, maks 185: moderat fra 56 + 0,4 * 129 = 107,6 bpm.
        val uphill = minutes(15, 70, hr = 125.0)
        assertEquals(15, activeMinutesFromSteps(uphill, restingHeartRate = 56))
        // Samme tempo med lav puls teller ikke.
        assertEquals(0, activeMinutesFromSteps(minutes(15, 70, hr = 95.0), restingHeartRate = 56))
    }

    @Test
    fun `high heart rate while sitting still does not count`() {
        assertEquals(0, activeMinutesFromSteps(minutes(10, 10, hr = 120.0), restingHeartRate = 56))
    }

    @Test
    fun `heart rate is ignored without a resting heart rate for the day`() {
        assertEquals(0, activeMinutesFromSteps(minutes(10, 70, hr = 130.0), restingHeartRate = null))
    }

    @Test
    fun `own max heart rate moves the heart rate limit`() {
        // Maks 200: moderat fra 56 + 0,4 * 144 = 113,6 bpm.
        assertEquals(0, activeMinutesFromSteps(minutes(10, 70, hr = 110.0), restingHeartRate = 56, maxHeartRate = 200))
        assertEquals(10, activeMinutesFromSteps(minutes(10, 70, hr = 115.0), restingHeartRate = 56, maxHeartRate = 200))
    }

    @Test
    fun `long periods from other sources are estimated from the step count`() {
        assertEquals(20, activeMinutesFromSteps(listOf(StepPeriod(3600, 3000))))
        assertEquals(0, activeMinutesFromSteps(listOf(StepPeriod(3600, 1000))))
        assertEquals(15, activeMinutesFromSteps(listOf(StepPeriod(900, 2000))))
    }

    @Test
    fun `garmin one-minute periods follow the chosen threshold`() {
        assertEquals(0, activeMinutesFromSteps(minutes(30, 92)))
        assertEquals(30, activeMinutesFromSteps(minutes(30, 92), threshold = 90.0))
    }

    @Test
    fun `period without duration counts as one minute`() {
        assertEquals(1, activeMinutesFromSteps(listOf(StepPeriod(0, 150))))
    }
}
