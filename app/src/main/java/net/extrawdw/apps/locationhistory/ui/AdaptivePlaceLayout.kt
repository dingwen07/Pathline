package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/** Keep the same composition (and embedded MapView) when its layout parent changes. */
@Composable
internal fun rememberRetainedContent(content: @Composable () -> Unit): @Composable () -> Unit {
    val currentContent by rememberUpdatedState(content)
    return remember { movableContentOf { currentContent() } }
}

/** The map supports this page's content; it is not a separate navigation destination. */
@Composable
internal fun AdaptivePlaceLayout(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
    map: @Composable (edgeToEdge: Boolean) -> Unit,
    content: @Composable (PaddingValues, inlineMap: (@Composable () -> Unit)?) -> Unit,
) {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val sideBySide = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    ) && !adaptiveInfo.windowPosture.isTabletop
    val currentMap by rememberUpdatedState(map)
    val retainedMap = remember { movableContentOf<Boolean> { edgeToEdge -> currentMap(edgeToEdge) } }
    val currentContent by rememberUpdatedState(content)
    val retainedContent = remember {
        movableContentOf<PaddingValues, Boolean> { padding, inline ->
            currentContent(padding, if (inline) { { retainedMap(false) } } else null)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentWidth = (maxWidth * 0.45f).coerceAtMost(480.dp)
        // Keep one Scaffold/subcomposition while changing layout. Its content starts at y=0;
        // only the form consumes app-bar padding, allowing the wide map behind the status bar.
        Scaffold(
            topBar = {
                Box(if (sideBySide) Modifier.width(contentWidth) else Modifier.fillMaxWidth()) {
                    topBar()
                }
            },
        ) { padding ->
            if (sideBySide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(contentWidth).fillMaxHeight()) {
                        retainedContent(padding, false)
                    }
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) { retainedMap(true) }
                }
            } else {
                retainedContent(padding, true)
            }
        }
    }
}
