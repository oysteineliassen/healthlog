package no.healthlog.app.garmin

import no.healthlog.app.data.HealthDay

/**
 * Adapter boundary for Garmin Connect.
 *
 * Garmin's official Health API is cloud-to-cloud. Production access requires
 * Garmin approval/credentials. Keep all Garmin-specific HTTP/OAuth code behind
 * this interface so the UI/database stay independent of the vendor.
 */
interface GarminHealthProvider {
    suspend fun syncLatest(): Result<HealthDay>
}

class NotConfiguredGarminProvider : GarminHealthProvider {
    override suspend fun syncLatest(): Result<HealthDay> =
        Result.failure(IllegalStateException("Garmin is not connected yet."))
}
