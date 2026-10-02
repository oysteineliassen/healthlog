package no.healthlog.app.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import no.healthlog.app.MainActivity
import no.healthlog.app.R

private const val BACKUP_FAILURE_CHANNEL_ID = "backup_failure"
private const val BACKUP_FAILURE_NOTIFICATION_ID = 1002

/** Antall påfølgende feil før brukeren faktisk varsles - unngår å slå alarm for en enkelt,
 * mulig forbigående feil (f.eks. midlertidig nettverksproblem). */
private const val FAILURE_THRESHOLD = 3

private fun ensureBackupFailureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            BACKUP_FAILURE_CHANNEL_ID,
            context.getString(R.string.backup_failure_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.backup_failure_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}

/**
 * Kalles fra alle steder som skriver til auto-backup-filen (både den daglige
 * bakgrunnsjobben og den manuelle "Lag sikkerhetskopi"-knappen sin sekundære
 * oppdatering), slik at feiltellingen er felles uansett hvor feilen oppstår.
 * Varsler kun ÉN GANG per sammenhengende feilrekke (ikke hver dag feilen vedvarer) -
 * nullstilles automatisk ved neste vellykkede skriving, se BackupPreferences.recordSuccess.
 */
fun notifyIfBackupRepeatedlyFailing(context: Context, errorMessage: String? = null) {
    val failures = BackupPreferences.recordFailure(context, errorMessage)
    if (failures < FAILURE_THRESHOLD) return
    if (BackupPreferences.hasAlreadyNotifiedForCurrentStreak(context)) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    ensureBackupFailureChannel(context)

    val openAppIntent = PendingIntent.getActivity(
        context,
        3,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, BACKUP_FAILURE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.backup_failure_notification_title))
        .setContentText(context.getString(R.string.backup_failure_notification_text))
        .setContentIntent(openAppIntent)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context).notify(BACKUP_FAILURE_NOTIFICATION_ID, notification)
    BackupPreferences.markNotifiedForCurrentStreak(context)
}
