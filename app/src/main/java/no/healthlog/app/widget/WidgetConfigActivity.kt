package no.healthlog.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import no.healthlog.app.R

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android krever CANCELED som standardresultat - widgeten legges ikke til i det
        // hele tatt hvis brukeren forlater denne skjermen uten å fullføre (f.eks. med
        // tilbake-knappen), og appen skal ikke late som noe ble lagret da.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Surface {
                    WidgetConfigScreen(
                        initialMetric1 = WidgetPreferences.getMetric1(this, appWidgetId),
                        initialMetric2 = WidgetPreferences.getMetric2(this, appWidgetId),
                        onConfirm = { metric1, metric2 ->
                            WidgetPreferences.setMetrics(this, appWidgetId, metric1, metric2)
                            HealthWidgetProvider.requestUpdate(this)

                            val resultValue = Intent().apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            }
                            setResult(Activity.RESULT_OK, resultValue)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    initialMetric1: String,
    initialMetric2: String,
    onConfirm: (String, String) -> Unit
) {
    var metric1 by remember { mutableStateOf(initialMetric1) }
    var metric2 by remember { mutableStateOf(initialMetric2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(stringResource(R.string.widget_config_title), style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.widget_config_metric1_label), style = MaterialTheme.typography.titleSmall)
        WidgetMetric.all.forEach { metric ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = metric1 == metric, onClick = { metric1 = metric })
                Text(widgetMetricLabel(metric))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.widget_config_metric2_label), style = MaterialTheme.typography.titleSmall)
        WidgetMetric.all.forEach { metric ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = metric2 == metric, onClick = { metric2 = metric })
                Text(widgetMetricLabel(metric))
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onConfirm(metric1, metric2) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}
