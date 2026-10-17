package no.healthlog.app.scale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyCompositionTest {

    /** Tall fra Huawei Health for samme veiing: 85,5 kg, 22,3 %, BMI 24,5. */
    @Test
    fun `matches huawei health for the same weigh-in`() {
        val c = bodyComposition(85.5, 22.3, heightCm = 187)
        assertEquals(24.5, c.bmi!!, 0.05)
        assertEquals(19.07, c.fatMassKg!!, 0.01)
        assertEquals(62.8, c.muscleMassKg!!, 0.05)
        assertEquals(45.83, c.waterKg!!, 0.05)
        assertEquals(53.6, c.waterPercent!!, 0.1)
        assertEquals(17.00, c.proteinKg!!, 0.05)
        assertEquals(3.60, c.boneMassKg!!, 0.02)
        // Katch-McArdle gir 1805; Huawei viser 1872 med sin egen formel.
        assertEquals(1805, c.bmrKcal)
    }

    @Test
    fun `without fat percent only weight and bmi are given`() {
        val c = bodyComposition(85.5, null, heightCm = 187)
        assertEquals(24.5, c.bmi!!, 0.05)
        assertNull(c.fatMassKg)
        assertNull(c.bmrKcal)
    }

    @Test
    fun `without height there is no bmi`() {
        assertNull(bodyComposition(85.5, 22.3, heightCm = null).bmi)
    }
}
