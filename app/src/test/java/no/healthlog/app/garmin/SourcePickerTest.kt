package no.healthlog.app.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePickerTest {

    private data class R(val source: String, val steps: Long)

    private fun pick(vararg r: R) = recordsFromMainSource(r.toList(), { it.source }, { it.steps })

    @Test
    fun `steps from phone and watch are not added together`() {
        val picked = pick(R("garmin", 5000), R("phone", 3000), R("garmin", 4000), R("phone", 2500))
        assertEquals(9000, picked.sumOf { it.steps })
    }

    @Test
    fun `single source is kept as is`() {
        assertEquals(2, pick(R("garmin", 1), R("garmin", 2)).size)
    }

    @Test
    fun `tie picks the same source every time`() {
        assertEquals("a", pick(R("b", 10), R("a", 10)).first().source)
    }
}
