package no.healthlog.app.garmin

import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bygger på en ekte Garmin Connect-eksport for 29.09.2026 (Forerunner, norsk tid).
 * Fasit-tallene er kontrollert mot Garmins egen FIT SDK (Python).
 */
class GarminFitImportTest {

    private val oslo = ZoneId.of("Europe/Oslo")

    private fun exportZip(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("garmin/2026-09-29.zip")!!.readBytes()

    private fun summary(): List<GarminDayData> =
        summarizeGarminFiles(extractFitFiles(exportZip()).map(::decodeFit), oslo)

    @Test
    fun `all fit files in the zip are found and decoded`() {
        val files = extractFitFiles(exportZip())
        assertEquals(18, files.size)
        files.forEach { assertTrue(decodeFit(it).isNotEmpty()) }
    }

    @Test
    fun `one export gives exactly one day, without a stray day from the last minute before midnight`() {
        val days = summary()
        assertEquals(listOf(LocalDate.of(2026, 9, 29)), days.map { it.date })
    }

    @Test
    fun `sleep score, stages and duration come from the sleep file`() {
        val day = summary().single()
        assertEquals(81, day.sleepScore)
        assertEquals(73, day.deepSleepMinutes)
        assertEquals(179, day.lightSleepMinutes)
        assertEquals(98, day.remSleepMinutes)
        assertEquals(67, day.sleepAwakeMinutes)
        assertEquals(350, day.sleepMinutes) // dyp + lett + REM
    }

    @Test
    fun `hrv, resting heart rate, stress and body battery`() {
        val day = summary().single()
        assertEquals(45.0, day.hrvMs!!, 0.01)
        assertEquals(57, day.restingHeartRate)
        assertEquals(25, day.stress)
        assertEquals(98, day.bodyBatteryMax)
        assertEquals(26, day.bodyBatteryMin)
    }

    @Test
    fun `merge overwrites with garmin values but keeps manually corrected fields and other data`() {
        val old = HealthDay(
            date = "2026-09-29",
            steps = 9000,
            stress = 40,
            restingHeartRate = 50,
            eveningNote = "Fin dag",
            manualOverrides = "restingHeartRate"
        )
        val merged = mergeGarminDay(old, summary().single())
        assertEquals(25, merged.stress)
        assertEquals(50, merged.restingHeartRate) // rettet manuelt - beholdes
        assertEquals(9000, merged.steps)
        assertEquals("Fin dag", merged.eveningNote)
        assertEquals(81, merged.sleepScore)
    }

    @Test
    fun `merge into an empty day creates it`() {
        val merged = mergeGarminDay(null, GarminDayData(LocalDate.of(2026, 9, 29), hrvMs = 44.0))
        assertEquals("2026-09-29", merged.date)
        assertEquals(44.0, merged.hrvMs!!, 0.01)
        assertNull(merged.stress)
    }

    @Test
    fun `non-fit content is ignored`() {
        assertTrue(extractFitFiles("hei".toByteArray()).isEmpty())
    }
}
