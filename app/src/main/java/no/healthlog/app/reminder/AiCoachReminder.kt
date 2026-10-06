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
import no.healthlog.app.MainActivity
import no.healthlog.app.R
import no.healthlog.app.coach.AiCoachPreferences
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

private const val WORK_NAME = "ai_coach_weekly_reminder"
private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_LAST_SENT = "ai_coach_reminder_last_sent"
private const val NOTIFICATION_ID = 1005
// Etter ukeoppsummeringen (19:00), så de ikke kommer samtidig.
private val SEND_TIME: LocalTime = LocalTime.of(19, 30)

/**
 * Søndagsvarsel om å hente ukens AI-innsikt. Trykk åpner appen og kopierer prompten for
 * uka rett til utklippstavlen (se MainActivity.EXTRA_COPY_AI_PROMPT).
 */
object AiCoachReminderScheduler {

    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(SEND_TIME)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val request = PeriodicWorkRequestBuilder<AiCoachReminderWorker>(Duration.ofDays(7))
            .setInitialDelay(Duration.between(now, next))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class AiCoachReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!AiCoachPreferences.isEnabled(context) || !AiCoachPreferences.isReminderEnabled(context)) return Result.success()

        val today = LocalDate.now()
        val weekEnd = when (today.dayOfWeek) {
            DayOfWeek.SUNDAY -> today
            DayOfWeek.MONDAY -> today.minusDays(1) // utsatt av batterisparing
            else -> return Result.success()
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_SENT, null) == weekEnd.toString()) return Result.success()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        ensureReminderChannel(context)
        val open = PendingIntent.getActivity(
            context,
            6,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_COPY_AI_PROMPT, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ai_reminder_title))
            .setContentText(context.getString(R.string.ai_reminder_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.ai_reminder_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_LAST_SENT, weekEnd.toString()).apply()
        return Result.success()
    }
}
