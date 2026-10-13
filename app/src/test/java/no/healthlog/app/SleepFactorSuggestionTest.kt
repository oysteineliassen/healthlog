package no.healthlog.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepFactorSuggestionTest {

    @Test
    fun `habit tags from yesterday evening suggest matching sleep factors`() {
        assertEquals(
            setOf(SleepFactors.ALCOHOL, SleepFactors.LATE_MEAL),
            suggestedSleepFactors(setOf("Alkohol", "Sen middag", "Gåtur"))
        )
        assertEquals(setOf(SleepFactors.CAFFEINE), suggestedSleepFactors(setOf("Kaffe etter kl 15")))
        assertEquals(emptySet<String>(), suggestedSleepFactors(setOf("Meditasjon")))
        // Delord skal ikke treffe.
        assertEquals(emptySet<String>(), suggestedSleepFactors(setOf("Utvikling", "Vintertur", "Følelser")))
    }
}
