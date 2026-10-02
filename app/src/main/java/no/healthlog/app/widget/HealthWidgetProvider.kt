package no.healthlog.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.healthlog.app.MainActivity
import no.healthlog.app.R
import no.healthlog.app.calculateHealthScore
import no.healthlog.app.healthScoreStatus
import no.healthlog.app.data.AppDatabase
import no.healthlog.app.data.HealthRepository
import java.time.LocalDate

/**
 * Hjemskjerm-widget som viser dagens helse-score pluss to selvvalgte målinger (se
 * WidgetConfigActivity - vises automatisk når widgeten legges til, valgene lagres per
 * widget-instans i WidgetPreferences).
 *
 * Oppdateres av Android automatisk omtrent hvert 30. minutt (systemets minstegrense,
 * satt i health_widget_info.xml) - men i tillegg ber HealthViewModel og
 * HealthConnectSyncWorker widgeten om å friske seg opp med det samme hver gang dagens
 * data faktisk endrer seg (se requestUpdate), slik at den ikke henger etter i opptil
 * en halvtime etter en manuell registrering eller en Health Connect-synk.
 */
class HealthWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> WidgetPreferences.remove(context, id) }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        // Room-spørringer kan ikke gjøres synkront på hovedtråden en BroadcastReceiver
        // kjører på - goAsync() gir litt ekstra tid til å fullføre arbeidet i bakgrunnen
        // før systemet har lov til å drepe prosessen.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = HealthRepository(AppDatabase.getInstance(context).healthDayDao())
                val today = repo.get(LocalDate.now().toString())
                val score = calculateHealthScore(today)

                val views = RemoteViews(context.packageName, R.layout.widget_health_score).apply {
                    setTextViewText(R.id.widget_score, score.toString())
                    setTextViewText(R.id.widget_status, healthScoreStatus(context, score))

                    val metric1 = WidgetPreferences.getMetric1(context, appWidgetId)
                    val metric2 = WidgetPreferences.getMetric2(context, appWidgetId)
                    setTextViewText(R.id.widget_metric1, formatWidgetMetric(context, metric1, today))
                    setTextViewText(R.id.widget_metric2, formatWidgetMetric(context, metric2, today))

                    val openAppIntent = PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_root, openAppIntent)

                    // Egen PendingIntent (ulik request code, ellers ville den overskrevet
                    // openAppIntent over) som ber MainActivity åpne rett til
                    // registrerings-dialogen i stedet for bare "I dag"-fanen.
                    val quickAddIntent = PendingIntent.getActivity(
                        context,
                        1,
                        Intent(context, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_OPEN_ENTRY, true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_add_button, quickAddIntent)
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** Kalles fra appen etter lagring/synk for å be widgeten friske seg opp med det samme. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HealthWidgetProvider::class.java))
            if (ids.isEmpty()) return // ingen widget lagt til noe sted - ingenting å oppdatere

            val intent = Intent(context, HealthWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
