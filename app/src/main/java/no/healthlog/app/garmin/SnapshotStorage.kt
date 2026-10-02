package no.healthlog.app.garmin

import no.healthlog.app.data.HealthRepository

/**
 * Lagrer ALLE feltene fra et Health Connect-øyeblikksbilde. Eneste sted som mapper
 * snapshot -> database, brukt av både bakgrunnssynk, synk ved åpning og manuell synk.
 * Tidligere hadde hver av dem sin egen kopi av denne listen, og bakgrunnssynken hadde
 * bare fire av feltene - trening, snittpuls, aktive minutter og søvnfaser ble da aldri
 * lagret før man synket manuelt.
 */
suspend fun HealthRepository.upsertSnapshot(date: String, snapshot: HealthConnectDaySnapshot) {
    upsertHealthConnectData(
        date = date,
        steps = snapshot.steps,
        sleepMinutes = snapshot.sleepMinutes,
        restingHeartRate = snapshot.restingHeartRate,
        weightKg = snapshot.weightKg,
        trainingMinutes = snapshot.trainingMinutes,
        trainingIntensity = snapshot.trainingIntensity,
        trainingType = snapshot.trainingType,
        trainingSessions = snapshot.trainingSessions,
        avgHeartRate = snapshot.avgHeartRate,
        activeMinutes = snapshot.activeMinutes,
        deepSleepMinutes = snapshot.deepSleepMinutes,
        lightSleepMinutes = snapshot.lightSleepMinutes,
        remSleepMinutes = snapshot.remSleepMinutes,
        sleepAwakeMinutes = snapshot.sleepAwakeMinutes,
        hrvMs = snapshot.hrvMs,
        bodyFatPercent = snapshot.bodyFatPercent
    )
}
