package net.extrawdw.apps.locationhistory.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class RecorderDebugState(
    val isRecording: Boolean = false,
    val state: DevicePhysicalState = DevicePhysicalState.UNKNOWN,
    val profile: PowerProfile? = null,
    val updatedAtMs: Long? = null,
    val lastStartError: String? = null,
)

/** Starts/stops [LocationRecorderService] and refreshes its location request when state changes. */
@Singleton
class RecorderServiceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val running = AtomicBoolean(false)

    /**
     * In-memory mirror of the persisted autostart-suppression flag (see [SettingsRepository]). When
     * set, [start] is a no-op so an AR transition / geofence exit can't relaunch the foreground
     * service after the app was swiped from Recents. [RecordingController] keeps it in sync with the
     * persisted value (restoring it on a fresh process and clearing it when the app returns).
     */
    private val autostartSuppressed = AtomicBoolean(false)
    private val debugMutable = MutableStateFlow(RecorderDebugState())

    val isRecording: Boolean get() = running.get()
    val debugState: StateFlow<RecorderDebugState> = debugMutable.asStateFlow()

    fun suppressAutostart() {
        autostartSuppressed.set(true)
    }

    fun clearAutostartSuppression() {
        autostartSuppressed.set(false)
    }

    /** Start (or re-tune) the active recording session for the given state/profile. */
    fun start(state: DevicePhysicalState, profile: PowerProfile): Boolean {
        if (autostartSuppressed.get()) {
            AppLog.i(TAG, "start ignored — autostart suppressed until app returns to foreground")
            return false
        }
        val intent = Intent(context, LocationRecorderService::class.java).apply {
            putExtra(LocationRecorderService.EXTRA_STATE, state.name)
            putExtra(LocationRecorderService.EXTRA_PROFILE, profile.name)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: RuntimeException) {
            val message = e.message ?: e.javaClass.simpleName
            AppLog.w(TAG, "startForegroundService denied: $message")
            running.set(false)
            debugMutable.value = RecorderDebugState(
                isRecording = false,
                state = state,
                profile = profile,
                updatedAtMs = System.currentTimeMillis(),
                lastStartError = message,
            )
            return false
        }
        running.set(true)
        debugMutable.value = RecorderDebugState(
            isRecording = true,
            state = state,
            profile = profile,
            updatedAtMs = System.currentTimeMillis(),
            lastStartError = null,
        )
        return true
    }

    /**
     * Called by [LocationRecorderService] once it has actually started/retuned its location
     * request. The OS can restart the START_STICKY service on its own (intent == null — e.g. after
     * a Play Store update or process death) without ever going through [start], which would leave
     * [running] false and silently freeze the cadence (every [RecordingController] retune is gated
     * on [isRecording]). This makes the service the source of truth for the running flag.
     */
    fun markStarted(state: DevicePhysicalState, profile: PowerProfile) {
        running.set(true)
        debugMutable.value = RecorderDebugState(
            isRecording = true,
            state = state,
            profile = profile,
            updatedAtMs = System.currentTimeMillis(),
            lastStartError = null,
        )
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        debugMutable.value = debugMutable.value.copy(
            isRecording = false,
            updatedAtMs = System.currentTimeMillis(),
        )
        val intent = Intent(context, LocationRecorderService::class.java)
            .setAction(LocationRecorderService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }

    fun markStopped(reason: String? = null) {
        running.set(false)
        debugMutable.value = debugMutable.value.copy(
            isRecording = false,
            updatedAtMs = System.currentTimeMillis(),
            lastStartError = reason ?: debugMutable.value.lastStartError,
        )
    }

    private companion object {
        const val TAG = "RecorderServiceController"
    }
}
