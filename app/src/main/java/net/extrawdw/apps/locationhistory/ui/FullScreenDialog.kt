package net.extrawdw.apps.locationhistory.ui

import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.coroutines.cancellation.CancellationException

/** Duration of the open/close scale-and-fade so every dismissal path matches the opening. */
private const val DialogTransitionMs = 220

/**
 * A full-screen [Dialog] whose open, back-button close, and **predictive back** gesture all share the
 * same scale-and-fade transition, so dismissing never looks different from opening.
 *
 * The opening and any non-gesture close (back button, scrim tap, the [content]'s own close/save
 * action) animate a symmetric scale+fade through [DialogTransitionMs]. A predictive-back drag layers
 * a directional preview on top that follows the finger: it peels away from the swiped edge
 * ([BackEventCompat.swipeEdge]) and pivots around the finger's vertical position
 * ([BackEventCompat.touchY]), so left/right and diagonal swipes look different.
 *
 * [content] receives a `requestClose(action)` callback: invoke it for close/save buttons so the exit
 * animation plays before [action] runs (e.g. the parent dropping this dialog from composition).
 *
 * @param dim when false the platform scrim is cleared (for dialogs stacked over another dialog).
 */
@Composable
fun FullScreenDialog(
    onDismiss: () -> Unit,
    dim: Boolean = true,
    content: @Composable (requestClose: (() -> Unit) -> Unit) -> Unit,
) {
    // openness: 1 = fully shown, 0 = gone. Starts at 0 and animates up on first composition (open);
    // a requested close flips it back to 0 and the terminal action runs once it settles.
    var visible by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(Unit) { visible = true }

    val openness by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(DialogTransitionMs, easing = FastOutSlowInEasing),
        // Run the terminal action (which drops this dialog) only after the exit has fully played.
        finishedListener = { settled ->
            if (settled == 0f) {
                val action = pendingAction ?: onDismiss
                pendingAction = null
                action()
            }
        },
        label = "dialogOpenness",
    )

    fun requestClose(andThen: () -> Unit) {
        if (!visible) return
        pendingAction = andThen
        visible = false
    }

    // Predictive-back gesture state, latched from each BackEventCompat.
    var gestureInProgress by remember { mutableStateOf(false) }
    // On commit, hold the preview where the finger left it (instead of springing back to 0) and let the
    // exit fade carry it out -- otherwise the un-shrink races the fade and the page visibly rebounds.
    var committing by remember { mutableStateOf(false) }
    var rawProgress by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var touchY by remember { mutableFloatStateOf(0f) }
    val gesture by animateFloatAsState(
        targetValue = if (gestureInProgress || committing) rawProgress else 0f,
        label = "predictiveBack",
    )

    Dialog(
        onDismissRequest = { requestClose(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
    ) {
        if (!dim) DisableDialogDim()

        PredictiveBackHandler(enabled = visible) { events ->
            try {
                events.collect { event ->
                    gestureInProgress = true
                    rawProgress = event.progress
                    swipeEdge = event.swipeEdge
                    touchY = event.touchY
                }
                gestureInProgress = false
                committing = true
                requestClose(onDismiss)
            } catch (_: CancellationException) {
                gestureInProgress = false
                rawProgress = 0f
            }
        }

        Surface(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Open/close: symmetric scale+fade, pivoting from the centre.
                    val enterScale = lerp(0.92f, 1f, openness)
                    // Gesture preview: shrink toward the swiped edge, pivot at the finger.
                    val gestureScale = 1f - 0.10f * gesture

                    val scale = enterScale * gestureScale
                    scaleX = scale
                    scaleY = scale
                    alpha = openness * (1f - 0.15f * gesture)

                    if (gesture > 0f) {
                        val pivotY = if (size.height > 0f) {
                            (touchY / size.height).coerceIn(0f, 1f)
                        } else 0.5f
                        // Anchor the far edge so the swiped edge peels away as it shrinks.
                        val pivotX = if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else 0f
                        transformOrigin = TransformOrigin(pivotX, pivotY)
                    } else {
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                }
        ) {
            content(::requestClose)
        }
    }
}

/**
 * Registers a **predictive back** handler and returns an animated 0..1 progress to drive an in-window
 * dismiss animation (e.g. the timeline editor panel that slides down). For full-screen dialogs use
 * [FullScreenDialog] instead, which also animates opening and the back-button close.
 *
 * As the user drags from the edge, progress rises; releasing past the threshold calls [onDismiss],
 * cancelling springs it back.
 */
@Composable
fun rememberPredictiveBackProgress(enabled: Boolean = true, onDismiss: () -> Unit): State<Float> {
    var inProgress by remember { mutableStateOf(false) }
    var rawProgress by remember { mutableFloatStateOf(0f) }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event -> inProgress = true; rawProgress = event.progress }
            onDismiss()
        } catch (_: CancellationException) {
            // cancelled -> spring back
        } finally {
            inProgress = false; rawProgress = 0f
        }
    }

    return animateFloatAsState(
        targetValue = if (inProgress) rawProgress else 0f,
        label = "predictiveBack"
    )
}

/** Clears the platform scrim/dim for a dialog stacked over another dialog. */
@Composable
private fun DisableDialogDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
    }
}
