package no.healthlog.app.backup

import android.content.Context
import no.healthlog.app.data.isBackedUpSetting

private const val PREFS_NAME = "healthlog_prefs"

/** Leser/skriver appens innstillinger (mål, makspuls, vaner, kort, AI-svar ...) for sikkerhetskopien. */
object SettingsBackup {

    fun export(context: Context): Map<String, *> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all

    fun restore(context: Context, settings: Map<String, Any>) {
        if (settings.isEmpty()) return
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        settings.filterKeys(::isBackedUpSetting).forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.apply()
    }
}
