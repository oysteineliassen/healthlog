package no.healthlog.app

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_THEME_MODE = "theme_mode"

object ThemePreferences {

    fun get(context: Context): ThemeMode {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(stored ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(context: Context, mode: ThemeMode) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }
}
