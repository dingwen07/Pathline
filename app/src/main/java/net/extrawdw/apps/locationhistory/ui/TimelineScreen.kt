package net.extrawdw.apps.locationhistory.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.domain.SegmentType
import net.extrawdw.apps.locationhistory.domain.TimelineDay
import net.extrawdw.apps.locationhistory.domain.TimelineItem

private val SHEET_PEEK = 340.dp
private const val TODAY_PAGE = 100_000 // anchor; pages below are past days, none in the future

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(viewModel: TimelineViewModel = hiltViewModel()) {
    val mapState by viewModel.mapState.collectAsStateWithLifecycle()
    val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val today = remember { viewModel.today }
    fun dayForPage(p: Int) = today - (TODAY_PAGE - p)
    val pagerState = rememberPagerState(initialPage = TODAY_PAGE) { TODAY_PAGE + 1 }

    val cameraPositionState = rememberCameraPositionState()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()

    var confirmVisit by remember { mutableStateOf<VisitEntity?>(null) }
    var editItem by remember { mutableStateOf<TimelineItem?>(null) }
    var editSamples by remember { mutableStateOf<List<LocationSampleEntity>>(emptyList()) }
    var splitIndex by remember { mutableStateOf<Int?>(null) }
    var reclassifyType by remember { mutableStateOf<SegmentType?>(null) }
    var editPlace by remember { mutableStateOf<PlaceEntity?>(null) }
    var detailPlaceId by remember { mutableStateOf<Long?>(null) }

    val editing = editItem != null
    val editPoints = remember(editSamples) { editSamples.map { LatLng(it.latitude, it.longitude) } }
    val dotIcon = rememberDotIcon(VISIT_BLUE)

    val editBackProgress by rememberPredictiveBackProgress(enabled = editItem != null) {
        editItem = null
        splitIndex = null
        reclassifyType = null
    }

    LaunchedEffect(Unit) { viewModel.refreshIfStale() }
    // Pager (interactive swipe) is the source of truth for the day.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { viewModel.selectDay(dayForPage(it)) }
    }

    // Fit the camera to the day once (prevents zoom jitter as samples stream in).
    var fittedDay by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedDay, mapState, editing) {
        if (editing || fittedDay == selectedDay) return@LaunchedEffect
        val bounds = mapState.boundsOrNull()
        if (bounds != null) {
            runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
                .onFailure { cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(bounds.center, 15f)) }
            fittedDay = selectedDay
        } else {
            viewModel.currentLatLng()?.let {
                cameraPositionState.move(CameraUpdateFactory.newCameraPosition(CameraPosition.fromLatLngZoom(it, 15f)))
            }
        }
    }
    // While splitting, follow the split point; while reclassifying, fit the whole edited track.
    LaunchedEffect(editing, splitIndex, editPoints) {
        if (!editing || editPoints.isEmpty()) return@LaunchedEffect
        val idx = splitIndex
        if (idx != null && idx in editPoints.indices) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(editPoints[idx], 17f))
        } else {
            runCatching {
                val b = LatLngBounds.builder().apply { editPoints.forEach { include(it) } }.build()
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(b, 140))
            }
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = SHEET_PEEK,
        sheetContent = {
            AnimatedContent(
                targetState = editItem,
                transitionSpec = {
                    (slideInVertically { h -> h / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically { h -> h / 3 } + fadeOut(tween(200)))
                },
                label = "sheet",
            ) { ed ->
            if (ed != null) {
                Box(
                    Modifier.graphicsLayer {
                        translationY = size.height * 0.33f * editBackProgress
                        alpha = 1f - 0.20f * editBackProgress
                    },
                ) {
                    SplitEditorPanel(
                        samples = editSamples,
                        initialType = ed.currentType(),
                        onSplit = { i, l, r -> viewModel.splitItem(ed, i, l, r); editItem = null; splitIndex = null; reclassifyType = null },
                        onConvert = { t -> viewModel.convertItem(ed, t); editItem = null; splitIndex = null; reclassifyType = null },
                        onCancel = { editItem = null; splitIndex = null; reclassifyType = null },
                        onSplitIndexChange = { splitIndex = it },
                        onReclassifyType = { reclassifyType = it },
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    DayHeader(
                        dayEpoch = selectedDay,
                        isToday = selectedDay >= today,
                        onToday = { scope.launch { pagerState.animateScrollToPage(TODAY_PAGE) } },
                    )
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(520.dp)) { page ->
                        val day = dayForPage(page)
                        val dayTimeline by remember(day) { viewModel.timelineFor(day) }
                            .collectAsStateWithLifecycle(TimelineDay(day, emptyList()))
                        val listState = rememberLazyListState()
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = { viewModel.refresh() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                if (dayTimeline.items.isEmpty()) {
                                    item { EmptyDay(Modifier.fillParentMaxWidth().padding(32.dp)) }
                                }
                                itemsIndexed(dayTimeline.items, key = { _, it -> it.itemKey() }) { index, item ->
                                    when (item) {
                                        is TimelineItem.VisitItem -> VisitRow(
                                            item = item,
                                            isFirst = index == 0,
                                            isLast = index == dayTimeline.items.lastIndex,
                                            topColor = tripJoinColor(dayTimeline.items.getOrNull(index - 1), first = true),
                                            bottomColor = tripJoinColor(dayTimeline.items.getOrNull(index + 1), first = false),
                                            onConfirm = { confirmVisit = item.visit },
                                            onOpenPlace = { item.visit.placeId?.let { detailPlaceId = it } },
                                            onEditPlace = { item.place?.let { editPlace = it } },
                                            onEditSamples = { editItem = item; scope.launch { editSamples = viewModel.samplesFor(item) } },
                                        )
                                        is TimelineItem.TripItem -> TripRow(
                                            item = item,
                                            onConfirmSegment = { id, mode -> viewModel.confirmSegmentMode(id, mode) },
                                            onMarkStationary = { viewModel.convertItem(item, SegmentType.Stationary) },
                                            onEdit = { editItem = item; scope.launch { editSamples = viewModel.samplesFor(item) } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
                contentPadding = PaddingValues(bottom = SHEET_PEEK),
                uiSettings = MapUiSettings(zoomControlsEnabled = false),
            ) {
                val dim = editing
                if (mapState.rawPath.size >= 2) {
                    Polyline(points = mapState.rawPath, color = Color(0x33888888), width = 5f)
                }
                mapState.segments.forEach { seg ->
                    Polyline(
                        points = seg.points,
                        color = if (dim) Color(0x33888888) else modeColor(seg.mode),
                        width = if (seg.confirmed) 16f else 11f,
                    )
                }
                // Yellow place rings (fill only, no edge).
                mapState.placeRings.forEach { ring ->
                    Circle(center = ring.center, radius = ring.radiusMeters, strokeColor = Color.Transparent, strokeWidth = 0f, fillColor = PLACE_RING.copy(alpha = if (dim) 0.06f else 0.18f))
                }
                // Visits "my location" style: translucent accuracy circle (meters) + a fixed
                // screen-size dot marker that never grows/shrinks with zoom.
                mapState.visits.forEach { v ->
                    Circle(center = v.center, radius = v.radiusMeters, strokeColor = Color.Transparent, strokeWidth = 0f, fillColor = VISIT_BLUE.copy(alpha = if (dim) 0.10f else 0.22f))
                    if (dotIcon != null) {
                        Marker(
                            state = rememberMarkerState(key = "${v.center.latitude},${v.center.longitude}", position = v.center),
                            icon = dotIcon,
                            anchor = Offset(0.5f, 0.5f),
                            flat = true,
                            zIndex = 1f,
                        )
                    }
                }
                // Split/reclassify preview.
                if (editing && editPoints.size >= 2) {
                    val idx = splitIndex
                    if (idx != null && idx in 1 until editPoints.size) {
                        Polyline(points = editPoints.subList(0, idx + 1), color = Color(0xFF2E7D32), width = 18f)
                        Polyline(points = editPoints.subList(idx, editPoints.size), color = Color(0xFF1565C0), width = 18f)
                    } else {
                        Polyline(points = editPoints, color = typeColor(reclassifyType), width = 18f)
                    }
                }
            }
            FloatingActionButton(
                onClick = { scope.launch { viewModel.currentLatLng()?.let { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 16f)) } } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = SHEET_PEEK + 16.dp),
            ) { Icon(Icons.Filled.MyLocation, contentDescription = "Recenter on my location") }
        }
    }

    confirmVisit?.let { visit ->
        ConfirmPlaceSheet(
            visit = visit,
            localPlaces = places,
            loadNearby = { lat, lon -> viewModel.nearbySuggestions(lat, lon) },
            searchPlaces = { q, lat, lon -> viewModel.searchPlaces(q, lat, lon) },
            onConfirm = { choice -> viewModel.confirmVisit(visit.id, choice); confirmVisit = null },
            onDismiss = { confirmVisit = null },
        )
    }

    editPlace?.let { place ->
        PlaceEditDialog(
            place = place,
            onSave = { name, address, lat, lon, radius, fixed ->
                viewModel.updatePlace(place.copy(name = name, address = address, latitude = lat, longitude = lon, radiusMeters = radius, fixed = fixed))
                editPlace = null
            },
            onDismiss = { editPlace = null },
        )
    }

    detailPlaceId?.let { id -> PlaceDetailDialog(placeId = id, onDismiss = { detailPlaceId = null }) }
}

// --- day header -------------------------------------------------------------------------------

@Composable
private fun DayHeader(dayEpoch: Long, isToday: Boolean, onToday: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Format.date(dayEpoch),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (!isToday) {
            IconButton(onClick = onToday) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Jump to today")
            }
        }
    }
    HorizontalDivider()
}

// --- map helpers ------------------------------------------------------------------------------

private val VISIT_BLUE = Color(0xFF4285F4)
private val PLACE_RING = Color(0xFFFFD54F)

/** A constant screen-size "my location" dot (white ring + coloured center) as a marker icon. */
@Composable
private fun rememberDotIcon(color: Color): BitmapDescriptor? = remember(color) {
    runCatching {
        val size = 22
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val c = size / 2f
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(c, c, c, paint)
        paint.color = color.toArgb()
        canvas.drawCircle(c, c, c - 4f, paint)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }.getOrNull()
}

/** Highlight colour for a reclassify preview, by the chosen target type. */
private fun typeColor(type: SegmentType?): Color = when (type) {
    null, SegmentType.Stationary -> VISIT_BLUE
    is SegmentType.Moving -> modeColor(type.mode)
}

private fun MapState.boundsOrNull(): LatLngBounds? {
    val points = ArrayList<LatLng>()
    val rings = visits.map { it.center to it.radiusMeters } + placeRings.map { it.center to it.radiusMeters }
    rings.forEach { (center, radius) ->
        val box = net.extrawdw.apps.locationhistory.core.Geo.boundingBox(center.latitude, center.longitude, radius.coerceAtLeast(60.0))
        points.add(LatLng(box[0], box[1])); points.add(LatLng(box[2], box[3]))
    }
    segments.forEach { points.addAll(it.points) }
    if (points.isEmpty()) points.addAll(rawPath)
    if (points.isEmpty()) return null
    val b = LatLngBounds.builder()
    points.forEach { b.include(it) }
    return runCatching { b.build() }.getOrNull()
}

private fun TimelineItem.currentType(): SegmentType = when (this) {
    is TimelineItem.VisitItem -> SegmentType.Stationary
    is TimelineItem.TripItem -> SegmentType.Moving(segments.firstOrNull()?.mode ?: TransportMode.WALKING)
}

private fun TimelineItem.itemKey(): String = when (this) {
    is TimelineItem.VisitItem -> "v${visit.id}"
    is TimelineItem.TripItem -> "t${trip.id}"
}

fun modeColor(mode: TransportMode): Color = when (mode) {
    TransportMode.WALKING -> Color(0xFF2E7D32)
    TransportMode.RUNNING -> Color(0xFF1B5E20)
    TransportMode.CYCLING -> Color(0xFF00897B)
    TransportMode.CAR -> Color(0xFF1565C0)
    TransportMode.BUS -> Color(0xFFF9A825)
    TransportMode.RAIL -> Color(0xFF6A1B9A)
    TransportMode.FERRY -> Color(0xFF0277BD)
    TransportMode.FLIGHT -> Color(0xFFC62828)
    TransportMode.UNKNOWN -> Color(0xFF757575)
}

@Composable
private fun EmptyDay(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No activity recorded for this day", style = MaterialTheme.typography.bodyLarge)
    }
}

// --- rail rows --------------------------------------------------------------------------------

private val GUTTER_WIDTH = 44.dp
private val NODE_DOT_Y = 30.dp

@Composable
private fun VisitRow(
    item: TimelineItem.VisitItem,
    isFirst: Boolean,
    isLast: Boolean,
    topColor: Color?,
    bottomColor: Color?,
    onConfirm: () -> Unit,
    onOpenPlace: () -> Unit,
    onEditPlace: () -> Unit,
    onEditSamples: () -> Unit,
) {
    val dotColor = if (item.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        Box(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val dotY = NODE_DOT_Y.toPx().coerceAtMost(size.height - 4.dp.toPx())
                val topY = if (isFirst) dotY else 0f
                val bottomY = if (isLast) dotY else size.height
                if (dotY > topY) drawLine(topColor ?: lineColor, Offset(cx, topY), Offset(cx, dotY), if (topColor != null) 6.dp.toPx() else 3.dp.toPx(), StrokeCap.Round)
                if (bottomY > dotY) drawLine(bottomColor ?: lineColor, Offset(cx, dotY), Offset(cx, bottomY), if (bottomColor != null) 6.dp.toPx() else 3.dp.toPx(), StrokeCap.Round)
                drawCircle(surfaceColor, 9.dp.toPx(), Offset(cx, dotY))
                drawCircle(dotColor, 6.dp.toPx(), Offset(cx, dotY))
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).then(if (item.place != null) Modifier.clickable(onClick = onOpenPlace) else Modifier),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (item.place != null) {
                                DropdownMenuItem(text = { Text("Place details") }, onClick = { menuOpen = false; onOpenPlace() })
                                DropdownMenuItem(text = { Text("Edit place") }, onClick = { menuOpen = false; onEditPlace() })
                            }
                            DropdownMenuItem(text = { Text("Edit samples (split)") }, onClick = { menuOpen = false; onEditSamples() })
                        }
                    }
                }
                Text(
                    "${Format.time(item.startMs)} – ${Format.time(item.endMs)} · ${Format.duration(item.startMs, item.endMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!item.confirmed) {
                        UnconfirmedChip()
                        AssistChip(onClick = onConfirm, label = { Text("Confirm place") })
                    } else {
                        AssistChip(onClick = onConfirm, label = { Text("Change place") })
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(
    item: TimelineItem.TripItem,
    onConfirmSegment: (Long, TransportMode) -> Unit,
    onMarkStationary: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        Box(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) { DashedTripLine(item, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp)) {
            val segments = item.segments
            if (segments.isEmpty()) {
                CompactTripLine(
                    icon = { Icon(Format.transportIcon(TransportMode.UNKNOWN), null, tint = modeColor(TransportMode.UNKNOWN)) },
                    text = "Moving · ${Format.distance(item.trip.distanceMeters)} · ${Format.duration(item.startMs, item.endMs)}",
                    confirmed = item.trip.confirmed,
                    onConfirm = { onEdit() },
                    showEdit = true,
                    onEdit = onEdit,
                )
            } else {
                segments.forEachIndexed { index, segment ->
                    var menuOpen by remember(segment.id) { mutableStateOf(false) }
                    Box {
                        CompactTripLine(
                            icon = { Icon(Format.transportIcon(segment.mode), segment.mode.label, tint = modeColor(segment.mode)) },
                            text = "${segment.mode.label} · ${Format.distance(segment.distanceMeters)} · ${Format.duration(segment.startMs, segment.endMs)}",
                            confirmed = segment.confirmed,
                            onConfirm = { menuOpen = true },
                            showEdit = index == 0,
                            onEdit = onEdit,
                            topPadding = if (index == 0) 0.dp else 4.dp,
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Stationary (a place)") }, onClick = { menuOpen = false; onMarkStationary() })
                            HorizontalDivider()
                            TransportMode.MODEL_CLASSES.forEach { mode ->
                                DropdownMenuItem(text = { Text(mode.label) }, onClick = { menuOpen = false; onConfirmSegment(segment.id, mode) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTripLine(
    icon: @Composable () -> Unit,
    text: String,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    showEdit: Boolean,
    onEdit: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = topPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (confirmed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AssistChip(onClick = onConfirm, label = { Text(if (confirmed) "Edit mode" else "Confirm") })
        if (showEdit) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit trip") }
    }
}

@Composable
private fun DashedTripLine(item: TimelineItem.TripItem, modifier: Modifier) {
    val fallback = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val cx = size.width / 2
        val stroke = 6.dp.toPx()
        val segs = item.segments
        if (segs.isEmpty()) {
            drawLine(fallback, Offset(cx, 0f), Offset(cx, size.height), stroke, StrokeCap.Round)
            return@Canvas
        }
        val total = segs.sumOf { (it.endMs - it.startMs).coerceAtLeast(1L) }.toFloat()
        var y = 0f
        for (seg in segs.asReversed()) {
            val h = size.height * ((seg.endMs - seg.startMs).coerceAtLeast(1L) / total)
            drawLine(modeColor(seg.mode), Offset(cx, y), Offset(cx, y + h), stroke, StrokeCap.Round)
            y += h
        }
    }
}

private fun tripJoinColor(item: TimelineItem?, first: Boolean): Color? {
    val trip = item as? TimelineItem.TripItem ?: return null
    val seg = if (first) trip.segments.firstOrNull() else trip.segments.lastOrNull()
    return seg?.let { modeColor(it.mode) }
}

@Composable
private fun UnconfirmedChip() {
    AssistChip(onClick = {}, enabled = false, label = { Text("Unconfirmed") })
}
