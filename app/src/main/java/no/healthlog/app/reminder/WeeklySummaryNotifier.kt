package no.healthlog.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.healthlog.app.GoalPreferences
import no.healthlog.app.MainActivity
import no.healthlog.app.R
import no.healthlog.app.coach.buildWeeklySummary
import no.healthlog.app.data.AppDatabase
import no.healthlog.app.data.HealthRepository
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

private const val WORK_NAME = "weekly_summary_notification"
private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_ENABLED = "weekly_summary_notification_enabled"
private const val KEY_LAST_SENT_WEEK_END = "weekly_summary_last_sent_week_end"
private const val NOTIFICATION_ID = 1004
private val SEND_TIME: LocalTime = LocalTime.of(19, 0)

object WeeklySummaryPreferences {
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/**
 * Varsel søndag kl. 19 med ukens oppsummering (samme tall som kortet på I dag viser
 * på søndager). Planlegges som en ukentlig jobb med første kjøring neste søndag 19:00 -
 * samme avbryt-og-opprett-mønster som ReminderScheduler, av samme grunn.
 */
object WeeklySummaryScheduler {

    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(SEND_TIME)
        if (!next.isAfter(now)) next = next.plusWeeks(1)

        val request = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(Duration.ofDays(7))
            .setInitialDelay(Duration.between(now, next))
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME)
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun isScheduled(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.any { !it.state.isFinished } }
}

class WeeklySummaryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        sendIfDue()
        Result.success()
    } catch (e: Exception) {
        FirebaseCrashlytics.getInstance().recordException(e)
        Result.retry()
    }

    private suspend fun sendIfDue() {
        val context = applicationContext
        if (!WeeklySummaryPreferences.isEnabled(context)) return

        // Android kan utsette jobben noen timer (batterisparing). Kjører den først natt
        // til mandag, gjelder oppsummeringen fortsatt uka som sluttet søndag.
        val today = LocalDate.now()
        val weekEnd = when (today.dayOfWeek) {
            DayOfWeek.SUNDAY -> today
            DayOfWeek.MONDAY -> today.minusDays(1)
            else -> return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_SENT_WEEK_END, null) == weekEnd.toString()) return // allerede sendt

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val repo = HealthRepository(AppDatabase.getInstance(context).healthDayDao())
        val summary = buildWeeklySummary(
            repo.getAllOnce(),
            weekEnd,
            GoalPreferences.getSleepGoalMinutes(context),
            GoalPreferences.getStepsGoal(context)
        ) ?: return

        fun withPrevious(line: String, previous: String?) =
            if (previous == null) line else "$line ${context.getString(R.string.weekly_summary_prev_week, previous)}"

        val lines = listOfNotNull(
            summary.avgScoreThisWeek?.let {
                withPrevious(
                    context.getString(R.string.weekly_summary_score, it.toInt()),
                    summary.avgScoreLastWeek?.toInt()?.toString()
                )
            },
            withPrevious(
                context.getString(R.string.weekly_summary_steps, "%,d".format(summary.stepsThisWeek)),
                summary.stepsLastWeek?.let { "%,d".format(it) }
            ),
            summary.avgSleepThisWeek?.let {
                withPrevious(
                    context.getString(R.string.weekly_summary_sleep, "%.1f".format(it)),
                    summary.avgSleepLastWeek?.let { prev -> "%.1f".format(prev) }
                )
            }
        )

        ensureReminderChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.weekly_summary_title))
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_LAST_SENT_WEEK_END, weekEnd.toString()).apply()
    }
}
