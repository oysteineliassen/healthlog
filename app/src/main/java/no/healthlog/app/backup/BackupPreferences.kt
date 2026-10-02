package no.healthlog.app.backup

import android.content.Context
import android.net.Uri
import android.util.Log

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_BACKUP_URI = "auto_backup_uri"
private const val KEY_CONSECUTIVE_FAILURES = "backup_consecutive_failures"
private const val KEY_ALREADY_NOTIFIED = "backup_failure_already_notified"
private const val KEY_LAST_ERROR_MESSAGE = "backup_last_error_message"
private const val KEY_LAST_ERROR_TIME = "backup_last_error_time_millis"
private const val KEY_LAST_SUCCESS_TIME = "backup_last_success_time_millis"
private const val TAG = "BackupPreferences"

object BackupPreferences {

    fun get(context: Context): Uri? {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BACKUP_URI, null)
        return stored?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    fun set(context: Context, uri: Uri?) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKUP_URI, uri?.toString())
            .apply()
    }

    /** Antall ganger auto-backup har feilet PÅ RAD - nullstilles ved neste vellykkede skriving. */
    fun getConsecutiveFailures(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CONSECUTIVE_FAILURES, 0)

    /**
     * Øker telleren og returnerer den nye verdien, slik at kalleren kan avgjøre om det er nok
     * til å varsle. [errorMessage] lagres samtidig, slik at brukeren kan se ÅRSAKEN i
     * Innstillinger etterpå - uten dette må man fange logcat i akkurat det sekundet en
     * bakgrunnsjobb (som kan kjøre når som helst i døgnet) faktisk feiler, noe som i praksis
     * ikke er mulig for de fleste brukere.
     */
    fun recordFailure(context: Context, errorMessage: String? = null): Int {
        val count = getConsecutiveFailures(context) + 1
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CONSECUTIVE_FAILURES, count)
            .putString(KEY_LAST_ERROR_MESSAGE, errorMessage ?: "Ukjent feil")
            .putLong(KEY_LAST_ERROR_TIME, System.currentTimeMillis())
            .apply()
        return count
    }

    /** Feilmeldingen fra siste mislykkede forsøk, og når det skjedde - vises i Innstillinger. */
    fun getLastError(context: Context): Pair<String, Long>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_LAST_ERROR_MESSAGE, null) ?: return null
        val time = prefs.getLong(KEY_LAST_ERROR_TIME, 0L)
        return message to time
    }

    fun recordSuccess(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .putBoolean(KEY_ALREADY_NOTIFIED, false)
            .remove(KEY_LAST_ERROR_MESSAGE)
            .remove(KEY_LAST_ERROR_TIME)
            .putLong(KEY_LAST_SUCCESS_TIME, System.currentTimeMillis())
            .apply()
    }

    /** Tidspunkt for siste vellykkede auto-backup, eller null hvis den aldri har lyktes. */
    fun getLastSuccessTime(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SUCCESS_TIME, 0L)
            .takeIf { it > 0L }

    /**
     * Har appen fortsatt varig skrivetilgang til den lagrede backup-filen? Brukes for å
     * vise en tydelig "velg plassering på nytt"-melding i stedet for at backupen bare
     * feiler stille natt etter natt.
     */
    fun hasWriteAccess(context: Context): Boolean {
        val uri = get(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }

    /** Har brukeren allerede blitt varslet om DENNE sammenhengende feilrekken? Hindrer at samme varsel gjentas hver eneste dag mens feilen vedvarer. */
    fun hasAlreadyNotifiedForCurrentStreak(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALREADY_NOTIFIED, false)

    fun markNotifiedForCurrentStreak(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALREADY_NOTIFIED, true)
            .apply()
    }
}
