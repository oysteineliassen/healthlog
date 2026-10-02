package no.healthlog.app.reminder

import android.content.Context
import java.time.LocalTime

private const val PREFS_NAME = "healthlog_prefs"

enum class ReminderType(val defaultHour: Int, val defaultMinute: Int) {
    MORNING(8, 0),
    EVENING(20, 0)
}

object ReminderPreferences {

    private fun keyEnabled(type: ReminderType) = "reminder_enabled_${type.name}"
    private fun keyHour(type: ReminderType) = "reminder_hour_${type.name}"
    private fun keyMinute(type: ReminderType) = "reminder_minute_${type.name}"

    fun isEnabled(context: Context, type: ReminderType): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(keyEnabled(type), false)

    fun setEnabled(context: Context, type: ReminderType, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(keyEnabled(type), enabled)
            .apply()
    }

    fun getTime(context: Context, type: ReminderType): LocalTime {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hour = prefs.getInt(keyHour(type), type.defaultHour)
        val minute = prefs.getInt(keyMinute(type), type.defaultMinute)
        return LocalTime.of(hour, minute)
    }

    fun setTime(context: Context, type: ReminderType, time: LocalTime) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(keyHour(type), time.hour)
            .putInt(keyMinute(type), time.minute)
            .apply()
    }
}
