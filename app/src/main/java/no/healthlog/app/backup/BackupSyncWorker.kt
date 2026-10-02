package no.healthlog.app.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.healthlog.app.data.AppDatabase
import no.healthlog.app.data.HealthRepository
import no.healthlog.app.data.healthDaysToJson
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "backup_daily_sync"
private const val TAG = "BackupSyncWorker"

/**
 * Skriver en fersk backup til den filen brukeren valgte, omtrent én gang i døgnet.
 * Krever at brukeren har gitt vedvarende skrivetilgang til filen via
 * takePersistableUriPermission (gjøres når filen velges i SettingsScreen) – uten
 * det ville tilgangen forsvunnet så snart appen ble lukket.
 */
class BackupSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uri = BackupPreferences.get(applicationContext) ?: return Result.success() // ikke satt opp ennå

        return try {
            val repo = HealthRepository(AppDatabase.getInstance(applicationContext).healthDayDao())
            val json = healthDaysToJson(repo.getAllOnce())

            applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(json.toByteArray())
            } ?: return Result.retry()

            // Filen døpes IKKE om lenger. På lokal lagring er filens ID selve stien, så
            // en omdøping gir ny URI - og Android trekker da tilbake den varige
            // skrivetilgangen til den gamle, slik at neste nattlige kjøring feilet med
            // SecurityException. Tidspunkt for siste vellykkede backup vises i stedet
            // i Innstillinger (BackupPreferences.getLastSuccessTime).
            BackupPreferences.recordSuccess(applicationContext)
            Log.d(TAG, "Automatisk backup skrevet til $uri")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Automatisk backup feilet", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            // Konkret årsak (f.eks. et IO- eller sikkerhetsunntak) lagres slik at den kan
            // vises i Innstillinger etterpå - se BackupPreferences.getLastError. Uten dette
            // må feilen fanges i logcat i akkurat det sekundet jobben kjører, noe som ikke
            // er praktisk mulig for en bakgrunnsjobb som går når som helst i døgnet.
            val description = "${e.javaClass.simpleName}: ${e.message ?: "ingen detaljer"}"
            notifyIfBackupRepeatedlyFailing(applicationContext, description)
            Result.retry()
        }
    }
}

object BackupSyncScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupSyncWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun isScheduled(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING } }
}
