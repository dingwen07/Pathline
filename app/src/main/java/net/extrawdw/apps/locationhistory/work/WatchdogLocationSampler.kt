package net.extrawdw.apps.locationhistory.work

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.enrich.DeviceStateCollector
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.service.toLocationSample
import javax.inject.Inject

/**
 * Best-effort gap coverage, independent of RecordingController and the foreground location request.
 * A single fix carries no fresh Activity Recognition / IMU / step evidence, so those fields remain
 * unknown. It contributes to the timeline without entering the recorder's movement state machine.
 */
class WatchdogLocationSampler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val locationRepository: LocationRepository,
    private val deviceStateCollector: DeviceStateCollector,
    private val workScheduler: WorkScheduler,
) {
    suspend fun captureIfStale() {
        val result = captureStaleLocation(
            isAllowed = ::isAllowed,
            latestSampleTimeMs = { locationRepository.mostRecent()?.timestampMs },
            requestSample = ::requestSample,
            recordIfStillStale = { sample, cutoffMs ->
                val saved = locationRepository.recordIfStillStale(sample, cutoffMs) != null
                if (saved) {
                    AppLog.i(TAG, "saved fix time=${sample.timestampMs} accuracy=${sample.accuracy}")
                    workScheduler.enqueueTimelineMaintenance(sample.dayEpoch, "watchdog_sample")
                }
                saved
            },
        )
        AppLog.i(TAG, "sample fallback: $result")
    }

    private suspend fun isAllowed(): Boolean =
        settingsRepository.settings.first().trackingEnabled &&
            !settingsRepository.autostartSuppressed.first() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked before requesting and before saving; revocation can throw.
    private suspend fun requestSample(): LocationSampleEntity? {
        AppLog.i(TAG, "no recent sample; requesting one fresh fix (timeout=${WATCHDOG_SAMPLE_TIMEOUT_MS}ms)")
        val cancellation = CancellationTokenSource()
        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0)
                .setDurationMillis(WATCHDOG_SAMPLE_TIMEOUT_MS)
                .build()
            val location = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(request, cancellation.token).await() ?: return null
            return location.toLocationSample(deviceStateCollector.snapshot())
        } finally {
            // This token belongs only to this one-shot request. Never remove/flush/replace the
            // foreground service's PendingIntent updates, even on timeout or worker cancellation.
            cancellation.cancel()
        }
    }

    private companion object {
        const val TAG = "WatchdogSample"
    }
}
