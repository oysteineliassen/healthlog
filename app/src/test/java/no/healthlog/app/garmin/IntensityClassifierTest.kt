package no.healthlog.app.garmin

import no.healthlog.app.TrainingIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntensityClassifierTest {

    private fun bpm(avg: Long) = listOf(avg)

    @Test
    fun `calm strength session is no longer rated as vigorous`() {
        // Før: 100 bpm med hvilepuls 56 = 44 over hvile = "høy".
        assertEquals(TrainingIntensity.LIGHT, classifyIntensity(bpm(100), 56, null))
    }

    @Test
    fun `default max 185 gives moderate from 108 and vigorous from 134 bpm`() {
        assertEquals(TrainingIntensity.LIGHT, classifyIntensity(bpm(107), 56, null))
        assertEquals(TrainingIntensity.MODERATE, classifyIntensity(bpm(108), 56, null))
        assertEquals(TrainingIntensity.MODERATE, classifyIntensity(bpm(133), 56, null))
        assertEquals(TrainingIntensity.VIGOROUS, classifyIntensity(bpm(134), 56, null))
    }

    @Test
    fun `own max heart rate moves the thresholds`() {
        // Maks 200: høy fra 56 + 0,6 * 144 = 142,4 bpm.
        assertEquals(TrainingIntensity.MODERATE, classifyIntensity(bpm(140), 56, 200))
        assertEquals(TrainingIntensity.VIGOROUS, classifyIntensity(bpm(143), 56, 200))
    }

    @Test
    fun `without resting heart rate, share of own max is used, and nothing without max`() {
        assertEquals(TrainingIntensity.VIGOROUS, classifyIntensity(bpm(150), null, 190))
        assertEquals(TrainingIntensity.LIGHT, classifyIntensity(bpm(110), null, 190))
        assertNull(classifyIntensity(bpm(150), null, null))
    }

    @Test
    fun `no samples gives no rating`() {
        assertNull(classifyIntensity(emptyList(), 56, 185))
    }
}
