package no.healthlog.app.coach

import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FastingTest {

    private val start = LocalDate.of(2026, 10, 12)
    private val period = FastingPeriod(start, 5) // 12.-16. oktober

    @Test
    fun `periods round-trip through the stored text and bad entries are skipped`() {
        val periods = listOf(FastingPeriod(LocalDate.of(2026, 11, 1), 3), period)
        assertEquals(periods.sortedBy { it.start }, parseFastingPeriods(periods.serialize()))
        assertEquals(listOf(period), parseFastingPeriods("2026-10-12:5,tull,2026-13-01:3,2026-10-01:0"))
    }

    @Test
    fun `relevant period covers two weeks before and after`() {
        assertEquals(period, relevantFastingPeriod(listOf(period), start.minusDays(14)))
        assertEquals(period, relevantFastingPeriod(listOf(period), period.end.plusDays(14)))
        assertNull(relevantFastingPeriod(listOf(period), start.minusDays(15)))
    }

    @Test
    fun `nightly values are shifted to the nights after each fasting day`() {
        // HRV 50 før, 35 nettene etter fastedagene (13.-17.), 48 etterpå.
        val days = (-7..12).map { offset ->
            val date = start.plusDays(offset.toLong())
            val hrv = when {
                date.isAfter(start) && !date.isAfter(period.end.plusDays(1)) -> 35.0
                !date.isAfter(start) -> 50.0
                else -> 48.0
            }
            HealthDay(date = date.toString(), hrvMs = hrv, weightKg = if (date in period) 79.0 else 81.0)
        }
        val rows = fastingComparison(days, period, today = start.plusDays(12)).associateBy { it.metric }
        val hrv = rows.getValue(FastingMetric.HRV)
        assertEquals(50.0, hrv.before!!.average, 0.01)
        assertEquals(35.0, hrv.during!!.average, 0.01)
        assertEquals(5, hrv.during!!.days)
        assertEquals(48.0, hrv.after!!.average, 0.01)
        // Vekt er ikke nattlig - fastedagene selv.
        assertEquals(79.0, rows.getValue(FastingMetric.WEIGHT).during!!.average, 0.01)
    }

    @Test
    fun `phases in the future are empty`() {
        val days = listOf(HealthDay(date = start.minusDays(1).toString(), stress = 30))
        val stress = fastingComparison(days, period, today = start.minusDays(1)).first { it.metric == FastingMetric.STRESS }
        assertEquals(30.0, stress.before!!.average, 0.01)
        assertNull(stress.during)
        assertNull(stress.after)
    }

    @Test
    fun `report shows change from before to during and the AI prompt holds the days around the fast`() {
        val days = (-7..12).map { offset ->
            val date = start.plusDays(offset.toLong())
            HealthDay(date = date.toString(), weightKg = if (date in period) 79.0 else 81.0, eveningNote = if (offset == 2) "Tung dag;\nsliten" else null)
        }
        val rows = fastingComparison(days, period, today = start.plusDays(12))
        val report = fastingReportText(period, rows, "Faste", Triple("før", "under", "etter")) { it.name }
        assertTrue(report.contains("WEIGHT: før 81.0 · under 79.0 (-2.0) · etter 81.0"))
        val prompt = buildFastingAiPrompt(days, period, start.plusDays(12), report)
        assertTrue(prompt.contains("2026-10-14;ja;"))
        assertTrue(prompt.contains("Tung dag, sliten"))
        assertTrue(prompt.contains("2026-10-05;"))
        assertFalse(prompt.contains("2026-10-04;"))
    }
}
