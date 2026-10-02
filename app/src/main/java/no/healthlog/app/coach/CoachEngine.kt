package no.healthlog.app.coach

import no.healthlog.app.DayTags
import no.healthlog.app.NutritionLevel
import no.healthlog.app.calculateHealthScore
import no.healthlog.app.data.HealthDay
import no.healthlog.app.data.habitTagSet
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

enum class CoachMessageId {
    MILESTONE_STREAK_RECORD_STEPS,
    MILESTONE_STREAK_RECORD_SLEEP,
    MILESTONE_FIRST_HRV_ABOVE_50,
    MILESTONE_TOTAL_DAYS,
    CONCERN_SLEEP_LOW,
    CONCERN_STRESS_HIGH,
    CONCERN_NO_ENTRY,
    CONCERN_HRV_DOWN,
    CONCERN_TRAINING_LOAD_SPIKE,
    CONCERN_AWAKE_UP,
    POSITIVE_HRV_UP,
    POSITIVE_RESTING_HR_DOWN,
    POSITIVE_SLEEP_STREAK,
    POSITIVE_STEPS_STREAK,
    POSITIVE_SCORE_STREAK,
    POSITIVE_DEEP_SLEEP_UP,
    POSITIVE_TRAINING_SLEEP_CORRELATION,
    POSITIVE_TRAINING_HRV_CORRELATION,
    POSITIVE_REST_DAY_STRESS_CORRELATION,
    POSITIVE_LOW_STRESS_SLEEP_CORRELATION,
    POSITIVE_LOW_STRESS_HRV_CORRELATION,
    POSITIVE_LIGHT_MEALS_SLEEP_CORRELATION,
    POSITIVE_LIGHT_MEALS_STRESS_CORRELATION,
    POSITIVE_ACTIVE_DAY_STRESS_CORRELATION,
    POSITIVE_GREAT_SLEEP_LAST_NIGHT,
    NUDGE_TRAINING_LOAD_DROP,
    HABIT_SLEEP_LOWER,
    HABIT_SLEEP_HIGHER,
    HABIT_HRV_LOWER,
    HABIT_HRV_HIGHER,
    NEUTRAL
}

/** Målingene "Hvorfor?"-visningen kan vise tall for. */
enum class CoachMetric { SLEEP_HOURS, HRV_MS, RESTING_HR, STRESS, STEPS, TRAINING_LOAD, DEEP_SLEEP_MIN, AWAKE_MIN, HEALTH_SCORE }

/** Dagtypene sammenhengene sammenligner mot "øvrige dager". */
enum class CoachCondition { TRAINED, REST_DAY, LOW_STRESS, LIGHT_MEALS, ACTIVE_DAY }

/**
 * Tallene bak en melding - vises når du trykker på AI-trener-kortet. Hver type har
 * sin egen tekstmal i TodayScreen (CoachWhyDialog).
 */
sealed class CoachFact {
    data class RecentVsPrevious(val metric: CoachMetric, val recent: Double, val previous: Double) : CoachFact()
    data class WithVsWithout(val condition: CoachCondition, val metric: CoachMetric, val comparison: GroupComparisonFact) : CoachFact()
    data class Streak(val current: Int, val previousRecord: Int) : CoachFact()
    data class LatestVsNormal(val metric: CoachMetric, val latest: Double, val normal: Double?) : CoachFact()
    data class RecentAverage(val metric: CoachMetric, val days: Int, val average: Double) : CoachFact()
    data class Last7VsNormal(val metric: CoachMetric, val last7: Double, val normal: Double) : CoachFact()
    data class DaysSince(val lastEntry: LocalDate) : CoachFact()
    data class BasedOnDays(val days: Int) : CoachFact()
    /** Verdien dagen etter dager MED [tag] mot dagen etter dager uten. */
    data class HabitNextDay(val tag: String, val metric: CoachMetric, val comparison: GroupComparisonFact) : CoachFact()
}

/** Offentlig speiling av GroupComparison (som er internal i CoachMath). */
data class GroupComparisonFact(val withMean: Double, val withDays: Int, val withoutMean: Double, val withoutDays: Int)

data class CoachMessage(
    val id: CoachMessageId,
    val intArg: Int? = null,
    val doubleArg: Double? = null,
    val facts: List<CoachFact> = emptyList(),
    /** Tekst som settes inn i meldingen - brukt for navnet på et vane-merke. */
    val textArg: String? = null
)

private fun sleepLow(day: HealthDay) = day.sleepMinutes != null && day.sleepMinutes < 360
private fun stressHigh(day: HealthDay) = day.stress != null && day.stress > 60
private fun trainedMeaningfully(day: HealthDay) = (day.trainingMinutes ?: 0) >= 20
// "Aktiv dag" utenom formell trening - 30+ aktive minutter fra skritt-raten alene
// (se HealthConnectManager), UAVHENGIG av trainedMeaningfully over. Fanger opp
// bevegelse gjennom dagen som aldri ble logget som en egen treningsøkt.
private fun activeDay(day: HealthDay) = (day.activeMinutes ?: 0) >= 30
private fun lowStress(day: HealthDay) = day.stress != null && day.stress <= 30

private fun average(days: List<HealthDay>, selector: (HealthDay) -> Number?): Double? {
    val values = days.mapNotNull { selector(it)?.toDouble() }
    return if (values.isEmpty()) null else values.average()
}

/** Lengste sammenhengende streak av [condition] som slutter nøyaktig på [endDate] (0 hvis den ikke holder der). */
internal fun streakEndingAt(byDate: Map<LocalDate, HealthDay>, endDate: LocalDate, condition: (HealthDay) -> Boolean): Int {
    var streak = 0
    var d = endDate
    while (true) {
        val day = byDate[d] ?: break
        if (!condition(day)) break
        streak++
        d = d.minusDays(1)
    }
    return streak
}

/** Lengste streak av [condition] fullført FØR [beforeDate] - brukes til å oppdage nye rekorder. */
internal fun longestStreakBefore(byDate: Map<LocalDate, HealthDay>, beforeDate: LocalDate, condition: (HealthDay) -> Boolean): Int {
    val dates = byDate.keys.filter { it.isBefore(beforeDate) }.sorted()
    var best = 0
    var current = 0
    var prev: LocalDate? = null
    for (d in dates) {
        val day = byDate.getValue(d)
        val consecutive = prev != null && d == prev.plusDays(1)
        current = if (condition(day)) (if (consecutive) current + 1 else 1) else 0
        best = max(best, current)
        prev = d
    }
    return best
}

/**
 * Hvor mange dager en meldingstype holdes tilbake etter at den er vist. Uten dette
 * vant alltid den høyest prioriterte regelen, og samme melding kom annenhver dag så
 * lenge regelen var sann. Bekymringer får kortest pause (de er viktigst å gjenta),
 * sammenhenger lengst (de endrer seg sakte og blir kjedelige fort).
 */
internal fun cooldownDays(id: CoachMessageId): Long = when (id) {
    // Også nøytral får pause - ellers vinner "ingenting å melde" hver gang alle
    // andre meldinger har pause, og vi er tilbake til samme tekst dag etter dag.
    CoachMessageId.NEUTRAL -> 2
    CoachMessageId.CONCERN_SLEEP_LOW,
    CoachMessageId.CONCERN_STRESS_HIGH,
    CoachMessageId.CONCERN_NO_ENTRY,
    CoachMessageId.CONCERN_HRV_DOWN,
    CoachMessageId.CONCERN_TRAINING_LOAD_SPIKE,
    CoachMessageId.CONCERN_AWAKE_UP -> 2
    CoachMessageId.MILESTONE_STREAK_RECORD_STEPS,
    CoachMessageId.MILESTONE_STREAK_RECORD_SLEEP,
    CoachMessageId.MILESTONE_FIRST_HRV_ABOVE_50,
    CoachMessageId.MILESTONE_TOTAL_DAYS -> 3
    CoachMessageId.POSITIVE_GREAT_SLEEP_LAST_NIGHT,
    CoachMessageId.POSITIVE_DEEP_SLEEP_UP -> 3
    CoachMessageId.POSITIVE_HRV_UP,
    CoachMessageId.POSITIVE_RESTING_HR_DOWN,
    CoachMessageId.POSITIVE_SLEEP_STREAK,
    CoachMessageId.POSITIVE_STEPS_STREAK,
    CoachMessageId.POSITIVE_SCORE_STREAK,
    CoachMessageId.NUDGE_TRAINING_LOAD_DROP -> 5
    CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION,
    CoachMessageId.POSITIVE_TRAINING_HRV_CORRELATION,
    CoachMessageId.POSITIVE_REST_DAY_STRESS_CORRELATION,
    CoachMessageId.POSITIVE_LOW_STRESS_SLEEP_CORRELATION,
    CoachMessageId.POSITIVE_LOW_STRESS_HRV_CORRELATION,
    CoachMessageId.POSITIVE_LIGHT_MEALS_SLEEP_CORRELATION,
    CoachMessageId.POSITIVE_LIGHT_MEALS_STRESS_CORRELATION,
    CoachMessageId.POSITIVE_ACTIVE_DAY_STRESS_CORRELATION,
    CoachMessageId.HABIT_SLEEP_LOWER,
    CoachMessageId.HABIT_SLEEP_HIGHER,
    CoachMessageId.HABIT_HRV_LOWER,
    CoachMessageId.HABIT_HRV_HIGHER -> 7
}

/**
 * Velger dagens melding blant [candidates] (som er i prioritert rekkefølge):
 * 1. En melding som allerede er vist I DAG beholdes, så kortet ikke bytter tekst
 *    hver gang nye data synkes inn i løpet av dagen.
 * 2. Ellers den høyest prioriterte som ikke har pause (se [cooldownDays]).
 * 3. Har alle pause (også den nøytrale): den "ekte" meldingen som ble vist for
 *    lengst siden - en gjentakelse er bedre enn samme "ingenting å melde" flere dager.
 */
internal fun selectCoachMessage(
    candidates: List<CoachMessage>,
    history: Map<CoachMessageId, LocalDate>,
    today: LocalDate
): CoachMessage {
    candidates.firstOrNull { history[it.id] == today }?.let { return it }

    candidates.firstOrNull { candidate ->
        val lastShown = history[candidate.id] ?: return@firstOrNull true
        ChronoUnit.DAYS.between(lastShown, today) >= cooldownDays(candidate.id)
    }?.let { return it }

    val nonNeutral = candidates.filter { it.id != CoachMessageId.NEUTRAL }
    return nonNeutral.minByOrNull { history[it.id] ?: LocalDate.MIN } ?: candidates.first()
}

/**
 * Velger nøyaktig én tilbakemelding for [today]. [history] er når hver meldingstype
 * sist ble vist (se CoachPreferences) - brukes til å variere meldingene.
 */
fun evaluateCoachMessage(
    allDays: List<HealthDay>,
    history: Map<CoachMessageId, LocalDate>,
    today: LocalDate = LocalDate.now(),
    sleepGoalMinutes: Int = 480,
    stepsGoal: Int = 8000
): CoachMessage? {
    val candidates = buildCoachCandidates(allDays, today, sleepGoalMinutes, stepsGoal) ?: return null
    return selectCoachMessage(candidates, history, today)
}

/**
 * Alle meldinger som er sanne i dag, i prioritert rekkefølge:
 * milepæl > bekymring > positiv trend > oppmuntring > sammenheng > nøytral.
 * Null hvis det er for lite data (under 3 dager) til å si noe i det hele tatt.
 */
internal fun buildCoachCandidates(
    allDays: List<HealthDay>,
    today: LocalDate,
    sleepGoalMinutes: Int,
    stepsGoal: Int
): List<CoachMessage>? {
    val byDate = byDate(allDays)
    if (byDate.size < 3) return null

    // Bruker DINE mål fra Innstillinger - tidligere var 8 timer / 8000 skritt hardkodet
    // her, slik at treneren kunne feire en streak som ikke stemte med Dagens mål.
    val sleepGoalMet = { day: HealthDay -> (day.sleepMinutes ?: 0) >= sleepGoalMinutes }
    val stepsGoalMet = { day: HealthDay -> (day.steps ?: 0) >= stepsGoal }

    val candidates = mutableListOf<CoachMessage>()

    val stepsStreakToday = streakEndingAt(byDate, today, stepsGoalMet)
    val sleepStreakToday = streakEndingAt(byDate, today, sleepGoalMet)
    val stepsRecordBefore = longestStreakBefore(byDate, today, stepsGoalMet)
    val sleepRecordBefore = longestStreakBefore(byDate, today, sleepGoalMet)
    val todayEntry = byDate[today]

    // --- Milepæler ---
    if (stepsStreakToday >= 5 && stepsStreakToday > stepsRecordBefore) {
        candidates += CoachMessage(
            CoachMessageId.MILESTONE_STREAK_RECORD_STEPS, stepsStreakToday,
            facts = listOf(CoachFact.Streak(stepsStreakToday, stepsRecordBefore))
        )
    }
    if (sleepStreakToday >= 5 && sleepStreakToday > sleepRecordBefore) {
        candidates += CoachMessage(
            CoachMessageId.MILESTONE_STREAK_RECORD_SLEEP, sleepStreakToday,
            facts = listOf(CoachFact.Streak(sleepStreakToday, sleepRecordBefore))
        )
    }
    todayEntry?.hrvMs?.let { todayHrv ->
        val everHighBefore = byDate.filterKeys { it.isBefore(today) }.values.any { (it.hrvMs ?: 0.0) > 50.0 }
        if (todayHrv > 50.0 && !everHighBefore) {
            val normal = normalFor(byDate, today) { it.hrvMs }?.mean
            candidates += CoachMessage(
                CoachMessageId.MILESTONE_FIRST_HRV_ABOVE_50,
                facts = listOf(CoachFact.LatestVsNormal(CoachMetric.HRV_MS, todayHrv, normal))
            )
        }
    }
    if (byDate.size in setOf(30, 100, 365)) {
        candidates += CoachMessage(CoachMessageId.MILESTONE_TOTAL_DAYS, byDate.size)
    }

    // --- Bekymringsmønstre ---
    if (streakEndingAt(byDate, today, ::sleepLow) >= 3) {
        val last3 = (0L..2L).mapNotNull { byDate[today.minusDays(it)] }
        candidates += CoachMessage(
            CoachMessageId.CONCERN_SLEEP_LOW,
            facts = listOfNotNull(average(last3) { it.sleepMinutes }?.let {
                CoachFact.RecentAverage(CoachMetric.SLEEP_HOURS, 3, it / 60.0)
            })
        )
    }
    if (streakEndingAt(byDate, today, ::stressHigh) >= 3) {
        val last3 = (0L..2L).mapNotNull { byDate[today.minusDays(it)] }
        candidates += CoachMessage(
            CoachMessageId.CONCERN_STRESS_HIGH,
            facts = listOfNotNull(average(last3) { it.stress }?.let {
                CoachFact.RecentAverage(CoachMetric.STRESS, 3, it)
            })
        )
    }
    byDate.keys.maxOrNull()?.let { lastEntryDate ->
        val daysSince = ChronoUnit.DAYS.between(lastEntryDate, today)
        if (daysSince >= 3) {
            candidates += CoachMessage(
                CoachMessageId.CONCERN_NO_ENTRY, daysSince.toInt(),
                facts = listOf(CoachFact.DaysSince(lastEntryDate))
            )
        }
    }

    val loadStatus = trainingLoadStatus(byDate, today)
    if (loadStatus != null && loadStatus.changePercent >= 50) {
        candidates += CoachMessage(
            CoachMessageId.CONCERN_TRAINING_LOAD_SPIKE, loadStatus.changePercent,
            facts = listOf(CoachFact.Last7VsNormal(CoachMetric.TRAINING_LOAD, loadStatus.last7, loadStatus.normalWeek))
        )
    }

    // Mer våken om natta siste uke enn normalt (4 uker før). Krever søvnfase-data,
    // som bare finnes når klokka registrerer faser og Health Connect deler dem.
    run {
        val last7Awake = (0L..6L).mapNotNull { byDate[today.minusDays(it)]?.sleepAwakeMinutes?.toDouble() }
        val normalAwake = (7L..34L).mapNotNull { d ->
            byDate[today.minusDays(d)]?.takeIf { !it.isAtypical() }?.sleepAwakeMinutes?.toDouble()
        }
        if (last7Awake.size >= 5 && normalAwake.size >= 14) {
            val recent = last7Awake.average()
            val normal = normalAwake.average()
            if (recent >= normal * 1.5 && recent - normal >= 10.0) {
                candidates += CoachMessage(
                    CoachMessageId.CONCERN_AWAKE_UP, recent.toInt(),
                    facts = listOf(CoachFact.Last7VsNormal(CoachMetric.AWAKE_MIN, recent, normal))
                )
            }
        }
    }

    val last14 = byDate.filter { (d, day) -> !d.isBefore(today.minusDays(13)) && !d.isAfter(today) && !day.isAtypical() }.values.toList()
    val prev14 = byDate.filter { (d, day) -> !d.isBefore(today.minusDays(27)) && d.isBefore(today.minusDays(13)) && !day.isAtypical() }.values.toList()

    val hrvLast14 = average(last14) { it.hrvMs }
    val hrvPrev14 = average(prev14) { it.hrvMs }
    val enoughHrvData = last14.count { it.hrvMs != null } >= 7 && prev14.count { it.hrvMs != null } >= 7
    val hrvChangeRatio = if (enoughHrvData && hrvLast14 != null && hrvPrev14 != null && hrvPrev14 > 0.0) {
        (hrvLast14 - hrvPrev14) / hrvPrev14
    } else {
        null
    }
    val hrvFacts = if (hrvLast14 != null && hrvPrev14 != null) {
        listOf(
            CoachFact.RecentVsPrevious(CoachMetric.HRV_MS, hrvLast14, hrvPrev14),
            CoachFact.BasedOnDays(last14.count { it.hrvMs != null } + prev14.count { it.hrvMs != null })
        )
    } else {
        emptyList()
    }
    if (hrvChangeRatio != null && hrvChangeRatio <= -0.10) {
        candidates += CoachMessage(CoachMessageId.CONCERN_HRV_DOWN, facts = hrvFacts)
    }

    // --- Positive trender ---
    if (hrvChangeRatio != null && hrvChangeRatio >= 0.10) {
        candidates += CoachMessage(CoachMessageId.POSITIVE_HRV_UP, facts = hrvFacts)
    }

    val hrLast14 = average(last14) { it.restingHeartRate }
    val hrPrev14 = average(prev14) { it.restingHeartRate }
    val enoughHrData = last14.count { it.restingHeartRate != null } >= 7 && prev14.count { it.restingHeartRate != null } >= 7
    if (enoughHrData && hrLast14 != null && hrPrev14 != null && hrPrev14 - hrLast14 >= 3.0) {
        candidates += CoachMessage(
            CoachMessageId.POSITIVE_RESTING_HR_DOWN,
            facts = listOf(
                CoachFact.RecentVsPrevious(CoachMetric.RESTING_HR, hrLast14, hrPrev14),
                CoachFact.BasedOnDays(last14.count { it.restingHeartRate != null } + prev14.count { it.restingHeartRate != null })
            )
        )
    }

    if (sleepStreakToday >= 5) {
        candidates += CoachMessage(
            CoachMessageId.POSITIVE_SLEEP_STREAK, sleepStreakToday,
            facts = listOf(CoachFact.Streak(sleepStreakToday, sleepRecordBefore))
        )
    }
    if (stepsStreakToday >= 5) {
        candidates += CoachMessage(
            CoachMessageId.POSITIVE_STEPS_STREAK, stepsStreakToday,
            facts = listOf(CoachFact.Streak(stepsStreakToday, stepsRecordBefore))
        )
    }
    val scoreOf = { day: HealthDay -> calculateHealthScore(day, sleepGoalMinutes, stepsGoal) }
    if (streakEndingAt(byDate, today) { scoreOf(it) >= 80 } >= 3) {
        val last3 = (0L..2L).mapNotNull { byDate[today.minusDays(it)] }
        candidates += CoachMessage(
            CoachMessageId.POSITIVE_SCORE_STREAK,
            facts = listOf(CoachFact.RecentAverage(CoachMetric.HEALTH_SCORE, 3, last3.map { scoreOf(it) }.average()))
        )
    }

    // Søvnen lagres på dagen natten slutter - "i natt" er altså dagens registrering.
    todayEntry?.sleepMinutes?.let { minutes ->
        if (minutes >= max(540, sleepGoalMinutes + 30)) {
            val normal = normalFor(byDate, today) { it.sleepMinutes?.let { m -> m / 60.0 } }?.mean
            candidates += CoachMessage(
                CoachMessageId.POSITIVE_GREAT_SLEEP_LAST_NIGHT, doubleArg = minutes / 60.0,
                facts = listOf(CoachFact.LatestVsNormal(CoachMetric.SLEEP_HOURS, minutes / 60.0, normal))
            )
        }
    }

    todayEntry?.deepSleepMinutes?.let { deep ->
        normalFor(byDate, today) { it.deepSleepMinutes?.toDouble() }?.let { normal ->
            if (deep >= normal.mean * 1.25 && deep - normal.mean >= 10.0) {
                candidates += CoachMessage(
                    CoachMessageId.POSITIVE_DEEP_SLEEP_UP, deep,
                    facts = listOf(CoachFact.LatestVsNormal(CoachMetric.DEEP_SLEEP_MIN, deep.toDouble(), normal.mean))
                )
            }
        }
    }

    // Mindre trening enn vanlig - bare en vennlig påminnelse, og aldri når du har
    // vært syk eller på reise den uka (da er det helt forventet).
    if (loadStatus != null && loadStatus.changePercent <= -50 && !loadStatus.hadAtypicalDaysLast7) {
        candidates += CoachMessage(
            CoachMessageId.NUDGE_TRAINING_LOAD_DROP, -loadStatus.changePercent,
            facts = listOf(CoachFact.Last7VsNormal(CoachMetric.TRAINING_LOAD, loadStatus.last7, loadStatus.normalWeek))
        )
    }

    // --- Vane-merker ---
    // Dine egne merker mot søvn og HRV natten etter. Her vises BEGGE retninger (i
    // motsetning til de faste sammenhengene under) - at alkohol gir dårligere søvn er
    // nettopp det nyttige å vite. Kun det sterkeste utslaget per måling blir kandidat.
    val allTags = byDate.values.flatMap { it.habitTagSet() }.toSet()
    fun strongestHabitEffect(
        metric: CoachMetric,
        selector: (HealthDay) -> Double?,
        minDiff: Double,
        lowerId: CoachMessageId,
        higherId: CoachMessageId
    ) {
        val best = allTags.mapNotNull { tag ->
            compareNextDay(byDate, today, tag, selector = selector)?.let { tag to it }
        }.maxByOrNull { (_, c) -> kotlin.math.abs(c.withMean - c.withoutMean) } ?: return
        val (tag, c) = best
        val diff = c.withMean - c.withoutMean
        if (kotlin.math.abs(diff) < minDiff) return
        candidates += CoachMessage(
            if (diff < 0) lowerId else higherId,
            doubleArg = kotlin.math.abs(diff),
            textArg = tag,
            facts = listOf(
                CoachFact.HabitNextDay(tag, metric, GroupComparisonFact(c.withMean, c.withDays, c.withoutMean, c.withoutDays)),
                CoachFact.BasedOnDays(c.withDays + c.withoutDays)
            )
        )
    }
    strongestHabitEffect(
        CoachMetric.SLEEP_HOURS, { it.sleepMinutes?.let { m -> m / 60.0 } }, 0.3,
        CoachMessageId.HABIT_SLEEP_LOWER, CoachMessageId.HABIT_SLEEP_HIGHER
    )
    strongestHabitEffect(
        CoachMetric.HRV_MS, { it.hrvMs }, 3.0,
        CoachMessageId.HABIT_HRV_LOWER, CoachMessageId.HABIT_HRV_HIGHER
    )

    // --- Sammenhenger ---
    // Vises kun når sammenhengen peker i en oppmuntrende retning - peker den motsatt
    // vei, utelates den heller enn å vises som noe negativt om trening/hvile.
    fun correlation(
        id: CoachMessageId,
        condition: CoachCondition,
        test: (HealthDay) -> Boolean,
        metric: CoachMetric,
        selector: (HealthDay) -> Double?,
        higherIsBetter: Boolean,
        minDiff: Double
    ) {
        val result = compareGroups(byDate, today, test, selector = selector) ?: return
        val diff = if (higherIsBetter) result.withMean - result.withoutMean else result.withoutMean - result.withMean
        if (diff < minDiff) return
        candidates += CoachMessage(
            id, doubleArg = diff,
            facts = listOf(
                CoachFact.WithVsWithout(
                    condition, metric,
                    GroupComparisonFact(result.withMean, result.withDays, result.withoutMean, result.withoutDays)
                ),
                CoachFact.BasedOnDays(result.withDays + result.withoutDays)
            )
        )
    }

    val sleepHours = { day: HealthDay -> day.sleepMinutes?.let { it / 60.0 } }
    val hrv = { day: HealthDay -> day.hrvMs }
    val stress = { day: HealthDay -> day.stress?.toDouble() }

    correlation(CoachMessageId.POSITIVE_TRAINING_SLEEP_CORRELATION, CoachCondition.TRAINED, ::trainedMeaningfully, CoachMetric.SLEEP_HOURS, sleepHours, true, 0.3)
    correlation(CoachMessageId.POSITIVE_TRAINING_HRV_CORRELATION, CoachCondition.TRAINED, ::trainedMeaningfully, CoachMetric.HRV_MS, hrv, true, 3.0)
    correlation(CoachMessageId.POSITIVE_REST_DAY_STRESS_CORRELATION, CoachCondition.REST_DAY, { it.dayTag == DayTags.REST }, CoachMetric.STRESS, stress, false, 6.0)
    correlation(CoachMessageId.POSITIVE_LOW_STRESS_SLEEP_CORRELATION, CoachCondition.LOW_STRESS, ::lowStress, CoachMetric.SLEEP_HOURS, sleepHours, true, 0.3)
    correlation(CoachMessageId.POSITIVE_LOW_STRESS_HRV_CORRELATION, CoachCondition.LOW_STRESS, ::lowStress, CoachMetric.HRV_MS, hrv, true, 3.0)
    correlation(CoachMessageId.POSITIVE_LIGHT_MEALS_SLEEP_CORRELATION, CoachCondition.LIGHT_MEALS, { it.nutritionLevel == NutritionLevel.LIGHT }, CoachMetric.SLEEP_HOURS, sleepHours, true, 0.3)
    correlation(CoachMessageId.POSITIVE_LIGHT_MEALS_STRESS_CORRELATION, CoachCondition.LIGHT_MEALS, { it.nutritionLevel == NutritionLevel.LIGHT }, CoachMetric.STRESS, stress, false, 6.0)
    // Bevisst en EGEN sammenligning fra trenings-sammenhengene over - activeDay fanger
    // opp bevegelse gjennom dagen som aldri ble logget som en formell treningsøkt.
    correlation(CoachMessageId.POSITIVE_ACTIVE_DAY_STRESS_CORRELATION, CoachCondition.ACTIVE_DAY, ::activeDay, CoachMetric.STRESS, stress, false, 6.0)

    // --- Nøytral fallback, alltid tilgjengelig ---
    candidates += CoachMessage(CoachMessageId.NEUTRAL)
    return candidates
}
