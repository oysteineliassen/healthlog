package no.healthlog.app.coach

import no.healthlog.app.DayTags
import no.healthlog.app.TrainingIntensity
import no.healthlog.app.data.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoachEngineTest {

    private val today = LocalDate.of(2026, 9, 27)

    /** [count] dager bakover fra og med i dag, bygget av [make] (0 = i dag). */
    private fun days(count: Int, make: (daysAgo: Int, date: LocalDate) -> HealthDay?): List<HealthDay> =
        (0 until count).mapNotNull { ago -> make(ago, today.minusDays(ago.toLong())) }

    private fun ids(list: List<CoachMessage>?) = list.orEmpty().map { it.id }

    @Test
    fun `streak record follows the user's own steps goal, not a hardcoded 8000`() {
        // 6000 skritt hver dag i 6 dager, én dag uten før det - rekord med mål 5000, ikke med 8000.
        val data = days(7) { ago, date -> HealthDay(date = date.toString(), steps = if (ago == 6) 100 else 6000) }
        val withLowGoal = buildCoachCandidates(data, today, sleepGoalMinutes = 480, stepsGoal = 5000)
        val withDefaultGoal = buildCoachCandidates(data, today, sleepGoalMinutes = 480, stepsGoal = 8000)
        assertTrue(CoachMessageId.MILESTONE_STREAK_RECORD_STEPS in ids(withLowGoal))
        assertFalse(CoachMessageId.MILESTONE_STREAK_RECORD_STEPS in ids(withDefaultGoal))
    }

    @Test
    fun `message shown today is kept, so the card does not change during the day`() {
        val a = CoachMessage(CoachMessageId.CONCERN_SLEEP_LOW)
        val b = CoachMessage(CoachMessageId.POSITIVE_HRV_UP)
        val chosen = selectCoachMessage(listOf(a, b), mapOf(CoachMessageId.POSITIVE_HRV_UP to today), today)
        assertEquals(CoachMessageId.POSITIVE_HRV_UP, chosen.id)
    }

    @Test
    fun `top candidate on cooldown is skipped for the next one`() {
        val a = CoachMessage(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION) // 7 dagers pause
        val b = CoachMessage(CoachMessageId.POSITIVE_STEPS_STREAK)
        val neutral = CoachMessage(CoachMessageId.NEUTRAL)
        val history = mapOf(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION to today.minusDays(3))
        assertEquals(CoachMessageId.POSITIVE_STEPS_STREAK, selectCoachMessage(listOf(a, b, neutral), history, today).id)
    }

    @Test
    fun `cooldown expires after the configured number of days`() {
        val a = CoachMessage(CoachMessageId.CONCERN_SLEEP_LOW) // 2 dagers pause
        val b = CoachMessage(CoachMessageId.POSITIVE_STEPS_STREAK)
        val history = mapOf(CoachMessageId.CONCERN_SLEEP_LOW to today.minusDays(2))
        assertEquals(CoachMessageId.CONCERN_SLEEP_LOW, selectCoachMessage(listOf(a, b), history, today).id)
    }

    @Test
    fun `when everything is on cooldown, the least recently shown beats the neutral message`() {
        val a = CoachMessage(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION)
        val b = CoachMessage(CoachMessageId.POSITIVE_TRAINING_HRV_CORRELATION)
        val neutral = CoachMessage(CoachMessageId.NEUTRAL)
        val history = mapOf(
            CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION to today.minusDays(1),
            CoachMessageId.POSITIVE_TRAINING_HRV_CORRELATION to today.minusDays(4),
            CoachMessageId.NEUTRAL to today.minusDays(1)
        )
        // Også nøytral har pause (vist i går) - da gjentas den eldste "ekte" meldingen.
        val chosen = selectCoachMessage(listOf(a, b, neutral), history, today)
        assertEquals(CoachMessageId.POSITIVE_TRAINING_HRV_CORRELATION, chosen.id)
    }

    @Test
    fun `neutral is shown when all real messages are on cooldown and it has not been shown lately`() {
        val a = CoachMessage(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION)
        val neutral = CoachMessage(CoachMessageId.NEUTRAL)
        val history = mapOf(
            CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION to today.minusDays(1),
            CoachMessageId.NEUTRAL to today.minusDays(5)
        )
        assertEquals(CoachMessageId.NEUTRAL, selectCoachMessage(listOf(a, neutral), history, today).id)
    }

    @Test
    fun `a lone correlation is not repeated every other day when everything is on cooldown`() {
        // Det rapporterte scenariet: søvn/trening-sammenhengen er eneste ekte melding.
        val a = CoachMessage(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION) // 7 dagers pause
        val neutral = CoachMessage(CoachMessageId.NEUTRAL)
        val history = mutableMapOf<CoachMessageId, LocalDate>()
        val shown = (0L until 14L).map { offset ->
            val day = today.plusDays(offset)
            selectCoachMessage(listOf(a, neutral), history, day).also { history[it.id] = day }.id
        }
        // Gammel logikk: annenhver dag (7 av 14). Nå minst halve pausen mellom hver gang.
        assertTrue(shown.count { it == CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION } <= 4)
        assertFalse(shown.zipWithNext().any { (x, y) -> x == y && x == CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION })
    }

    @Test
    fun `a single candidate on cooldown is still shown`() {
        val a = CoachMessage(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION)
        val history = mapOf(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION to today.minusDays(1))
        assertEquals(a.id, selectCoachMessage(listOf(a), history, today).id)
    }

    private fun steadyTraining(minutes: Int, intensity: String, lastWeekMinutes: Int, lastWeekIntensity: String, sickAgo: Int? = null) =
        days(35) { ago, date ->
            HealthDay(
                date = date.toString(),
                steps = 7000,
                trainingMinutes = if (ago <= 6) lastWeekMinutes else minutes,
                trainingIntensity = if (ago <= 6) lastWeekIntensity else intensity,
                dayTag = if (ago == sickAgo) DayTags.SICK else null
            )
        }

    @Test
    fun `training load spike is reported with the percentage increase`() {
        // Normal uke: 7 x 30 min moderat = 315. Siste uke: 7 x 60 min hard = 840 -> +166 %.
        val data = steadyTraining(30, TrainingIntensity.MODERATE, 60, TrainingIntensity.VIGOROUS)
        val spike = buildCoachCandidates(data, today, 480, 8000)!!.first { it.id == CoachMessageId.CONCERN_TRAINING_LOAD_SPIKE }
        assertEquals(166, spike.intArg)
        val fact = spike.facts.single() as CoachFact.Last7VsNormal
        assertEquals(840.0, fact.last7, 0.01)
        assertEquals(315.0, fact.normal, 0.01)
    }

    @Test
    fun `training load drop nudges, but not in a week with a sick day`() {
        val quiet = steadyTraining(40, TrainingIntensity.MODERATE, 0, TrainingIntensity.MODERATE)
        assertTrue(CoachMessageId.NUDGE_TRAINING_LOAD_DROP in ids(buildCoachCandidates(quiet, today, 480, 8000)))

        val quietButSick = steadyTraining(40, TrainingIntensity.MODERATE, 0, TrainingIntensity.MODERATE, sickAgo = 2)
        assertFalse(CoachMessageId.NUDGE_TRAINING_LOAD_DROP in ids(buildCoachCandidates(quietButSick, today, 480, 8000)))
    }

    @Test
    fun `no training load message when there is too little history`() {
        val data = steadyTraining(30, TrainingIntensity.MODERATE, 60, TrainingIntensity.VIGOROUS).take(20)
        assertNull(trainingLoadStatus(byDate(data), today))
    }

    @Test
    fun `correlation needs five days in each group`() {
        // 4 treningsdager med mye søvn, 20 uten - for få treningsdager til å si noe.
        val data = days(24) { ago, date ->
            HealthDay(date = date.toString(), trainingMinutes = if (ago < 4) 45 else 0, sleepMinutes = if (ago < 4) 540 else 420)
        }
        assertFalse(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION in ids(buildCoachCandidates(data, today, 480, 8000)))
    }

    @Test
    fun `correlation reports group sizes and ignores sick days`() {
        // Trening annenhver dag; natten ETTER en treningsdag gir 8 t søvn, ellers 7 t.
        val data = days(20) { ago, date ->
            val trained = ago % 2 == 0
            val trainedYesterday = (ago + 1) % 2 == 0
            HealthDay(
                date = date.toString(),
                trainingMinutes = if (trained) 45 else 0,
                sleepMinutes = if (trainedYesterday) 480 else 420,
                // To syke dager med ekstremt kort søvn - skal ikke telle med.
                dayTag = if (ago == 1 || ago == 3) DayTags.SICK else null
            )
        }.map { if (it.dayTag == DayTags.SICK) it.copy(sleepMinutes = 120) else it }

        val message = buildCoachCandidates(data, today, 480, 8000)!!
            .first { it.id == CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION }
        val fact = message.facts.filterIsInstance<CoachFact.WithVsWithout>().single()
        assertTrue(fact.nextDay)
        // Dag-par der verken dagen eller natten etter er syk: 5..19 dager siden.
        assertEquals(7, fact.comparison.withDays)
        assertEquals(8, fact.comparison.withoutDays)
        assertEquals(1.0, message.doubleArg!!, 0.01) // 8 t mot 7 t - syke dager ville trukket "uten" ned
    }

    @Test
    fun `sleep the night before training does not count as an effect of training`() {
        // Lang søvn lagret på SAMME dag som treningen (natten før økten), vanlig søvn
        // natten etter. Den gamle utregningen meldte "du sover mer når du trener".
        val data = days(30) { ago, date ->
            val trained = ago % 2 == 0
            HealthDay(date = date.toString(), trainingMinutes = if (trained) 45 else 0, sleepMinutes = if (trained) 500 else 420)
        }
        assertFalse(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION in ids(buildCoachCandidates(data, today, 480, 8000)))
    }

    @Test
    fun `deep sleep above normal is noticed`() {
        val data = days(20) { ago, date ->
            HealthDay(date = date.toString(), sleepMinutes = 450, deepSleepMinutes = if (ago == 0) 95 else 60)
        }
        val message = buildCoachCandidates(data, today, 480, 8000)!!.first { it.id == CoachMessageId.POSITIVE_DEEP_SLEEP_UP }
        assertEquals(95, message.intArg)
    }

    @Test
    fun `more awake time this week than normal is flagged`() {
        val data = days(35) { ago, date ->
            HealthDay(date = date.toString(), sleepMinutes = 450, sleepAwakeMinutes = if (ago <= 6) 45 else 20)
        }
        val message = buildCoachCandidates(data, today, 480, 8000)!!.first { it.id == CoachMessageId.CONCERN_AWAKE_UP }
        assertEquals(45, message.intArg)
    }

    @Test
    fun `great sleep last night uses today's entry, since sleep is stored on the day it ends`() {
        val data = days(5) { ago, date -> HealthDay(date = date.toString(), sleepMinutes = if (ago == 0) 560 else 420) }
        assertTrue(CoachMessageId.POSITIVE_GREAT_SLEEP_LAST_NIGHT in ids(buildCoachCandidates(data, today, 480, 8000)))
    }

    @Test
    fun `too little data gives no message at all`() {
        val data = days(2) { _, date -> HealthDay(date = date.toString(), steps = 9000) }
        assertNull(evaluateCoachMessage(data, emptyMap(), today))
        assertNotNull(evaluateCoachMessage(days(3) { _, date -> HealthDay(date = date.toString()) }, emptyMap(), today))
    }

    /** 30 dager der [tag] er satt annenhver dag; natten etter merket gir [sleepAfterTag] min søvn. */
    private fun habitData(tag: String, sleepAfterTag: Int, normalSleep: Int = 450, hrvAfterTag: Double? = null) =
        days(30) { ago, date ->
            val taggedYesterday = (ago + 1) % 2 == 0
            HealthDay(
                date = date.toString(),
                habitTags = if (ago % 2 == 0) tag else "",
                sleepMinutes = if (taggedYesterday) sleepAfterTag else normalSleep,
                hrvMs = if (taggedYesterday && hrvAfterTag != null) hrvAfterTag else 50.0
            )
        }

    @Test
    fun `habit tag is compared with the night after, and negative effects are shown`() {
        val data = habitData("Alkohol", sleepAfterTag = 390, hrvAfterTag = 41.0)
        val candidates = buildCoachCandidates(data, today, 480, 8000)!!
        val sleep = candidates.first { it.id == CoachMessageId.HABIT_SLEEP_LOWER }
        assertEquals("Alkohol", sleep.textArg)
        assertEquals(1.0, sleep.doubleArg!!, 0.01) // 7,5 t mot 6,5 t
        val hrv = candidates.first { it.id == CoachMessageId.HABIT_HRV_LOWER }
        assertEquals(9.0, hrv.doubleArg!!, 0.01)
    }

    @Test
    fun `habit with a positive effect is reported as higher`() {
        val data = habitData("Kveldstur", sleepAfterTag = 500)
        assertTrue(CoachMessageId.HABIT_SLEEP_HIGHER in ids(buildCoachCandidates(data, today, 480, 8000)))
    }

    @Test
    fun `days where habit tags were never registered do not count as without the habit`() {
        // Merket registrert kun 6 dager (alle med merket) - de andre dagene er ukjente
        // (habitTags = null), så det finnes ingen "uten"-gruppe å sammenligne med.
        val data = days(30) { ago, date ->
            HealthDay(
                date = date.toString(),
                habitTags = if (ago % 5 == 0) "Alkohol" else null,
                sleepMinutes = if ((ago + 1) % 5 == 0) 360 else 450
            )
        }
        assertFalse(CoachMessageId.HABIT_SLEEP_LOWER in ids(buildCoachCandidates(data, today, 480, 8000)))
    }

    @Test
    fun `small habit effects are ignored`() {
        val data = habitData("Kaffe", sleepAfterTag = 440) // bare 10 min forskjell
        val found = ids(buildCoachCandidates(data, today, 480, 8000))
        assertFalse(CoachMessageId.HABIT_SLEEP_LOWER in found)
        assertFalse(CoachMessageId.HABIT_SLEEP_HIGHER in found)
    }
}
