package no.healthlog.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import no.healthlog.app.R
import no.healthlog.app.data.HealthDay

object WidgetMetric {
    const val STEPS = "steps"
    const val SLEEP = "sleep"
    const val RESTING_HR = "resting_hr"
    const val HRV = "hrv"
    const val STRESS = "stress"
    const val BODY_BATTERY = "body_battery"
    const val TRAINING = "training"
    const val WEIGHT = "weight"

    val all = listOf(STEPS, SLEEP, RESTING_HR, HRV, STRESS, BODY_BATTERY, TRAINING, WEIGHT)
}

@Composable
fun widgetMetricLabel(metric: String): String = when (metric) {
    WidgetMetric.STEPS -> stringResource(R.string.metric_label_steps)
    WidgetMetric.SLEEP -> stringResource(R.string.metric_label_sleep)
    WidgetMetric.RESTING_HR -> stringResource(R.string.metric_label_resting_hr)
    WidgetMetric.HRV -> stringResource(R.string.metric_label_hrv)
    WidgetMetric.STRESS -> stringResource(R.string.metric_label_stress)
    WidgetMetric.BODY_BATTERY -> stringResource(R.string.metric_label_body_battery)
    WidgetMetric.TRAINING -> stringResource(R.string.metric_label_training)
    WidgetMetric.WEIGHT -> stringResource(R.string.widget_config_label_weight)
    else -> metric
}

/** Ikke-Composable variant, siden RemoteViews-oppdateringen i HealthWidgetProvider skjer utenfor Compose. */
fun formatWidgetMetric(context: Context, metric: String, day: HealthDay?): String {
    val placeholder = context.getString(R.string.widget_metric_placeholder)
    return when (metric) {
        WidgetMetric.STEPS -> day?.steps?.let {
            context.getString(R.string.widget_steps_format, "%,d".format(it))
        } ?: context.getString(R.string.widget_steps_placeholder)

        WidgetMetric.SLEEP -> day?.sleepMinutes?.let {
            context.getString(R.string.widget_sleep_format, it / 60, it % 60)
        } ?: context.getString(R.string.widget_sleep_placeholder)

        WidgetMetric.RESTING_HR -> day?.restingHeartRate?.let {
            context.getString(R.string.widget_resting_hr_format, it)
        } ?: placeholder

        WidgetMetric.HRV -> day?.hrvMs?.let {
            context.getString(R.string.widget_hrv_format, it.toInt())
        } ?: placeholder

        WidgetMetric.STRESS -> day?.stress?.let {
            context.getString(R.string.widget_stress_format, it)
        } ?: placeholder

        WidgetMetric.BODY_BATTERY -> day?.bodyBatteryMax?.let {
            context.getString(R.string.widget_body_battery_format, it)
        } ?: placeholder

        WidgetMetric.TRAINING -> day?.trainingMinutes?.let {
            context.getString(R.string.widget_training_format, it)
        } ?: placeholder

        WidgetMetric.WEIGHT -> day?.weightKg?.let {
            context.getString(R.string.widget_weight_format, it)
        } ?: placeholder

        else -> placeholder
    }
}
