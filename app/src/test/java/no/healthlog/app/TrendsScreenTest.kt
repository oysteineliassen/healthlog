package no.healthlog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrendsScreenTest {

    @Test
    fun `axisRange Fixed always returns the fixed bounds regardless of data`() {
        val (min, max) = AxisRange.Fixed(0f, 100f).resolve(dataMin = 42f, dataMax = 58f)
        assertEquals(0f, min, 0f)
        assertEquals(100f, max, 0f)
    }

    @Test
    fun `axisRange Padded adds padding above and below the data`() {
        val (min, max) = AxisRange.Padded(5f).resolve(dataMin = 10f, dataMax = 20f)
        assertEquals(5f, min, 0f)
        assertEquals(25f, max, 0f)
    }

    @Test
    fun `axisRange ZeroAnchored always starts at zero`() {
        val (min, max) = AxisRange.ZeroAnchored().resolve(dataMin = 10f, dataMax = 20f)
        assertEquals(0f, min, 0f)
        assertEquals(20f, max, 0f)
    }

    @Test
    fun `axisRange ZeroAnchored adds padding above only`() {
        val (min, max) = AxisRange.ZeroAnchored(10f).resolve(dataMin = 10f, dataMax = 20f)
        assertEquals(0f, min, 0f)
        assertEquals(30f, max, 0f)
    }

    @Test
    fun `aggregateMonthly averages points within the same month and keeps months sorted`() {
        val points = listOf(
            TrendPoint(LocalDate.of(2026, 9, 1), 10f),
            TrendPoint(LocalDate.of(2026, 9, 15), 20f),
            TrendPoint(LocalDate.of(2026, 10, 1), 30f)
        )

        val result = aggregateMonthly(points)

        assertEquals(2, result.size)
        assertEquals(LocalDate.of(2026, 9, 1), result[0].date)
        assertEquals(15f, result[0].value, 0.0001f)
        assertEquals(LocalDate.of(2026, 10, 1), result[1].date)
        assertEquals(30f, result[1].value, 0.0001f)
    }

    @Test
    fun `aggregateMonthly returns empty list for empty input`() {
        assertTrue(aggregateMonthly(emptyList()).isEmpty())
    }

    @Test
    fun `aggregateMonthlyRange averages high and low separately`() {
        val points = listOf(
            TrendRangePoint(LocalDate.of(2026, 9, 1), high = 80f, low = 20f),
            TrendRangePoint(LocalDate.of(2026, 9, 2), high = 90f, low = 30f)
        )

        val result = aggregateMonthlyRange(points)

        assertEquals(1, result.size)
        assertEquals(85f, result[0].high, 0.0001f)
        assertEquals(25f, result[0].low, 0.0001f)
    }
}
