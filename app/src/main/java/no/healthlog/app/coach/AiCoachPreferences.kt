package no.healthlog.app.coach

import android.content.Context
import java.time.LocalDate

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_ENABLED = "ai_coach_enabled"
private const val KEY_ANSWER = "ai_coach_answer"
private const val KEY_ANSWER_DATE = "ai_coach_answer_date"
private const val KEY_PERIOD_DAYS = "ai_coach_period_days"

/** Valgfri ekstern AI-trener (prompt ut, svar inn) - av som standard. */
object AiCoachPreferences {

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getPeriodDays(context: Context): Int = prefs(context).getInt(KEY_PERIOD_DAYS, 28)

    fun setPeriodDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_PERIOD_DAYS, days).apply()
    }

    fun getAnswer(context: Context): Pair<String, LocalDate?>? {
        val text = prefs(context).getString(KEY_ANSWER, null)?.takeIf { it.isNotBlank() } ?: return null
        val date = prefs(context).getString(KEY_ANSWER_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return text to date
    }

    fun setAnswer(context: Context, text: String?, date: LocalDate = LocalDate.now()) {
        prefs(context).edit()
            .putString(KEY_ANSWER, text)
            .putString(KEY_ANSWER_DATE, if (text == null) null else date.toString())
            .apply()
    }
}
