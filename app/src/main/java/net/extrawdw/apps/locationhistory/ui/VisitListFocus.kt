package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

// Each tap is a new request, including repeated taps on the same visit.
class VisitFocusRequest(val dayEpoch: Long, val visitId: Long)

internal val VisitFocusRequestSaver = Saver<VisitFocusRequest?, List<Long>>(
    save = { request -> request?.let { listOf(it.dayEpoch, it.visitId) } ?: emptyList() },
    restore = { values -> values.takeIf { it.size == 2 }?.let { VisitFocusRequest(it[0], it[1]) } },
)

/** Wait for the requested row to load, scroll to it, then briefly highlight it. */
@Composable
internal fun rememberVisitListHighlight(
    listState: LazyListState,
    request: VisitFocusRequest?,
    targetIndex: Int,
    onHandled: (VisitFocusRequest) -> Unit,
): Long? {
    var highlightedRequest by remember { mutableStateOf<VisitFocusRequest?>(null) }
    val currentOnHandled by rememberUpdatedState(onHandled)
    LaunchedEffect(request, targetIndex) {
        if (request == null || targetIndex < 0) return@LaunchedEffect
        highlightedRequest = null
        listState.animateScrollToItem(targetIndex)
        highlightedRequest = request
        currentOnHandled(request)
    }
    LaunchedEffect(highlightedRequest) {
        if (highlightedRequest == null) return@LaunchedEffect
        delay(2_000)
        highlightedRequest = null
    }
    return highlightedRequest?.visitId
}
