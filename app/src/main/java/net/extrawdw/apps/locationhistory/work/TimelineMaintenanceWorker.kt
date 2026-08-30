package net.extrawdw.apps.locationhistory.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.hilt.work.HiltWorker
import androidx.room3.withWriteTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.LegacyPlaceCoordinateManager
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.RecordingRepository
import net.extrawdw.apps.locationhistory.data.repo.AutomaticNearbyClaim
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.domain.PlaceMatcher
import net.extrawdw.apps.locationhistory.domain.PlaceMatch
import net.extrawdw.apps.locationhistory.domain.TimelineMerger
import net.extrawdw.apps.locationhistory.domain.TimelineRebuilder
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import net.extrawdw.apps.locationhistory.domain.TripSegmenter
import net.extrawdw.apps.locationhistory.domain.VisitDetector
import net.extrawdw.apps.locationhistory.service.Perf
import net.extrawdw.apps.locationhistory.security.MapsApiKeyVault
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authoritative maintenance entry point for derived timeline state. The foreground recorder only
 * appends samples and labels light state; this worker hands the affected day to
 * [TimelineRebuilder] — the actual pipeline (detect stays, persist geometry, match places,
 * segment transport, merge, cleanup), kept as a plain JVM-testable class. Here lives only the
 * WorkManager plumbing and the Android-bound seams the rebuilder takes as functions.
 */
@HiltWorker
class TimelineMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val sampleDao: LocationSampleDao,
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val locationRepository: LocationRepository,
    private val recordingRepository: RecordingRepository,
    private val placeRepository: PlaceRepository,
    private val legacyPlaceCoordinates: LegacyPlaceCoordinateManager,
    private val visitDetector: VisitDetector,
    private val placeMatcher: PlaceMatcher,
    private val settingsRepository: SettingsRepository,
    private val mapsApiKeyVault: MapsApiKeyVault,
    private val tripSegmenter: TripSegmenter,
    private val merger: TimelineMerger,
    private val timelineWriteLock: TimelineWriteLock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val day = inputData.getLong(KEY_DAY, TimeBuckets.dayEpoch(System.currentTimeMillis()))
        val reason = inputData.getString(KEY_REASON) ?: "unspecified"
        val userStarted = reason == REASON_PULL_REFRESH
        AppLog.i(TAG, "maintenance day=$day reason=$reason")

        // Idempotent and lock-serialized: prove any exact-identity legacy rows before a rebuild
        // considers place geometry. Ambiguous rows remain excluded.
        legacyPlaceCoordinates.classifySafeRows()

        // Only actual remote Nearby calls count here. Local-place matching is always free. Automatic
        // maintenance never calls Places for the ongoing visit; a pull-to-refresh may. Finalized
        // visits share the durable checked-through floor and daily allowance in every maintenance mode.
        val placeLookups = AtomicInteger(0)

        val rebuilder = TimelineRebuilder(
            sampleDao = sampleDao,
            visitDao = visitDao,
            tripDao = tripDao,
            locationRepository = locationRepository,
            recordingRepository = recordingRepository,
            placeRepository = placeRepository,
            visitDetector = visitDetector,
            merger = merger,
            matchPlace = { visitStartMs, visitEndMs, lat, lon, isOngoing ->
                val local = placeMatcher.matchLocal(lat, lon)
                if (local is PlaceMatch.Local) {
                    local
                } else if (isOngoing) {
                    if (userStarted && mapsApiKeyVault.configured.value) {
                        placeLookups.incrementAndGet()
                        Perf.trace("place_match_manual_current") {
                            placeMatcher.lookupNearby(lat, lon)
                        }
                    } else {
                        PlaceMatch.None
                    }
                } else {
                    when (
                        settingsRepository.claimAutomaticNearbyLookup(
                            visitStartMs = visitStartMs,
                            visitEndMs = visitEndMs,
                            nowMs = System.currentTimeMillis(),
                            // The claim consumes this visit's durable at-most-once allowance, so key
                            // presence alone is insufficient: do not consume it while offline or
                            // behind an unvalidated/captive network.
                            networkEligible = mapsApiKeyVault.configured.value &&
                                    hasValidatedInternet(),
                        )
                    ) {
                        AutomaticNearbyClaim.ALLOWED -> {
                            placeLookups.incrementAndGet()
                            Perf.trace("place_match_finalized") {
                                placeMatcher.lookupNearby(lat, lon)
                            }
                        }
                        AutomaticNearbyClaim.BELOW_FLOOR,
                        AutomaticNearbyClaim.SKIPPED -> PlaceMatch.None
                    }
                }
            },
            segmentTrips = tripSegmenter::segment,
            inTransaction = { block -> db.withWriteTransaction { block() } },
            log = { AppLog.w(TAG, it) },
        )
        // The whole rebuild holds the timeline write lock, so a user confirmation or hand edit can
        // never interleave with the delete-unconfirmed/reinsert sweep (which would silently drop it).
        val visits = Perf.trace("timeline_rebuild") { span ->
            span.attribute("reason", reason)
            timelineWriteLock.withLock { rebuilder.rebuildDay(day) }.also {
                span.metric("visits", it.toLong())
                span.metric("place_lookups", placeLookups.get().toLong())
            }
        }

        AppLog.i(TAG, "maintenance complete day=$day visits=$visits")
        return Result.success()
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        const val KEY_DAY = "day_epoch"
        const val KEY_REASON = "reason"
        const val REASON_PULL_REFRESH = "pull_refresh"
        private const val TAG = "TimelineMaintenance"
    }
}
