@file:OptIn(ExperimentalPermissionsApi::class)

package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/** Holds the app's runtime-permission state and per-phase request triggers. */
class PathlinePermissions(
    private val foreground: MultiplePermissionsState,
    private val background: MultiplePermissionsState,
) {
    /**
     * The permissions actually required to record: precise location + activity recognition.
     * Notifications are optional, so they don't gate this.
     */
    @OptIn(ExperimentalPermissionsApi::class)
    val granted: Boolean
        get() = foreground.permissions
            .filter { it.permission !in OPTIONAL_FOREGROUND }
            .all { it.status.isGranted }

    /** Whether the notification permission (Android 13+) has been granted. */
    @OptIn(ExperimentalPermissionsApi::class)
    val notificationsGranted: Boolean
        get() = foreground.permissions
            .firstOrNull { it.permission == Manifest.permission.POST_NOTIFICATIONS }
            ?.status?.isGranted ?: true

    /** Whether "Allow all the time" background location has been granted. */
    @OptIn(ExperimentalPermissionsApi::class)
    val backgroundGranted: Boolean
        get() = background.permissions.all { it.status.isGranted }

    /** Everything the app needs to record reliably in the background. */
    val fullyGranted: Boolean
        get() = granted && backgroundGranted

    /** Request the foreground permissions: location, activity recognition, and notifications. */
    fun requestForeground() = foreground.launchMultiplePermissionRequest()

    /**
     * Request "Allow all the time" location. Android requires foreground location to be granted
     * first; on Android 11+ this hands off to system settings.
     */
    fun requestBackground() = background.launchMultiplePermissionRequest()

    /** Request foreground permissions first; once granted, escalate to background location. */
    @OptIn(ExperimentalPermissionsApi::class)
    fun request() {
        if (!foreground.allPermissionsGranted) {
            foreground.launchMultiplePermissionRequest()
        } else {
            background.launchMultiplePermissionRequest()
        }
    }

    private companion object {
        /** Foreground permissions the app can run without. */
        val OPTIONAL_FOREGROUND = setOf(
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberPathlinePermissions(): PathlinePermissions {
    val foreground = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        ),
    )
    val background = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    )
    return remember(foreground, background) { PathlinePermissions(foreground, background) }
}
