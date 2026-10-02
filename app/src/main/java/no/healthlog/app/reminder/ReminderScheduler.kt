package no.healthlog.app.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDateTime

private fun workNameFor(type: ReminderType) = "reminder_${type.name.lowercase()}"

object ReminderScheduler {

    fun schedule(context: Context, type: ReminderType) {
        val reminderTime = ReminderPreferences.getTime(context, type)
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(reminderTime)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val initialDelay = Duration.between(now, next)

        val inputData = Data.Builder()
            .putString(ReminderWorker.KEY_REMINDER_TYPE, type.name)
            .build()

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(Duration.ofHours(24))
            .setInitialDelay(initialDelay)
            .setInputData(inputData)
            .build()

        // Avbryter og oppretter jobben helt på nytt i stedet for å stole på
        // ExistingPeriodicWorkPolicy.UPDATE - en periodisk jobb som allerede har kjørt
        // én gang "forankrer" seg til sitt opprinnelige starttidspunkt, og en ren
        // UPDATE respekterer ikke alltid en ny setInitialDelay() etter det. Kansellering
        // + fersk enqueue garanterer at et nytt valgt tidspunkt faktisk slår inn.
        val workManager = WorkManager.getInstance(context)
        val workName = workNameFor(type)
        workManager.cancelUniqueWork(workName)
        workManager.enqueueUniquePeriodicWork(
            workName,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, type: ReminderType) {
        WorkManager.getInstance(context).cancelUniqueWork(workNameFor(type))
    }

    fun isScheduled(context: Context, type: ReminderType): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workNameFor(type))
            .map { infos -> infos.any { !it.state.isFinished } }
}
