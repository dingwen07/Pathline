package net.extrawdw.apps.locationhistory.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Registers a **predictive back** handler and returns an animated 0..1 progress to drive the
 * dismiss animation. As the user drags from the edge, progress rises; releasing past the threshold
 * calls [onDismiss], cancelling springs it back. Use the returned value with [Modifier.predictiveBack].
 *
 * Works inside a Compose `Dialog` (its window owns an OnBackPressedDispatcher) and in-window content.
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
            // cancelled → spring back
        } finally {
            inProgress = false; rawProgress = 0f
        }
    }

    return animateFloatAsState(targetValue = if (inProgress) rawProgress else 0f, label = "predictiveBack")
}

/** Scales/translates content by the predictive-back [progress] (0..1) for the dismiss preview. */
fun Modifier.predictiveBack(progress: Float): Modifier = graphicsLayer {
    val scale = 1f - 0.12f * progress
    scaleX = scale
    scaleY = scale
    translationX = -size.width * 0.06f * progress
    alpha = 1f - 0.15f * progress
}
