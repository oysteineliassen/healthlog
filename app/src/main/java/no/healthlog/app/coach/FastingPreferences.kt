package no.healthlog.app.coach

import android.content.Context

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_PERIODS = "fasting_periods"

object FastingPreferences {
    fun get(context: Context): List<FastingPeriod> =
        parseFastingPeriods(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PERIODS, null))

    fun set(context: Context, periods: List<FastingPeriod>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PERIODS, periods.serialize()).apply()
    }
}
