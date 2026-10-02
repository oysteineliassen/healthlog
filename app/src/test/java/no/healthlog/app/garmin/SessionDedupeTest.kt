package no.healthlog.app.garmin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SessionDedupeTest {

    private data class S(val name: String, val start: Instant, val end: Instant)

    private val t0 = Instant.parse("2026-10-02T15:00:00Z")
    private fun s(name: String, fromMin: Long, toMin: Long) = S(name, t0.plusSeconds(fromMin * 60), t0.plusSeconds(toMin * 60))
    private fun dedupe(list: List<S>) = dedupeOverlappingSessions(list, { it.start }, { it.end }).map { it.name }

    @Test
    fun `the same workout written by two apps is kept once`() {
        assertEquals(listOf("garmin"), dedupe(listOf(s("garmin", 0, 26), s("fit", 0, 26))))
    }

    @Test
    fun `slightly shifted copy is still a duplicate, and the longest is kept`() {
        assertEquals(listOf("lang"), dedupe(listOf(s("kort", 1, 25), s("lang", 0, 27))))
    }

    @Test
    fun `back to back workouts are both kept`() {
        assertEquals(listOf("oppvarming", "styrke"), dedupe(listOf(s("styrke", 15, 45), s("oppvarming", 0, 15))))
    }

    @Test
    fun `small overlap between two different workouts is not treated as a duplicate`() {
        assertEquals(listOf("løp", "styrke"), dedupe(listOf(s("løp", 0, 40), s("styrke", 35, 75))))
    }
}
