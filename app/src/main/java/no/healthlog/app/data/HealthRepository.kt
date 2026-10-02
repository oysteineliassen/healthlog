package no.healthlog.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class HealthRepository(
    private val dao: HealthDayDao
) {

    fun observeAll(): Flow<List<HealthDay>> =
        dao.observeAll()

    suspend fun getAllOnce(): List<HealthDay> =
        dao.observeAll().first()

    suspend fun get(date: String) =
        dao.get(date)

    suspend fun upsert(day: HealthDay) =
        dao.upsert(day.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(date: String) {
        dao.delete(date)
    }

    /**
     * Slår sammen data lest fra Health Connect med det som eventuelt allerede ligger lagret
     * for datoen (manuelle felt som Body Battery/HRV/stress berøres ikke). Brukes både fra
     * HealthViewModel (manuell/forgrunns-synk) og HealthConnectSyncWorker (bakgrunnssynk),
     * slik at sammenslåingslogikken kun finnes på ett sted.
     *
     * Felt som står i old.manualOverrides (rettet manuelt i Historikk, se
     * HealthViewModel.saveManual) beholder alltid sin lagrede verdi her, uansett hva
     * Health Connect nå rapporterer - en manuell rettelse av en feilregistrering skal
     * ikke bli overskrevet igjen ved neste synk.
     */
    suspend fun upsertHealthConnectData(
        date: String,
        steps: Int?,
        sleepMinutes: Int?,
        restingHeartRate: Int?,
        weightKg: Double?,
        trainingMinutes: Int? = null,
        trainingIntensity: String? = null,
        trainingType: String? = null,
        trainingSessions: String? = null,
        avgHeartRate: Int? = null,
        activeMinutes: Int? = null,
        deepSleepMinutes: Int? = null,
        lightSleepMinutes: Int? = null,
        remSleepMinutes: Int? = null,
        sleepAwakeMinutes: Int? = null,
        hrvMs: Double? = null,
        bodyFatPercent: Double? = null
    ) {
        val old = dao.get(date)
        val overrides = old.manualOverrideSet()

        upsert(
            (old ?: HealthDay(date = date)).copy(
                steps = if ("steps" in overrides) old?.steps else (steps ?: old?.steps),
                sleepMinutes = if ("sleepMinutes" in overrides) old?.sleepMinutes else (sleepMinutes ?: old?.sleepMinutes),
                restingHeartRate = if ("restingHeartRate" in overrides) old?.restingHeartRate else (restingHeartRate ?: old?.restingHeartRate),
                avgHeartRate = if ("avgHeartRate" in overrides) old?.avgHeartRate else (avgHeartRate ?: old?.avgHeartRate),
                activeMinutes = if ("activeMinutes" in overrides) old?.activeMinutes else (activeMinutes ?: old?.activeMinutes),
                // Stadiene redigeres aldri manuelt (kun sleepMinutes selv er
                // beskyttbar/redigerbar som før) - alltid overskrevet av ferskeste synk.
                // HRV fra Health Connect fyller bare inn når dagen mangler HRV - en verdi du
                // har registrert selv overskrives aldri.
                hrvMs = old?.hrvMs ?: hrvMs,
                // Kroppsfett registreres aldri manuelt - nyeste fra Health Connect gjelder.
                bodyFatPercent = bodyFatPercent ?: old?.bodyFatPercent,
                deepSleepMinutes = deepSleepMinutes ?: old?.deepSleepMinutes,
                lightSleepMinutes = lightSleepMinutes ?: old?.lightSleepMinutes,
                remSleepMinutes = remSleepMinutes ?: old?.remSleepMinutes,
                sleepAwakeMinutes = sleepAwakeMinutes ?: old?.sleepAwakeMinutes,
                weightKg = if ("weightKg" in overrides) old?.weightKg else (weightKg ?: old?.weightKg),
                trainingMinutes = if ("trainingMinutes" in overrides) old?.trainingMinutes else (trainingMinutes ?: old?.trainingMinutes),
                trainingIntensity = if ("trainingIntensity" in overrides) old?.trainingIntensity else (trainingIntensity ?: old?.trainingIntensity),
                trainingType = if ("trainingType" in overrides) old?.trainingType else (trainingType ?: old?.trainingType),
                trainingSessions = trainingSessions ?: old?.trainingSessions,
                source = "health-connect"
            )
        )
    }

    /**
     * Fjerner beskyttelsen for ett enkelt felt (f.eks. "steps") på en gitt dag, slik at
     * Health Connect igjen kan overskrive akkurat det feltet ved neste synk. Rører ikke
     * beskyttelsen for andre felt på samme dag.
     */
    suspend fun clearManualOverride(date: String, field: String) {
        val old = dao.get(date) ?: return
        val overrides = old.manualOverrideSet() - field
        upsert(old.copy(manualOverrides = overrides.toManualOverridesString()))
    }

    /**
     * Skriver hele listen fra en sikkerhetskopi rett inn (overskriver eksisterende
     * rader for samme dato, rører ikke datoer som ikke finnes i filen).
     */
    suspend fun importBackup(days: List<HealthDay>): Int {
        val stamped = days.map { it.copy(updatedAt = System.currentTimeMillis()) }
        dao.upsertAll(stamped)
        return stamped.size
    }

}
