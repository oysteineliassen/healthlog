package no.healthlog.app

import android.content.Context

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_COMPLETED = "onboarding_completed"

object OnboardingPreferences {
    fun hasCompletedOnboarding(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }
}
