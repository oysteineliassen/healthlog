package no.healthlog.app.coach

import no.healthlog.app.NutritionLevel
import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiCoachPromptTest {

    private val today = LocalDate.of(2026, 9, 29)
    private val goals = AiCoachGoals(sleepGoalMinutes = 480, stepsGoal = 8000, trainingGoalMinutes = 150, weightGoalKg = 80.0)

    @Test
    fun `prompt contains goals, requested sections and one data row per day in the period`() {
        val days = listOf(
            HealthDay(date = "2026-09-29", sleepMinutes = 350, sleepScore = 81, hrvMs = 45.0, stress = 25),
            HealthDay(date = "2026-09-28", steps = 9000),
            HealthDay(date = "2026-08-01", steps = 1) // utenfor 28 dager
        )
        val prompt = buildAiCoachPrompt(days, today, goals, periodDays = 28)
        assertTrue(prompt.contains("Søvn: 8.0 timer"))
        assertTrue(prompt.contains("Vekt: 80.0 kg"))
        AI_COACH_SECTIONS.forEach { assertTrue(it, prompt.contains("## $it")) }
        assertTrue(prompt.contains("2026-09-29;5.83;81;;;;45;;25;"))
        assertTrue(prompt.contains("2026-09-28;"))
        assertFalse(prompt.contains("2026-08-01"))
        // Eldste dag først
        assertTrue(prompt.indexOf("2026-09-28;") < prompt.indexOf("2026-09-29;"))
    }

    @Test
    fun `notes including the sleep reflection are listed, on one line and without semicolons`() {
        val days = listOf(
            HealthDay(
                date = "2026-09-29",
                sleepReflection = "Sen middag;\nmye stress",
                eveningNote = "Bra økt",
                nutritionLevel = NutritionLevel.FASTING
            )
        )
        val prompt = buildAiCoachPrompt(days, today, goals)
        assertTrue(prompt.contains("2026-09-29: Natten: Sen middag, mye stress | Kveld: Bra økt"))
        assertTrue(prompt.contains(";fasting;"))
    }

    @Test
    fun `answer with headings, bullets and bold text is split into blocks`() {
        val answer = """
            ## Kort oppsummering
            Du sov **kort** i natt, men HRV er stabil.

            ## Tre konkrete råd for neste uke
            1. Legg deg før 23.
            - Unngå sen middag
            * Gå en tur etter jobb
        """.trimIndent()
        val blocks = parseAiAnswer(answer)
        assertEquals(
            listOf(
                AiAnswerBlock.Heading("Kort oppsummering"),
                AiAnswerBlock.Paragraph("Du sov kort i natt, men HRV er stabil."),
                AiAnswerBlock.Heading("Tre konkrete råd for neste uke"),
                AiAnswerBlock.Bullet("Legg deg før 23."),
                AiAnswerBlock.Bullet("Unngå sen middag"),
                AiAnswerBlock.Bullet("Gå en tur etter jobb")
            ),
            blocks
        )
    }

    @Test
    fun `bold line without hashes is treated as a heading`() {
        assertEquals(AiAnswerBlock.Heading("Søvn"), parseAiAnswer("**Søvn**").single())
    }
}
