package no.healthlog.app.coach

import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodComparisonTest {

    private val wednesday = LocalDate.of(2026, 9, 23)

    @Test
    fun `weeks are always monday to sunday`() {
        assertEquals(PeriodRange(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27)), periodRange(ComparisonPeriodType.WEEK, wednesday, 0))
        assertEquals(PeriodRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20)), periodRange(ComparisonPeriodType.WEEK, wednesday, 1))
    }

    @Test
    fun `months are whole calendar months, also across year and leap years`() {
        assertEquals(PeriodRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), periodRange(ComparisonPeriodType.MONTH, wednesday, 0))
        assertEquals(PeriodRange(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)), periodRange(ComparisonPeriodType.MONTH, wednesday, 9))
        assertEquals(LocalDate.of(2028, 2, 29), periodRange(ComparisonPeriodType.MONTH, LocalDate.of(2028, 2, 10), 0).end)
    }

    @Test
    fun `today is inside period zero`() {
        assertTrue(wednesday in periodRange(ComparisonPeriodType.WEEK, wednesday, 0))
        assertTrue(wednesday in periodRange(ComparisonPeriodType.MONTH, wednesday, 0))
    }

    @Test
    fun `steps are compared as average per day, so a partial week is fair`() {
        val lastWeek = (14..20).map { HealthDay(date = "2026-09-%02d".format(it), steps = 8000) }
        val thisWeek = (21..23).map { HealthDay(date = "2026-09-%02d".format(it), steps = 8000) }
        val a = periodRange(ComparisonPeriodType.WEEK, wednesday, 1)
        val b = periodRange(ComparisonPeriodType.WEEK, wednesday, 0)
        val c = buildPeriodComparison(lastWeek + thisWeek, a.start, a.end, b.start, b.end)
        assertEquals(8000.0, c.avgStepsA!!, 0.1)
        assertEquals(8000.0, c.avgStepsB!!, 0.1)
        assertEquals(56000, c.stepsA) // summen ville sett ut som et fall til 24 000
    }
}
