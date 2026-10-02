package no.healthlog.app.coach

import android.content.Context
import java.time.LocalDate

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_HISTORY = "coach_shown_history"
// Eldre versjoner lagret bare SISTE viste melding - leses én gang for å ikke miste den.
private const val KEY_LEGACY_DATE = "coach_last_shown_date"
private const val KEY_LEGACY_ID = "coach_last_shown_id"

/**
 * Husker når hver meldingstype sist ble vist, lagret som "ID=ÅÅÅÅ-MM-DD,ID=...".
 * Brukes av selectCoachMessage til å gi hver melding en pause etter at den er vist.
 */
object CoachPreferences {

    fun getHistory(context: Context): Map<CoachMessageId, LocalDate> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = mutableMapOf<CoachMessageId, LocalDate>()

        prefs.getString(KEY_HISTORY, null)?.split(",")?.forEach { entry ->
            val (idStr, dateStr) = entry.split("=").takeIf { it.size == 2 } ?: return@forEach
            val id = runCatching { CoachMessageId.valueOf(idStr) }.getOrNull() ?: return@forEach
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@forEach
            result[id] = date
        }

        if (result.isEmpty()) {
            val legacyId = prefs.getString(KEY_LEGACY_ID, null)?.let { runCatching { CoachMessageId.valueOf(it) }.getOrNull() }
            val legacyDate = prefs.getString(KEY_LEGACY_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (legacyId != null && legacyDate != null) result[legacyId] = legacyDate
        }
        return result
    }

    fun recordShown(context: Context, id: CoachMessageId, date: LocalDate) {
        val history = getHistory(context).toMutableMap()
        if (history[id] == date) return
        history[id] = date
        // Rydder bort oppføringer eldre enn 60 dager - ingen pause er så lang.
        val cutoff = date.minusDays(60)
        val serialized = history
            .filterValues { !it.isBefore(cutoff) }
            .entries
            .joinToString(",") { "${it.key.name}=${it.value}" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, serialized)
            .apply()
    }
}
