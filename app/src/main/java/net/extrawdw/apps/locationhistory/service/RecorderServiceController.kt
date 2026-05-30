package net.extrawdw.apps.locationhistory.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Starts/stops [LocationRecorderService] and refreshes its location request when state changes. */
@Singleton
class RecorderServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val running = AtomicBoolean(false)

    val isRecording: Boolean get() = running.get()

    /** Start (or re-tune) the active recording session for the given state/profile. */
    fun start(state: DevicePhysicalState, profile: PowerProfile) {
        val intent = Intent(context, LocationRecorderService::class.java).apply {
            putExtra(LocationRecorderService.EXTRA_STATE, state.name)
            putExtra(LocationRecorderService.EXTRA_PROFILE, profile.name)
        }
        ContextCompat.startForegroundService(context, intent)
        running.set(true)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val intent = Intent(context, LocationRecorderService::class.java)
            .setAction(LocationRecorderService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }
}
