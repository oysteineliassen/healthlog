package no.healthlog.app.widget

import android.content.Context

private const val PREFS_NAME = "healthlog_widget_prefs"

object WidgetPreferences {

    fun getMetric1(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("metric1_$appWidgetId", WidgetMetric.STEPS) ?: WidgetMetric.STEPS

    fun getMetric2(context: Context, appWidgetId: Int): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("metric2_$appWidgetId", WidgetMetric.SLEEP) ?: WidgetMetric.SLEEP

    fun setMetrics(context: Context, appWidgetId: Int, metric1: String, metric2: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("metric1_$appWidgetId", metric1)
            .putString("metric2_$appWidgetId", metric2)
            .apply()
    }

    /** Kalt når en widget-instans fjernes fra hjemskjermen, slik at valgene ikke blir liggende igjen ubrukt. */
    fun remove(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("metric1_$appWidgetId")
            .remove("metric2_$appWidgetId")
            .apply()
    }
}
