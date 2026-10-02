package no.healthlog.app.garmin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NightDateTest {

    private val tuesday = LocalDate.of(2026, 9, 29)

    @Test
    fun `measurements before and after midnight belong to the same night`() {
        assertEquals(tuesday, nightDate(LocalDateTime.of(2026, 9, 28, 23, 30)))
        assertEquals(tuesday, nightDate(LocalDateTime.of(2026, 9, 29, 4, 0)))
    }

    @Test
    fun `daytime measurement stays on its own date`() {
        assertEquals(tuesday, nightDate(LocalDateTime.of(2026, 9, 29, 14, 0)))
    }
}
