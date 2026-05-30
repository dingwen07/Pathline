@file:OptIn(ExperimentalPermissionsApi::class)

package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/** Holds the app's runtime-permission state and a one-call request trigger. */
class PathlinePermissions(
    private val foreground: MultiplePermissionsState,
    private val background: MultiplePermissionsState,
) {
    /** Enough to record: precise location + activity recognition. */
    @OptIn(ExperimentalPermissionsApi::class)
    val granted: Boolean
        get() = foreground.permissions
            .filter { it.permission != Manifest.permission.POST_NOTIFICATIONS }
            .all { it.status.isGranted }

    /** Request foreground permissions first; once granted, escalate to background location. */
    @OptIn(ExperimentalPermissionsApi::class)
    fun request() {
        if (!foreground.allPermissionsGranted) {
            foreground.launchMultiplePermissionRequest()
        } else {
            background.launchMultiplePermissionRequest()
        }
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
