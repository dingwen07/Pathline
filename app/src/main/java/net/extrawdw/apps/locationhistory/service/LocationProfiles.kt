package net.extrawdw.apps.locationhistory.service

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import net.extrawdw.apps.locationhistory.core.RecorderState
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile

/**
 * Builds the [LocationRequest] for the active recording session. The cadence and accuracy are tuned
 * to the recorder's control state ([RecorderState]) and the user's power profile so we only spend
 * battery on GPS when motion actually warrants it. Updates are always **batched**
 * ([LocationRequest.Builder.setMaxUpdateDelayMillis]) so the radio can coalesce deliveries and the
 * app is woken in efficient bursts via a PendingIntent rather than holding a wakelock.
 */
object LocationProfiles {

    fun buildRequest(state: RecorderState, profile: PowerProfile): LocationRequest {
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

    private fun tune(state: RecorderState, profile: PowerProfile): Tuning {
        // Base cadence by control state. The four AR moving activities collapsed into one MOVING tier:
        // their old location requests were near-identical (vehicle/cycle/run 4s, walk 5s), so the
        // distinction bought nothing but state churn — it lives in sample evidence for the timeline.
        val base = when (state) {
            // Travelling: tight, high-accuracy fixes for trip fidelity.
            RecorderState.MOVING -> Tuning(
                Priority.PRIORITY_HIGH_ACCURACY,
                4_000,
                2_000,
                12_000
            )

            // Cheap first look after a weak departure hint: BALANCED (Wi-Fi+cell, rarely GPS) at a
            // modest cadence. Filters transients (a phone pickup) without engaging GPS hard; escalates
            // to CONFIRMING only on a real displacement/Doppler hint.
            RecorderState.SENSING_DEPARTURE -> Tuning(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                20_000,
                10_000,
                40_000
            )

            // Escalated departure check: HIGH_ACCURACY so Doppler + sub-100 m position can resolve a
            // real walk-out from in-place drift. Slower than the old eager 2s burst -- the multi-fix
            // signature needs a few fixes over a couple of minutes, not a frantic burst.
            RecorderState.CONFIRMING_DEPARTURE -> Tuning(
                Priority.PRIORITY_HIGH_ACCURACY,
                10_000,
                5_000,
                20_000
            )

            // Parked: BALANCED (Wi-Fi+cell, ~100 m) rather than LOW_POWER (cell-only, ~km) so the stored
            // stationary samples don't drift on cell-tower handoffs. Departure detection rests on the
            // armed triggers (geofence / significant-motion / Wi-Fi disconnect), not this heartbeat.
            RecorderState.STATIONARY -> Tuning(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                180_000,
                120_000,
                300_000
            )

            RecorderState.UNKNOWN -> Tuning(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                12_000,
                6_000,
                30_000
            )
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
