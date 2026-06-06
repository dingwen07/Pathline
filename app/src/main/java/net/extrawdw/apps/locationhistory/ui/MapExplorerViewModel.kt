package net.extrawdw.apps.locationhistory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToLong

/** Time windows the map explorer can plot. */
enum class MapRange(@param:androidx.annotation.StringRes val labelRes: Int) {
    TODAY(net.extrawdw.apps.locationhistory.R.string.range_today),
    WEEK(net.extrawdw.apps.locationhistory.R.string.range_week),
    MONTH(net.extrawdw.apps.locationhistory.R.string.range_month),
    YEAR(net.extrawdw.apps.locationhistory.R.string.range_year),
    CUSTOM(net.extrawdw.apps.locationhistory.R.string.range_custom),
}

data class MapExplorerState(
    /** Positions to draw as red dots (already thinned/deduped off the UI thread). */
    val dotPoints: List<LatLng> = emptyList(),
    /** Ordered points for the connecting track polyline; empty when no track is drawn. */
    val trackPoints: List<LatLng> = emptyList(),
    /** Raw sample count for the window, before thinning (shown in the control bar). */
    val totalCount: Int = 0,
    val loading: Boolean = false,
)

/** Backs the standalone "Map" tab: plots raw recorded samples for a chosen time window. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapExplorerViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
) : ViewModel() {

    // Camera state lives in the ViewModel so the map keeps its zoom/center across tab switches
    // (the screen leaves composition when another tab is shown). `lastFittedPoints` records the
    // exact dataset the camera was last framed to, so we only reframe when the data changes —
    // never on a plain re-entry. `cameraFramed` guards the one-time initial centering.
    val cameraPositionState = CameraPositionState()
    var cameraFramed = false
    var lastFittedPoints: List<LatLng>? = null

    private val _range = MutableStateFlow(MapRange.TODAY)
    val range: StateFlow<MapRange> = _range.asStateFlow()

    /** Inclusive custom range bounds, as local-day epochs; null until the user picks them. */
    private val _customStart = MutableStateFlow<Long?>(null)
    val customStart: StateFlow<Long?> = _customStart.asStateFlow()
    private val _customEnd = MutableStateFlow<Long?>(null)
    val customEnd: StateFlow<Long?> = _customEnd.asStateFlow()

    /** Whether to connect points for a custom range (today/week always connect; month/year never). */
    private val _drawTrackCustom = MutableStateFlow(false)
    val drawTrackCustom: StateFlow<Boolean> = _drawTrackCustom.asStateFlow()

    val state: StateFlow<MapExplorerState> =
        combine(_range, _customStart, _customEnd, _drawTrackCustom) { r, cs, ce, draw ->
            QueryKey(r, cs, ce, draw)
        }
            .flatMapLatest { key ->
                flow {
                    emit(MapExplorerState(loading = true))
                    val window = rangeMillis(key.range, key.customStart, key.customEnd)
                    if (window == null) {
                        emit(MapExplorerState())
                        return@flow
                    }
                    val drawTrack = shouldDrawTrack(key.range, key.drawTrackCustom)
                    // With a track we exclude flagged samples (mock/low-accuracy/drift) so the line
                    // doesn't jump to bogus fixes; without a track, plot every recorded sample.
                    val samples = if (drawTrack) {
                        locationRepository.rangeForComputation(window.first, window.second)
                    } else {
                        locationRepository.range(window.first, window.second)
                    }
                    // All point processing happens here on Dispatchers.IO — never on the UI thread.
                    val state = if (drawTrack) {
                        // Thin stationary stretches so the polyline + dots stay light.
                        val track = thinTrack(samples)
                        MapExplorerState(
                            dotPoints = track,
                            trackPoints = track,
                            totalCount = samples.size
                        )
                    } else {
                        // Keep coverage but collapse coordinates that land in the same ~1 m cell
                        // (true duplicate fixes), which tames stacked stationary samples.
                        MapExplorerState(
                            dotPoints = dedupeByCell(samples),
                            totalCount = samples.size
                        )
                    }
                    emit(state)
                }.flowOn(Dispatchers.IO)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                MapExplorerState(loading = true)
            )

    private data class QueryKey(
        val range: MapRange,
        val customStart: Long?,
        val customEnd: Long?,
        val drawTrackCustom: Boolean,
    )

    fun selectRange(r: MapRange) {
        _range.value = r
    }

    fun setCustomRange(startDayEpoch: Long, endDayEpoch: Long) {
        _customStart.value = startDayEpoch
        _customEnd.value = endDayEpoch
    }

    fun setDrawTrackCustom(value: Boolean) {
        _drawTrackCustom.value = value
    }

    /** Connect samples with a track for today/week, and for custom only when the user opts in. */
    fun shouldDrawTrack(r: MapRange, drawCustom: Boolean): Boolean = when (r) {
        MapRange.TODAY, MapRange.WEEK -> true
        MapRange.CUSTOM -> drawCustom
        MapRange.MONTH, MapRange.YEAR -> false
    }

    /** Inclusive-start / exclusive-end epoch-millis for the selected window, or null if incomplete. */
    private fun rangeMillis(r: MapRange, customStart: Long?, customEnd: Long?): Pair<Long, Long>? {
        val today = TimeBuckets.dayEpoch(System.currentTimeMillis())
        val endMs = TimeBuckets.dayRangeMillis(today).last + 1
        return when (r) {
            MapRange.TODAY -> TimeBuckets.dayRangeMillis(today).first to endMs
            MapRange.WEEK -> TimeBuckets.dayRangeMillis(today - 6).first to endMs
            MapRange.MONTH -> {
                val start =
                    TimeBuckets.dayEpoch(LocalDate.ofEpochDay(today).minusMonths(1).plusDays(1))
                TimeBuckets.dayRangeMillis(start).first to endMs
            }

            MapRange.YEAR -> {
                val start =
                    TimeBuckets.dayEpoch(LocalDate.ofEpochDay(today).minusYears(1).plusDays(1))
                TimeBuckets.dayRangeMillis(start).first to endMs
            }

            MapRange.CUSTOM -> {
                if (customStart == null || customEnd == null) return null
                val lo = minOf(customStart, customEnd)
                val hi = maxOf(customStart, customEnd)
                TimeBuckets.dayRangeMillis(lo).first to (TimeBuckets.dayRangeMillis(hi).last + 1)
            }
        }
    }

    /**
     * Decimate a sample stream for track drawing: keep a point only once it has moved at least
     * [MIN_MOVE_METERS] from the last kept point, or [MAX_STATIONARY_GAP_MS] has elapsed. While the
     * device is stationary (no movement) this yields ~12 points/hour instead of one per fix.
     */
    private fun thinTrack(samples: List<LocationSampleEntity>): List<LatLng> {
        if (samples.isEmpty()) return emptyList()
        val out = ArrayList<LatLng>(samples.size / 4 + 1)
        var lastLat = 0.0
        var lastLon = 0.0
        var lastTime = 0L
        var seeded = false
        for (s in samples) {
            if (!seeded) {
                out.add(LatLng(s.latitude, s.longitude))
                lastLat = s.latitude; lastLon = s.longitude; lastTime = s.timestampMs; seeded = true
                continue
            }
            val moved = Geo.distanceMeters(lastLat, lastLon, s.latitude, s.longitude)
            if (moved >= MIN_MOVE_METERS || s.timestampMs - lastTime >= MAX_STATIONARY_GAP_MS) {
                out.add(LatLng(s.latitude, s.longitude))
                lastLat = s.latitude; lastLon = s.longitude; lastTime = s.timestampMs
            }
        }
        return out
    }

    /** Collapse samples whose coordinates round to the same ~1 m grid cell into a single dot. */
    private fun dedupeByCell(samples: List<LocationSampleEntity>): List<LatLng> {
        val seen = HashSet<Long>(samples.size)
        val out = ArrayList<LatLng>(samples.size)
        for (s in samples) {
            val latCell = (s.latitude * CELLS_PER_DEGREE).roundToLong()
            val lonCell = (s.longitude * CELLS_PER_DEGREE).roundToLong()
            val key = (latCell shl 32) xor (lonCell and 0xFFFFFFFFL)
            if (seen.add(key)) out.add(LatLng(s.latitude, s.longitude))
        }
        return out
    }

    /** Where to point the camera before any data loads: the most recent recorded sample. */
    suspend fun initialCameraTarget(): LatLng? =
        locationRepository.mostRecent()?.let { LatLng(it.latitude, it.longitude) }

    private companion object {
        const val MIN_MOVE_METERS = 11.0
        const val MAX_STATIONARY_GAP_MS = 300_000L // ~12 kept points/hour while stationary
        const val CELLS_PER_DEGREE = 111_000.0 // ≈ 1 m grid (1° latitude ≈ 111 km)
    }
}
