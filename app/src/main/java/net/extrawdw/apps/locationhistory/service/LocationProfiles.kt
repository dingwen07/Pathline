package net.extrawdw.apps.locationhistory.service

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile

/**
 * Builds the [LocationRequest] for the active recording session. The cadence and accuracy are
 * tuned to the detected physical state and the user's power profile so we only spend battery on
 * GPS when motion actually warrants it. Updates are always **batched**
 * ([LocationRequest.Builder.setMaxUpdateDelayMillis]) so the radio can coalesce deliveries and the
 * app is woken in efficient bursts via a PendingIntent rather than holding a wakelock.
 */
object LocationProfiles {

    fun buildRequest(state: DevicePhysicalState, profile: PowerProfile): LocationRequest {
        val (priority, intervalMs, fastestMs, batchMs) = tune(state, profile)
        return LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(fastestMs)
            .setMaxUpdateDelayMillis(batchMs)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private data class Tuning(
        val priority: Int,
        val intervalMs: Long,
        val fastestMs: Long,
        val batchMs: Long,
    )

    private fun tune(state: DevicePhysicalState, profile: PowerProfile): Tuning {
        // Base cadence by motion: faster fixes when moving fast, sparse when slow.
        val base = when (state) {
            DevicePhysicalState.IN_VEHICLE -> Tuning(Priority.PRIORITY_HIGH_ACCURACY, 5_000, 2_000, 15_000)
            DevicePhysicalState.CYCLING -> Tuning(Priority.PRIORITY_HIGH_ACCURACY, 8_000, 4_000, 20_000)
            DevicePhysicalState.RUNNING -> Tuning(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000, 5_000, 30_000)
            DevicePhysicalState.WALKING -> Tuning(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000, 8_000, 45_000)
            DevicePhysicalState.STATIONARY -> Tuning(Priority.PRIORITY_LOW_POWER, 60_000, 30_000, 120_000)
            DevicePhysicalState.UNKNOWN -> Tuning(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000, 10_000, 60_000)
        }
        return when (profile) {
            PowerProfile.HIGH_ACCURACY -> base.copy(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs = base.intervalMs / 2,
                fastestMs = base.fastestMs / 2,
            )
            PowerProfile.BALANCED -> base
            PowerProfile.BATTERY_SAVER -> base.copy(
                priority = if (base.priority == Priority.PRIORITY_HIGH_ACCURACY)
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY else base.priority,
                intervalMs = base.intervalMs * 2,
                fastestMs = base.fastestMs * 2,
                batchMs = base.batchMs * 2,
            )
        }
    }
}
