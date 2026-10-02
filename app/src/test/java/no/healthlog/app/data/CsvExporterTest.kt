package no.healthlog.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {

    @Test
    fun `header row lists all expected columns`() {
        val header = healthDaysToCsv(emptyList()).lines().first()
        listOf("date", "weightKg", "waistCm", "bodyFatPercent", "avgHeartRate", "activeMinutes", "deepSleepMinutes", "lightSleepMinutes", "remSleepMinutes", "sleepAwakeMinutes", "trainingIntensity", "trainingType", "trainingSessions", "morningNote", "eveningNote", "dayTag", "nutritionLevel", "habitTags", "source", "updatedAt")
            .forEach { column -> assertTrue("Mangler kolonne: $column", header.contains(column)) }
    }

    @Test
    fun `null fields render as empty values`() {
        val csv = healthDaysToCsv(listOf(HealthDay(date = "2026-09-16")))
        val fields = csv.lines()[1].split(",")
        assertEquals("2026-09-16", fields[0])
        assertEquals("", fields[1]) // weightKg
        assertEquals("manual", fields[27]) // source (default)
    }

    @Test
    fun `notes containing commas are quoted`() {
        val day = HealthDay(date = "2026-09-16", eveningNote = "Løp 5 km, følte meg bra")
        val csv = healthDaysToCsv(listOf(day))
        val row = csv.lines()[1]
        assertTrue(row.contains("\"Løp 5 km, følte meg bra\""))
    }

    @Test
    fun `rows are sorted by date regardless of input order`() {
        val days = listOf(
            HealthDay(date = "2026-09-03"),
            HealthDay(date = "2026-09-01"),
            HealthDay(date = "2026-09-02")
        )
        val dataLines = healthDaysToCsv(days).lines().drop(1)
        assertEquals(
            listOf("2026-09-01", "2026-09-02", "2026-09-03"),
            dataLines.map { it.substringBefore(",") }
        )
    }
}
