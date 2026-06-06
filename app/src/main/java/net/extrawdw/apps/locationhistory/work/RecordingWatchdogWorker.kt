package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.service.RecordingController

/**
 * Periodic liveness check (15-minute cadence, see [WorkScheduler.scheduleRecordingWatchdog]). If the
 * user has tracking on but the foreground recorder isn't running — a low-memory process kill that
 * `START_STICKY` didn't recover, a silent service death — it restarts the recorder, and when a
 * background start is refused it posts the "recording stopped" alert so the user can resume.
 *
 * It cannot recover a true force-stop / OEM "force kill": a stopped app's workers (and every other
 * scheduled entry point) never run until the user launches the app again, so there's no code path
 * left to detect it from. This watchdog only covers the kills that leave the app schedulable.
 */
@HiltWorker
class RecordingWatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recordingController: RecordingController,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppLog.i(TAG, "watchdog tick")
        runCatching { recordingController.ensureRecorderRunning("watchdog") }
            .onFailure { AppLog.e(TAG, "watchdog failed", it) }
        // Always success: a transient failure is retried on the next periodic tick, not via backoff.
        return Result.success()
    }

    private companion object {
        const val TAG = "RecordingWatchdog"
    }
}
