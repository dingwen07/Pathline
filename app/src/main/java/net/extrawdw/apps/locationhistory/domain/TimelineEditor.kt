package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.TripSegmentEntity
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.TrainingRepository
import net.extrawdw.apps.locationhistory.ml.Features
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** The activity a span of samples should be reclassified as in the editor. */
sealed interface SegmentType {
    data object Stationary : SegmentType
    data class Moving(val mode: TransportMode) : SegmentType
}

/**
 * Hand-editing of the timeline for GPS-drift correction. Operates at the **sample** level: a span
 * of samples is reclassified as a stationary stay or a moving segment, materializing the right
 * entity from the raw fixes. Every edit is treated as user ground truth, so it also records a
 * training example, and it kicks off an auto-merge of the affected day.
 */
@Singleton
class TimelineEditor @Inject constructor(
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val sampleDao: LocationSampleDao,
    private val placeMatcher: PlaceMatcher,
    private val placeRepository: PlaceRepository,
    private val trainingRepository: TrainingRepository,
    private val workScheduler: WorkScheduler,
) {

    /** All samples covered by a timeline item, time-ordered (used to drive the split slider). */
    suspend fun samplesFor(item: TimelineItem): List<LocationSampleEntity> =
        sampleDao.range(item.startMs, item.endMs + 1)

    /**
     * Split [item] at [splitIndex] (1..size-1) into a [leftType] span and a [rightType] span. The
     * original visit/trip is removed and replaced by the two materialized spans.
     */
    suspend fun splitItem(
        item: TimelineItem,
        splitIndex: Int,
        leftType: SegmentType,
        rightType: SegmentType,
    ) {
        val samples = samplesFor(item)
        if (samples.size < 2) return
        val k = splitIndex.coerceIn(1, samples.size - 1)
        deleteItem(item)
        materialize(samples.subList(0, k), leftType)
        materialize(samples.subList(k, samples.size), rightType)
        workScheduler.enqueueTimelineMaintenanceNow(TimeBuckets.dayEpoch(item.startMs), "edit_split")
    }

    /** Reclassify a whole item to [type] without splitting. */
    suspend fun convertItemType(item: TimelineItem, type: SegmentType) {
        val samples = samplesFor(item)
        if (samples.isEmpty()) return
        deleteItem(item)
        materialize(samples, type)
        workScheduler.enqueueTimelineMaintenanceNow(TimeBuckets.dayEpoch(item.startMs), "edit_convert")
    }

    private suspend fun deleteItem(item: TimelineItem) {
        when (item) {
            is TimelineItem.VisitItem -> visitDao.delete(item.visit.id)
            is TimelineItem.TripItem -> {
                tripDao.deleteSegmentsForTrip(item.trip.id)
                tripDao.deleteTrip(item.trip.id)
            }
        }
    }

    /** Turn a span of samples into the persisted entity for [type] and record a training example. */
    private suspend fun materialize(span: List<LocationSampleEntity>, type: SegmentType) {
        if (span.isEmpty()) return
        val usable = span.filter { it.includedInComputation }.ifEmpty { span }
        val startMs = span.first().timestampMs
        val endMs = span.last().timestampMs
        when (type) {
            SegmentType.Stationary -> {
                val fallback = Geo.centroid(usable.map { it.latitude to it.longitude })
                val initialGeom = VisitGeometry.compute(usable, fallback.first, fallback.second)
                val match = runCatching { placeMatcher.match(initialGeom.latitude, initialGeom.longitude) }.getOrNull()
                val ring = when (match) {
                    is PlaceMatch.Local -> Triple(match.place.latitude, match.place.longitude, match.place.radiusMeters)
                    else -> Triple(initialGeom.latitude, initialGeom.longitude, initialGeom.radiusMeters)
                }
                // Mark fixes outside the circle as GPS drift (bogus) before deriving the final
                // visit geometry and feeding place/training updates.
                for (s in span) {
                    if (s.includedInComputation &&
                        Geo.distanceMeters(ring.first, ring.second, s.latitude, s.longitude) > ring.third
                    ) {
                        sampleDao.markExcluded(s.id, "drift_outside_place")
                    }
                }
                val cleanUsable = usable.filter {
                    Geo.distanceMeters(ring.first, ring.second, it.latitude, it.longitude) <= ring.third
                }.ifEmpty { usable }
                val geom = VisitGeometry.compute(cleanUsable, ring.first, ring.second)
                val visit = applyMatch(
                    VisitEntity(
                        placeId = null,
                        candidateName = null, candidateGooglePlaceId = null,
                        candidateLatitude = null, candidateLongitude = null,
                        startMs = startMs, endMs = endMs, dayEpoch = TimeBuckets.dayEpoch(startMs),
                        centroidLatitude = geom.latitude, centroidLongitude = geom.longitude,
                        radiusMeters = geom.radiusMeters,
                        confirmed = true, confidence = 1f, isOngoing = false,
                    ),
                    match,
                )
                val id = visitDao.insert(visit)
                visit.placeId?.let { placeRepository.recordVisitToPlace(it, cleanUsable) }
                trainingRepository.addStateExample(
                    features = Features.stationaryFeatures(cleanUsable),
                    label = DevicePhysicalState.MODEL_CLASSES.indexOf(DevicePhysicalState.STATIONARY),
                    fromUserConfirmation = true,
                )
                id
            }
            is SegmentType.Moving -> {
                val points = usable.map { it.latitude to it.longitude }
                val distance = Geo.pathLengthMeters(points)
                val tripId = tripDao.insert(
                    TripEntity(
                        fromVisitId = null, toVisitId = null,
                        startMs = startMs, endMs = endMs, dayEpoch = TimeBuckets.dayEpoch(startMs),
                        distanceMeters = distance, confirmed = true,
                    ),
                )
                tripDao.insertSegment(
                    TripSegmentEntity(
                        tripId = tripId, startMs = startMs, endMs = endMs,
                        mode = type.mode, modeConfidence = 1f, confirmed = true,
                        encodedPolyline = Geo.encodePolyline(points), distanceMeters = distance,
                    ),
                )
                if (usable.size >= 2 && type.mode in TransportMode.MODEL_CLASSES) {
                    trainingRepository.addTransportExample(
                        features = Features.transportFeatures(usable),
                        label = TransportMode.MODEL_CLASSES.indexOf(type.mode),
                        fromUserConfirmation = true,
                    )
                }
            }
        }
    }

    private fun applyMatch(visit: VisitEntity, match: PlaceMatch?): VisitEntity = when (match) {
        is PlaceMatch.Local -> visit.copy(placeId = match.place.id, candidateName = match.place.name)
        is PlaceMatch.Candidate -> visit.copy(
            candidateName = match.candidate.name,
            candidateGooglePlaceId = match.candidate.googlePlaceId,
            candidateLatitude = match.candidate.latitude,
            candidateLongitude = match.candidate.longitude,
        )
        PlaceMatch.None, null -> visit
    }
}
