@file:OptIn(ExperimentalPermissionsApi::class)

package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale

/** Holds the app's runtime-permission state and per-phase request triggers. */
class PathlinePermissions(
    private val foreground: MultiplePermissionsState,
    private val background: MultiplePermissionsState,
    private val foregroundRequested: MutableState<Boolean>,
    private val backgroundRequested: MutableState<Boolean>,
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

    /**
     * The user picked "Approximate" in the location dialog: coarse location granted, precise
     * denied. Visit detection needs precise location, so recording is effectively dead until the
     * user upgrades the grant — re-requesting rarely re-offers the choice, so send them to the
     * app's settings page instead.
     */
    @OptIn(ExperimentalPermissionsApi::class)
    val approximateOnly: Boolean
        get() {
            val byPermission = foreground.permissions.associateBy { it.permission }
            return byPermission[Manifest.permission.ACCESS_COARSE_LOCATION]?.status?.isGranted == true &&
                    byPermission[Manifest.permission.ACCESS_FINE_LOCATION]?.status?.isGranted == false
        }

    /**
     * The system dialog is gone for the required foreground permissions: a request was launched at
     * least once, something required is still denied, and no denied permission offers a rationale
     * anymore — only the app's settings page can grant from here. The "has requested" gate matters
     * because `shouldShowRationale == false` also describes the never-asked state.
     */
    @OptIn(ExperimentalPermissionsApi::class)
    val foregroundPermanentlyDenied: Boolean
        get() = foregroundRequested.value && !granted && foreground.permissions
            .filter { it.permission !in OPTIONAL_FOREGROUND && !it.status.isGranted }
            .none { it.status.shouldShowRationale }

    /** Same dead-end shape for "Allow all the time" background location. */
    @OptIn(ExperimentalPermissionsApi::class)
    val backgroundPermanentlyDenied: Boolean
        get() = backgroundRequested.value && !backgroundGranted && background.permissions
            .filter { !it.status.isGranted }
            .none { it.status.shouldShowRationale }

    /** Request the foreground permissions: location, activity recognition, and notifications. */
    fun requestForeground() {
        foregroundRequested.value = true
        foreground.launchMultiplePermissionRequest()
    }

    /**
     * Request "Allow all the time" location. Android requires foreground location to be granted
     * first; on Android 11+ this hands off to system settings.
     */
    fun requestBackground() {
        backgroundRequested.value = true
        background.launchMultiplePermissionRequest()
    }

    /** Request foreground permissions first; once granted, escalate to background location. */
    @OptIn(ExperimentalPermissionsApi::class)
    fun request() {
        if (!foreground.allPermissionsGranted) requestForeground() else requestBackground()
    }

    private companion object {
        /** Foreground permissions the app can run without. */
        val OPTIONAL_FOREGROUND = setOf(
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

/**
 * Deep link to this app's page in system settings — the only place left to grant a permission once
 * Android stops showing the request dialog (or to upgrade approximate location to precise).
 */
fun appSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)

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
    // "Has a request been launched?" separates never-asked from permanently denied: only after a
    // request can denied + no-rationale mean the system dialog will never show again.
    val foregroundRequested = rememberSaveable { mutableStateOf(false) }
    val backgroundRequested = rememberSaveable { mutableStateOf(false) }
    return remember(foreground, background) {
        PathlinePermissions(foreground, background, foregroundRequested, backgroundRequested)
    }
}
