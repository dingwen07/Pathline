package net.extrawdw.apps.locationhistory.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.domain.TimelineDay
import net.extrawdw.apps.locationhistory.domain.TimelineItem
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import javax.inject.Inject
import javax.inject.Singleton

/** How the user chose to resolve a visit's place when confirming it. */
sealed interface PlaceChoice {
    /** Link to an existing local place. */
    data class Existing(val placeId: Long) : PlaceChoice

    /** Save a Google Places suggestion as a new local place and link it. */
    data class Google(val candidate: PlaceCandidate) : PlaceChoice

    /** Create a new place at the visit centroid with the given name. */
    data class NewNamed(val name: String) : PlaceChoice

    /** Promote the visit's own inline candidate (or centroid) into the place DB. */
    data object PromoteCandidate : PlaceChoice
}

@Singleton
class TimelineRepository @Inject constructor(
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val placeDao: PlaceDao,
    private val sampleDao: LocationSampleDao,
    private val placeRepository: PlaceRepository,
    private val placesGateway: net.extrawdw.apps.locationhistory.data.places.PlacesGateway,
    private val locationRepository: LocationRepository,
    private val db: AppDatabase,
    private val writeLock: TimelineWriteLock,
) {

    /** Nearby Google Places suggestions for the confirm-place sheet. */
    suspend fun nearbyPlaceSuggestions(lat: Double, lon: Double): List<PlaceCandidate> =
        placesGateway.nearbyPlaces(lat, lon)

    /** Free-text Google Places search, biased to the visit location. */
    suspend fun searchPlaces(query: String, lat: Double, lon: Double): List<PlaceCandidate> =
        placesGateway.searchText(query, lat, lon)

    /** Reactive timeline for a single local day, ordered chronologically. Uses time-overlap so a
     *  visit or trip that crosses midnight shows on both days it touches. */
    fun observeDay(dayEpoch: Long): Flow<TimelineDay> {
        val range = TimeBuckets.dayRangeMillis(dayEpoch)
        val startMs = range.first
        val endMs = range.last + 1
        return combine(
            visitDao.observeOverlapping(startMs, endMs),
            tripDao.observeOverlapping(startMs, endMs),
        ) { visits, trips ->
            val items = ArrayList<TimelineItem>(visits.size + trips.size)
            for (v in visits) {
                val place = v.placeId?.let { placeDao.byId(it) }
                items.add(TimelineItem.VisitItem(v, place))
            }
            for (t in trips) items.add(TimelineItem.TripItem(t))
            items.sortByDescending { it.startMs } // newest first
            TimelineDay(dayEpoch, items)
        }
    }

    fun observeRecordedDays(): Flow<List<Long>> = sampleDao.observeRecordedDays()

    fun observeUnconfirmedVisits() = visitDao.observeUnconfirmed()

    /** Back-compat: null promotes the inline candidate, non-null links an existing place. */
    suspend fun confirmVisitPlace(visitId: Long, chosenPlaceId: Long?) =
        confirmVisitPlace(
            visitId,
            chosenPlaceId?.let { PlaceChoice.Existing(it) } ?: PlaceChoice.PromoteCandidate,
        )

    /**
     * Confirm which place a visit happened at. The user's [choice] is authoritative: it links an
     * existing place, saves a Google suggestion, or creates a new one. Confirming feeds the place
     * model ([PlaceRepository.recordVisitToPlace] updates its center/radius unless fixed).
     *
     * Serialized against the maintenance rebuild via [TimelineWriteLock] and run inside one Room
     * transaction: the visit is re-read inside it, so if the (still unconfirmed) row was deleted by
     * a rebuild in the meantime, the confirm is a clean no-op — no orphan place is inserted and no
     * zero-row `@Update` silently swallows the user's input.
     */
    suspend fun confirmVisitPlace(visitId: Long, choice: PlaceChoice): Unit = writeLock.withLock {
        db.withTransaction {
            val visit = visitDao.byId(visitId) ?: return@withTransaction
            val placeId = when (choice) {
                is PlaceChoice.Existing -> choice.placeId
                is PlaceChoice.Google -> placeRepository.confirmPlace(
                    name = choice.candidate.name,
                    latitude = choice.candidate.latitude,
                    longitude = choice.candidate.longitude,
                    googlePlaceId = choice.candidate.googlePlaceId,
                    address = choice.candidate.address,
                    category = choice.candidate.primaryType,
                    types = choice.candidate.types.joinToString(",").ifEmpty { null },
                    source = PlaceSource.MAPS,
                )

                is PlaceChoice.NewNamed -> placeRepository.confirmPlace(
                    name = choice.name,
                    latitude = visit.centroidLatitude,
                    longitude = visit.centroidLongitude,
                    googlePlaceId = null, address = null, category = null,
                    source = PlaceSource.USER,
                )

                PlaceChoice.PromoteCandidate -> placeRepository.confirmPlace(
                    name = visit.candidateName ?: "Place",
                    latitude = visit.candidateLatitude ?: visit.centroidLatitude,
                    longitude = visit.candidateLongitude ?: visit.centroidLongitude,
                    googlePlaceId = visit.candidateGooglePlaceId,
                    address = null, category = null,
                    source = if (visit.candidateGooglePlaceId != null) PlaceSource.MAPS else PlaceSource.USER,
                )
            }
            // Link + confirm the visit first so the place recompute counts it as a (weighted) ground-truth
            // visit, then recompute the place center/radius as a recency/confirmation-weighted mean.
            // Clear the candidate (the geocoder's pre-confirmation guess) so a confirmed visit can't keep a
            // name/coords that diverge from the place the user actually chose — placeId is now authoritative.
            visitDao.update(
                visit.copy(
                    placeId = placeId,
                    confirmed = true,
                    confidence = 1f,
                    candidateName = null,
                    candidateGooglePlaceId = null,
                    candidateLatitude = null,
                    candidateLongitude = null,
                ),
            )
            placeRepository.recordVisitToPlace(placeId)
            // Mark fixes outside the (updated) place circle as GPS drift (bogus).
            placeRepository.byId(placeId)?.let { p ->
                locationRepository.excludeDriftOutside(
                    visit.startMs,
                    visit.endMs,
                    p.latitude,
                    p.longitude,
                    p.radiusMeters
                )
            }
        }
    }

    /**
     * Confirm a trip's transport mode. Treated as ground truth: the trip is marked confirmed, so
     * maintenance leaves it alone.
     *
     * Serialized + transactional like [confirmVisitPlace]: the trip is re-read inside the
     * transaction, so a row deleted by a concurrent rebuild makes this a clean no-op instead of a
     * zero-row `@Update` that silently drops the user's confirmation.
     */
    suspend fun confirmTripMode(tripId: Long, mode: TransportMode): Unit = writeLock.withLock {
        db.withTransaction {
            val trip = tripDao.byId(tripId) ?: return@withTransaction
            val samples = sampleDao.rangeForComputation(trip.startMs, trip.endMs + 1)

            // Rebuild geometry from the fixes in recorded order, like convertItemType does -- otherwise
            // confirming the mode left the stored polyline untouched, so a trip scrambled by an earlier
            // overlapping merge kept its straight spike. Too few fixes to draw a line: just relabel.
            val points = samples.map { it.latitude to it.longitude }
            val relabeled = trip.copy(mode = mode, modeConfidence = 1f, confirmed = true)
            tripDao.update(
                if (points.size >= 2) {
                    relabeled.copy(
                        encodedPolyline = Geo.encodePolyline(points),
                        distanceMeters = Geo.pathLengthMeters(points),
                    )
                } else {
                    relabeled
                },
            )
        }
    }
}
