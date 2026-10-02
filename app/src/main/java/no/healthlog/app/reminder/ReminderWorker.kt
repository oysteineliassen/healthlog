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
import androidx.work.WorkerParameters
import com.google.firebase.crashlytics.FirebaseCrashlytics
import no.healthlog.app.MainActivity
import no.healthlog.app.R
import no.healthlog.app.data.AppDatabase
import no.healthlog.app.data.HealthRepository
import java.time.LocalDate

private const val MORNING_NOTIFICATION_ID = 1001
private const val EVENING_NOTIFICATION_ID = 1003

/**
 * Kjøres omtrent én gang i døgnet per type (se ReminderScheduler) - én instans for
 * morgenpåminnelsen ("Planer for dagen"), én helt uavhengig instans for
 * kveldspåminnelsen ("Hvordan gikk dagen"). Hvilken type denne konkrete kjøringen
 * gjelder ligger i inputData, satt av ReminderScheduler.schedule().
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val typeName = inputData.getString(KEY_REMINDER_TYPE) ?: return Result.success()
        val type = runCatching { ReminderType.valueOf(typeName) }.getOrNull() ?: return Result.success()

        return try {
            doReminderCheck(type)
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            Result.retry()
        }
    }

    private suspend fun doReminderCheck(type: ReminderType): Result {
        if (!ReminderPreferences.isEnabled(applicationContext, type)) return Result.success()

        val repo = HealthRepository(AppDatabase.getInstance(applicationContext).healthDayDao())
        val today = repo.get(LocalDate.now().toString())

        val alreadyFilled = when (type) {
            ReminderType.MORNING -> !today?.morningNote.isNullOrBlank()
            ReminderType.EVENING -> !today?.eveningNote.isNullOrBlank()
        }
        if (alreadyFilled) return Result.success()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success() // ingen varslingstillatelse - ikke noe å gjøre
        }

        ensureReminderChannel(applicationContext)

        val openEntryIntent = PendingIntent.getActivity(
            applicationContext,
            if (type == ReminderType.MORNING) 2 else 4,
            Intent(applicationContext, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ENTRY, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleRes = if (type == ReminderType.MORNING) {
            R.string.reminder_notification_title_morning
        } else {
            R.string.reminder_notification_title_evening
        }
        val textRes = if (type == ReminderType.MORNING) {
            R.string.reminder_notification_text_morning
        } else {
            R.string.reminder_notification_text_evening
        }

        val notification = NotificationCompat.Builder(applicationContext, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(titleRes))
            .setContentText(applicationContext.getString(textRes))
            .setContentIntent(openEntryIntent)
            .setAutoCancel(true)
            .build()

        val notificationId = if (type == ReminderType.MORNING) MORNING_NOTIFICATION_ID else EVENING_NOTIFICATION_ID
        NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        return Result.success()
    }

    companion object {
        const val KEY_REMINDER_TYPE = "reminder_type"
    }
}
