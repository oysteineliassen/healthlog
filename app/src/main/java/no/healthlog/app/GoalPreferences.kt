package no.healthlog.app

import android.content.Context

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_STEPS_GOAL = "goal_steps"
private const val KEY_TRAINING_GOAL_MINUTES = "goal_training_minutes"
private const val KEY_SLEEP_GOAL_MINUTES = "goal_sleep_minutes"

private const val DEFAULT_STEPS_GOAL = 8000
private const val DEFAULT_TRAINING_GOAL_MINUTES = 30
private const val DEFAULT_SLEEP_GOAL_MINUTES = 480 // 8 timer
private const val KEY_WEIGHT_GOAL_KG = "goal_weight_kg"
private const val KEY_WEEKLY_ACTIVITY_GOAL = "goal_weekly_activity_minutes"
// Helsemyndighetenes anbefaling for voksne: minst 150 min moderat aktivitet per uke.
private const val DEFAULT_WEEKLY_ACTIVITY_GOAL = 150

private const val KEY_MAX_HEART_RATE = "max_heart_rate"

object GoalPreferences {

    /** Valgfritt - null betyr ikke satt (da antas DEFAULT_MAX_HEART_RATE ved intensitet). */
    fun getMaxHeartRate(context: Context): Int? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_HEART_RATE, 0)
            .takeIf { it > 0 }

    fun setMaxHeartRate(context: Context, value: Int?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_HEART_RATE, value ?: 0).apply()
    }

    fun getStepsGoal(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_STEPS_GOAL, DEFAULT_STEPS_GOAL)

    fun setStepsGoal(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_STEPS_GOAL, value).apply()
    }

    fun getTrainingGoalMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TRAINING_GOAL_MINUTES, DEFAULT_TRAINING_GOAL_MINUTES)

    fun setTrainingGoalMinutes(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TRAINING_GOAL_MINUTES, value).apply()
    }

    /** Lagres i minutter internt (samme enhet som selve søvndataen), men vises som hele timer i Innstillinger for enkelhets skyld. */
    fun getSleepGoalMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SLEEP_GOAL_MINUTES, DEFAULT_SLEEP_GOAL_MINUTES)

    fun setSleepGoalMinutes(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SLEEP_GOAL_MINUTES, value).apply()
    }

    /** Valgfritt - null betyr at du ikke har satt noe vektmål. */
    fun getWeightGoalKg(context: Context): Double? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WEIGHT_GOAL_KG, null)
            ?.toDoubleOrNull()

    fun setWeightGoalKg(context: Context, value: Double?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WEIGHT_GOAL_KG, value?.toString()).apply()
    }

    fun getWeeklyActivityGoal(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WEEKLY_ACTIVITY_GOAL, DEFAULT_WEEKLY_ACTIVITY_GOAL)

    fun setWeeklyActivityGoal(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_WEEKLY_ACTIVITY_GOAL, value).apply()
    }
}
