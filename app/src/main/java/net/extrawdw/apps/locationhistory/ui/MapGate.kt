package net.extrawdw.apps.locationhistory.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

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
