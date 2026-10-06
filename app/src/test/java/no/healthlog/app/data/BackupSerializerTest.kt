package no.healthlog.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupSerializerTest {

    @Test
    fun `round-trips a fully populated HealthDay through JSON`() {
        val original = HealthDay(
            date = "2026-09-16",
            weightKg = 81.3,
            waistCm = 93.1,
            hrvMs = 48.0,
            sleepMinutes = 455,
            sleepScore = 82,
            restingHeartRate = 52,
            bodyBatteryMax = 86,
            bodyBatteryMin = 31,
            stress = 27,
            steps = 8421,
            trainingMinutes = 47,
            morningNote = "Trene i dag",
            eveningNote = "Gikk bra",
            dayTag = "rest",
            source = "manual",
            updatedAt = 1234567890123
        )

        val json = healthDaysToJson(listOf(original))
        val restored = jsonToHealthDays(json)

        assertEquals(1, restored.size)
        assertEquals(original, restored.first())
    }

    /**
     * ALLE feltene i HealthDay satt til noe annet enn standardverdien. Legges det til et
     * nytt felt uten å oppdatere BackupSerializer, feiler denne - slik manualOverrides
     * tidligere manglet i sikkerhetskopien uten at noen test merket det.
     */
    @Test
    fun `every field of HealthDay survives a backup round-trip`() {
        val original = HealthDay(
            date = "2026-09-20",
            weightKg = 80.1,
            waistCm = 91.5,
            bodyFatPercent = 21.4,
            hrvMs = 51.5,
            sleepMinutes = 470,
            sleepScore = 84,
            restingHeartRate = 53,
            avgHeartRate = 68,
            activeMinutes = 42,
            deepSleepMinutes = 75,
            lightSleepMinutes = 250,
            remSleepMinutes = 110,
            sleepAwakeMinutes = 35,
            bodyBatteryMax = 90,
            bodyBatteryMin = 25,
            stress = 31,
            steps = 10234,
            trainingMinutes = 55,
            trainingIntensity = "moderate",
            trainingType = "running",
            trainingSessions = "running:55:moderate",
            morningNote = "Morgen",
            eveningNote = "Kveld",
            sleepReflection = "Sen middag, våknet tidlig",
            dayTag = "travel",
            nutritionLevel = "light",
            habitTags = "Alkohol|Sen middag",
            manualOverrides = "steps,sleepMinutes",
            source = "health_connect",
            updatedAt = 1726000000000
        )
        val restored = jsonToHealthDays(healthDaysToJson(listOf(original)))
        assertEquals(original, restored.single())
    }

    @Test
    fun `handles a day with only the required fields set`() {
        val original = HealthDay(date = "2026-09-01")
        val restored = jsonToHealthDays(healthDaysToJson(listOf(original)))

        assertEquals(1, restored.size)
        assertEquals("2026-09-01", restored.first().date)
        assertNull(restored.first().weightKg)
        assertNull(restored.first().morningNote)
    }

    @Test
    fun `empty days list produces valid JSON that parses back to an empty list`() {
        val restored = jsonToHealthDays(healthDaysToJson(emptyList()))
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `old backup format with a single note field falls back into eveningNote`() {
        // Slik sikkerhetskopier så ut FØR dagboken ble delt i to felt.
        val legacyJson = """
            {
              "version": 1,
              "days": [
                { "date": "2026-01-01", "note": "gammelt notat", "source": "manual", "updatedAt": 1000 }
              ]
            }
        """.trimIndent()

        val restored = jsonToHealthDays(legacyJson)

        assertEquals("gammelt notat", restored.first().eveningNote)
        assertNull(restored.first().morningNote)
    }

    @Test
    fun `rows with no date are skipped instead of crashing the whole import`() {
        val json = """
            {
              "version": 1,
              "days": [
                { "date": "2026-01-01", "source": "manual", "updatedAt": 1000 },
                { "weightKg": 80.0, "source": "manual", "updatedAt": 1000 }
              ]
            }
        """.trimIndent()

        val restored = jsonToHealthDays(json)

        assertEquals(1, restored.size)
        assertEquals("2026-01-01", restored.first().date)
    }

    @Test
    fun `settings survive a backup round-trip, but backup state and scheduled toggles are left out`() {
        val settings = mapOf(
            "goal_steps" to 9000,
            "max_heart_rate" to 188,
            "ai_coach_enabled" to true,
            "goal_weight_kg" to "80.0",
            "some_set" to setOf("b", "a"),
            "some_float" to 1.5f,
            "auto_backup_uri" to "content://x",
            "backup_last_success_time_millis" to 123L,
            "reminder_enabled_MORNING" to true,
            "reminder_hour_MORNING" to 7
        )
        val json = healthDaysToJson(emptyList(), settings)
        val restored = jsonToBackupSettings(json)
        assertEquals(
            mapOf(
                "goal_steps" to 9000,
                "max_heart_rate" to 188,
                "ai_coach_enabled" to true,
                "goal_weight_kg" to "80.0",
                "some_set" to setOf("a", "b"),
                "some_float" to 1.5f,
                "reminder_hour_MORNING" to 7
            ),
            restored
        )
        assertEquals(emptyMap<String, Any>(), jsonToBackupSettings(healthDaysToJson(emptyList())))
    }
}
