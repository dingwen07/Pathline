package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.locationhistory.R

/** Resize two parts of the same screen with Material's handle and Compose's native drag support. */
@Composable
internal fun TimelineTabletLayout(
    timelineFraction: Float,
    onTimelineFractionChange: (Float) -> Unit,
    timeline: @Composable () -> Unit,
    map: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interactionSource = remember { MutableInteractionSource() }
    var draggedWidth by remember { mutableFloatStateOf(0f) }
    val resizeLabel = stringResource(R.string.cd_resize_timeline)
    val context = LocalContext.current
    val resizeCursor = remember(context) {
        PointerIcon(android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val handleWidth = 48.dp
        val paneSpace = maxWidth
        // Both panes remain usable, including at the medium-width foldable breakpoint.
        val minPaneWidth = minOf(240.dp, paneSpace / 2)
        val maxTimelineWidth = paneSpace - minPaneWidth
        val preferredWidth = (maxWidth * 0.45f).coerceAtMost(420.dp)
        val timelineWidth = (if (timelineFraction.isNaN()) preferredWidth else paneSpace * timelineFraction)
            .coerceIn(minPaneWidth, maxTimelineWidth)
        fun setWidth(width: Float) {
            if (paneSpace.value > 0f) {
                onTimelineFractionChange(width.coerceIn(minPaneWidth.value, maxTimelineWidth.value) / paneSpace.value)
            }
        }
        Row(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.width(timelineWidth).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                content = timeline,
            )
            Box(Modifier.weight(1f).fillMaxHeight()) { map() }
        }
        // Overlay the boundary: the touch target occupies no space between the two panes.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = timelineWidth - handleWidth / 2)
                .width(handleWidth)
                .fillMaxHeight()
                .pointerHoverIcon(resizeCursor)
                .draggable(
                    state = rememberDraggableState { delta ->
                        // Accumulate every pointer delta, including multiple events per frame.
                        draggedWidth = (draggedWidth + with(density) { delta.toDp().value })
                            .coerceIn(minPaneWidth.value, maxTimelineWidth.value)
                        setWidth(draggedWidth)
                    },
                    orientation = Orientation.Horizontal,
                    reverseDirection = rtl,
                    interactionSource = interactionSource,
                    onDragStarted = { draggedWidth = timelineWidth.value },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = resizeLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        timelineWidth.value, minPaneWidth.value..maxTimelineWidth.value,
                    )
                    setProgress { width -> setWidth(width); true }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionRight -> if (rtl) -1 else 1
                        Key.DirectionLeft -> if (rtl) 1 else -1
                        else -> return@onKeyEvent false
                    }
                    setWidth(timelineWidth.value + direction * 24f)
                    true
                }
                .focusable(interactionSource = interactionSource),
            contentAlignment = Alignment.Center,
        ) {
            VerticalDragHandle(interactionSource = interactionSource)
        }
    }
}
