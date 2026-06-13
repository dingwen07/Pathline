package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.google.maps.android.compose.ComposeMapColorScheme

/**
 * Decides whether a given map's GoogleMap should stay composed. maps-compose only frees the map's
 * GPU surface (tens of MB of `GL mtrack`) when the GoogleMap leaves composition and the MapView is
 * destroyed -- onStop alone keeps it allocated. So we drop the surfaces we don't need:
 *
 *   - foreground, no pressure  -> keep every map warm (instant tab switching)
 *   - backgrounded             -> keep only the on-screen map warm (instant onRestart, no reload
 *                                 of the surface the user is about to return to); drop off-screen
 *                                 ones, since those reload on a fresh tab navigation anyway
 *   - memory pressure          -> drop even the on-screen map: avoiding a low-memory kill of the
 *                                 recorder beats avoiding a reload
 *
 * `onScreen` = this map's tab is the one currently shown to the user.
 *
 * Note: the recorder runs as a foreground service, so the process is never "cached" and Android 14+
 * no longer delivers the RUNNING_* trim levels -- meaning onTrimMemory rarely fires while tracking
 * is on. Lifecycle (below) is therefore the mechanism that actually reclaims map memory in the
 * field; [MapMemoryPressure] only kicks in for the tracking-off / cached case.
 */
@Composable
fun rememberMapComposed(onScreen: Boolean): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val uiVisible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    return if (MapMemoryPressure.active) uiVisible && onScreen else uiVisible || onScreen
}

/**
 * Process-wide flag toggled from [net.extrawdw.apps.locationhistory.MainActivity.onTrimMemory] when
 * the system signals real memory pressure, and cleared when the app returns to the foreground. When
 * set, [rememberMapComposed] releases every map -- including the one on screen.
 */
object MapMemoryPressure {
    var active by mutableStateOf(false)
}

/**
 * The map color scheme to follow the app's (system-driven) dark mode. We deliberately compute this
 * from [isSystemInDarkTheme] instead of passing [ComposeMapColorScheme.FOLLOW_SYSTEM]: MainActivity
 * declares `android:configChanges="uiMode"`, so a light/dark switch no longer recreates the Activity
 * -- which means FOLLOW_SYSTEM (evaluated once by the SDK) would stay stuck on the original scheme.
 * Reading [isSystemInDarkTheme] recomposes on the config change, flipping this value so maps-compose
 * calls `googleMap.setMapColorScheme()` on the live map -- it recolors in place, no surface rebuild.
 */
@Composable
fun rememberMapColorScheme(): ComposeMapColorScheme =
    if (isSystemInDarkTheme()) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT
