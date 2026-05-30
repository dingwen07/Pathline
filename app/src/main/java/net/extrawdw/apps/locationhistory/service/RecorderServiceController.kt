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
    @ApplicationContext private val context: Context,
) {
    private val running = AtomicBoolean(false)
    private val debugMutable = MutableStateFlow(RecorderDebugState())

    val isRecording: Boolean get() = running.get()
    val debugState: StateFlow<RecorderDebugState> = debugMutable.asStateFlow()

    /** Start (or re-tune) the active recording session for the given state/profile. */
    fun start(state: DevicePhysicalState, profile: PowerProfile): Boolean {
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
