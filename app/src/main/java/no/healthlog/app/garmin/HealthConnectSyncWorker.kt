package no.healthlog.app.garmin

import android.content.Context
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
import no.healthlog.app.widget.HealthWidgetProvider
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "health_connect_daily_sync"

/**
 * Kjører omtrent én gang i døgnet (WorkManager garanterer ikke et eksakt klokkeslett,
 * bare at det kjøres regelmessig – Android kan utsette den noe pga. batterioptimalisering).
 * Bygger sine egne Room/HealthConnectManager-instanser siden en Worker ikke har tilgang
 * til Activity eller ViewModel.
 */
class HealthConnectSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val healthConnectManager = HealthConnectManager(applicationContext)

        if (!healthConnectManager.isAvailable()) {
            return Result.success() // ingenting å gjøre, ikke noe å prøve på nytt heller
        }
        if (!healthConnectManager.hasAllPermissions() || !healthConnectManager.hasBackgroundPermission()) {
            return Result.success() // mangler tillatelse; bruker må gi den på nytt manuelt
        }

        return try {
            val repo = HealthRepository(AppDatabase.getInstance(applicationContext).healthDayDao())
            val today = LocalDate.now()
            // Henter et lite glidende vindu bakover, ikke bare i dag - Garmin/Health
            // Connect kan synke og korrigere data (typisk skritt) med litt forsinkelse,
            // og uten dette ville en "bare i dag"-jobb aldri gå tilbake og fange opp
            // en verdi som ble ferdig synket etter at dagen faktisk er passert.
            val snapshots = healthConnectManager.readRange(today.minusDays(2), today)
            snapshots.forEach { (date, snapshot) ->
                repo.upsertSnapshot(date.toString(), snapshot)
            }
            HealthWidgetProvider.requestUpdate(applicationContext)
            Result.success()
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            Result.retry()
        }
    }
}

object HealthConnectSyncScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** true hvis den daglige synken faktisk er planlagt akkurat nå – overlever appstart/omstart. */
    fun isScheduled(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING } }
}
