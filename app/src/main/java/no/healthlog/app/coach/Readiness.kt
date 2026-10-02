package no.healthlog.app.coach

import no.healthlog.app.data.HealthDay
import java.time.LocalDate
import kotlin.math.max

/** Grønn / gul / rød på Dagsform-kortet. */
enum class ReadinessLevel { GOOD, MODERATE, LOW }

enum class ReadinessSignalType { HRV, RESTING_HR, SLEEP, TRAINING_LOAD }

/** Hvordan ett signal står i dag sammenlignet med din normal (eller søvnmålet ditt). */
enum class ReadinessStatus { BETTER, NORMAL, WORSE }

/**
 * Ett av signalene bak dagsformen. [reference] er din normal (HRV, hvilepuls),
 * søvnmålet ditt (søvn) eller en normal uke (treningsbelastning). For
 * treningsbelastning er [value] endringen i prosent.
 */
data class ReadinessSignal(
    val type: ReadinessSignalType,
    val status: ReadinessStatus,
    val value: Double,
    val reference: Double
)

data class Readiness(
    val level: ReadinessLevel,
    /** true når minst ett signal er BEDRE enn normalt og ingen er dårligere - "klar for hard økt". */
    val isStrong: Boolean,
    val signals: List<ReadinessSignal>
)

/**
 * Dagsform for [today]: nattens søvn og dagens HRV og hvilepuls, hver sammenlignet
 * med DIN egen normal (siste 28 dager, uten syk/reise-dager) - ikke med generelle
 * tall. Samme prinsipp som "Body Battery"/"Training Readiness" hos Garmin, men enklere
 * og helt åpent: kortet viser nøyaktig hvilke tall vurderingen bygger på.
 *
 * Returnerer null når det er for lite data til å si noe (minst to av signalene må
 * finnes for i dag), heller enn å gi et råd på sviktende grunnlag.
 */
fun calculateReadiness(
    allDays: List<HealthDay>,
    today: LocalDate = LocalDate.now(),
    sleepGoalMinutes: Int = 480
): Readiness? {
    val byDate = byDate(allDays)
    val todayEntry = byDate[today] ?: return null
    val signals = mutableListOf<ReadinessSignal>()

    // HRV - høyere er bedre. Terskler i standardavvik, men med et minimum i ms, slik at
    // en svært jevn normal (lavt standardavvik) ikke gjør 1-2 ms forskjell til et utslag.
    todayEntry.hrvMs?.let { hrv ->
        normalFor(byDate, today) { it.hrvMs }?.let { normal ->
            val upper = normal.mean + max(0.5 * normal.stdDev, 3.0)
            val lower = normal.mean - max(1.0 * normal.stdDev, 5.0)
            val status = when {
                hrv >= upper -> ReadinessStatus.BETTER
                hrv <= lower -> ReadinessStatus.WORSE
                else -> ReadinessStatus.NORMAL
            }
            signals += ReadinessSignal(ReadinessSignalType.HRV, status, hrv, normal.mean)
        }
    }

    // Hvilepuls - lavere er bedre. Forhøyet hvilepuls er et av de mest pålitelige tegnene
    // på at kroppen ikke har hentet seg inn (sykdom på vei, dårlig restitusjon, alkohol).
    todayEntry.restingHeartRate?.let { rhr ->
        normalFor(byDate, today) { it.restingHeartRate?.toDouble() }?.let { normal ->
            val upper = normal.mean + max(1.0 * normal.stdDev, 3.0)
            val lower = normal.mean - max(0.5 * normal.stdDev, 2.0)
            val status = when {
                rhr >= upper -> ReadinessStatus.WORSE
                rhr <= lower -> ReadinessStatus.BETTER
                else -> ReadinessStatus.NORMAL
            }
            signals += ReadinessSignal(ReadinessSignalType.RESTING_HR, status, rhr.toDouble(), normal.mean)
        }
    }

    // Søvn i natt (lagres på dagen natten slutter, altså i dag) - mot søvnmålet ditt.
    todayEntry.sleepMinutes?.let { minutes ->
        val status = when {
            minutes >= sleepGoalMinutes -> ReadinessStatus.BETTER
            minutes >= sleepGoalMinutes - 60 -> ReadinessStatus.NORMAL
            else -> ReadinessStatus.WORSE
        }
        signals += ReadinessSignal(ReadinessSignalType.SLEEP, status, minutes / 60.0, sleepGoalMinutes / 60.0)
    }

    if (signals.size < 2) return null

    // Treningsbelastning teller bare når den er uvanlig høy - lav belastning er ikke et
    // tegn på dårlig form, og skal ikke gjøre dagsformen grønnere heller.
    trainingLoadStatus(byDate, today)?.let { load ->
        if (load.changePercent >= 50) {
            signals += ReadinessSignal(
                ReadinessSignalType.TRAINING_LOAD,
                ReadinessStatus.WORSE,
                load.changePercent.toDouble(),
                load.normalWeek
            )
        }
    }

    val worse = signals.count { it.status == ReadinessStatus.WORSE }
    val better = signals.count { it.status == ReadinessStatus.BETTER }
    val level = when {
        worse >= 2 -> ReadinessLevel.LOW
        worse == 1 -> ReadinessLevel.MODERATE
        else -> ReadinessLevel.GOOD
    }
    return Readiness(level, isStrong = level == ReadinessLevel.GOOD && better >= 1, signals = signals)
}
