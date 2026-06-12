package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.RecordingRepository

/**
 * The timeline rebuild pipeline: detect stays over a widened window, materialize them as
 * unconfirmed visits (geometry + place match), keep the single confirmed ongoing visit tracking
 * the present, fill the inter-stay gaps with segmented trips (working around confirmed runs),
 * merge, and sweep dangling trip endpoints.
 *
 * Extracted from `TimelineMaintenanceWorker` so the historically regressing rules (overnight
 * split, vanishing trips, resurrected converted stays) are a plain class over DAO interfaces —
 * JVM-testable with the in-memory fakes, like [TimelineMerger]. The worker keeps WorkManager
 * plumbing and supplies the Android-bound seams: [matchPlace] (Google Places gateway),
 * [segmentTrips] (LiteRT classifier), [inTransaction] (Room), [now] and [log].
 *
 * The rebuild runs in two phases: phase 1 (no transaction) does every read and resolves the place
 * matches — the network calls; phase 2 wraps EVERY write (delete unconfirmed, materialize visits,
 * rebuild trips, merge, dangling-ref sweep) in a single [inTransaction] block, so observers and a
 * crash mid-rebuild see either the old derived timeline or the new one, never a half-deleted day.
 *
 * User-confirmed rows are left in place and treated as ground truth throughout.
 */
internal class TimelineRebuilder(
    private val sampleDao: LocationSampleDao,
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val locationRepository: LocationRepository,
    private val recordingRepository: RecordingRepository,
    private val placeRepository: PlaceRepository,
    private val visitDetector: VisitDetector,
    private val merger: TimelineMerger,
    /** Resolve a visit centroid to a place — [PlaceMatcher.match] in production. */
    private val matchPlace: suspend (lat: Double, lon: Double) -> PlaceMatch,
    /** Split moving samples into single-mode runs — [TripSegmenter.segment] in production. */
    private val segmentTrips: (List<LocationSampleEntity>) -> List<SegmentResult>,
    /** Atomicity for the whole write phase (delete -> materialize -> trips -> merge -> sweep) —
     *  `db.withTransaction` in production. Place matches are resolved *before* it so no network
     *  call ever runs inside the transaction. */
    private val inTransaction: suspend (block: suspend () -> Unit) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    /** Rebuild the derived timeline for one local day. Returns the number of visits inserted. */
    suspend fun rebuildDay(day: Long): Int {
        val range = TimeBuckets.dayRangeMillis(day)
        val dayStart = range.first
        val dayEnd = range.last + 1

        // --- Phase 1: reads + place resolution (network), no transaction held -----------------

        // Load a window *wider than the day* so the visit detector sees a stay's full extent instead
        // of clipping it at midnight — a stay that crosses the boundary is then a single spanning row
        // (shown on both days by the time-overlap display query), which is what stops an overnight
        // stay from splitting into two. Delete + rebuild stays scoped to rows overlapping the day, so
        // re-running any day reproduces the same rows.
        //
        // Invariant: the delete scope (rows overlapping the day) must never exceed the re-detection
        // scope (this sample window). An unconfirmed stay/trip can extend further than the fixed
        // lookback/lookahead (a multi-day stay rebuilt on its last day), so widen the window to the
        // full extent of the unconfirmed rows about to be deleted, plus a margin — anything deleted
        // is then guaranteed re-detectable instead of eroding at each midnight rebuild.
        val earliestUnconfirmedStart = minOf(
            visitDao.minUnconfirmedStartOverlapping(dayStart, dayEnd) ?: Long.MAX_VALUE,
            tripDao.minUnconfirmedStartOverlapping(dayStart, dayEnd) ?: Long.MAX_VALUE,
        )
        val latestUnconfirmedEnd = maxOf(
            visitDao.maxUnconfirmedEndOverlapping(dayStart, dayEnd) ?: Long.MIN_VALUE,
            tripDao.maxUnconfirmedEndOverlapping(dayStart, dayEnd) ?: Long.MIN_VALUE,
        )
        // The MAX_VALUE/MIN_VALUE sentinels stay outside min/max after the margin shift, so a day
        // with no unconfirmed rows keeps the default window.
        val loadStart = minOf(
            dayStart - Constants.REBUILD_LOOKBACK_MS,
            earliestUnconfirmedStart - Constants.REBUILD_SCOPE_MARGIN_MS,
        )
        val loadEnd = maxOf(
            dayEnd + Constants.REBUILD_LOOKAHEAD_MS,
            latestUnconfirmedEnd + Constants.REBUILD_SCOPE_MARGIN_MS,
        )
        val samples = sampleDao.range(loadStart, loadEnd)

        if (samples.isEmpty()) {
            // No evidence in the window: still clear the day's unconfirmed rows.
            inTransaction {
                tripDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)
                visitDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)
            }
            return 0
        }

        val confirmedVisits = visitDao.confirmedOverlapping(loadStart, loadEnd)
        // A confirmed trip hand-classifies a span as movement, so it must suppress stay re-detection
        // there like a confirmed visit does -- otherwise converting a stay to a moving segment instantly
        // re-detects it (the raw fixes still look stationary), resurrecting the visit just converted away.
        val confirmedTrips = tripDao.confirmedOverlapping(loadStart, loadEnd)
        val latest = sampleDao.mostRecent()
        // Detect stays over the full window (true extents), keep those that touch this day, and skip
        // any already covered by a confirmed (ground-truth) visit or a confirmed trip.
        val candidates = visitDetector.detectVisits(samples)
            .filter { it.overlaps(dayStart, dayEnd) }
            .filterNot { candidate ->
                confirmedVisits.any { it.overlaps(candidate.startMs, candidate.endMs + 1) } ||
                        confirmedTrips.any { candidate.overlaps(it.startMs, it.endMs + 1) }
            }
            .toMutableList()

        ongoingStationaryCandidate(samples, dayStart, dayEnd, latest)?.let { ongoing ->
            val overlapsDetected =
                candidates.any { it.overlaps(ongoing.startMs, ongoing.endMs + 1) }
            val overlapsConfirmed =
                confirmedVisits.any { it.overlaps(ongoing.startMs, ongoing.endMs + 1) } ||
                        confirmedTrips.any { ongoing.overlaps(it.startMs, it.endMs + 1) }
            if (!overlapsDetected && !overlapsConfirmed) candidates.add(ongoing)
        }

        // Resolve every candidate's place match up front — these are the (potentially slow) Google
        // Places lookups, and they must not run while the write transaction below is held.
        val resolved = candidates.mapNotNull { resolveCandidate(it, samples) }

        // --- Phase 2: one transaction over every write ------------------------------------------

        var insertedCount = 0
        inTransaction {
            tripDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)
            visitDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)

            for (candidate in resolved) {
                if (materializeVisit(candidate, latest) != null) insertedCount++
            }

            // Confirming a visit locks its *place*, not its *clock*: a confirmed visit that is still
            // ongoing must keep extending while the user is there, and finalize once they move on.
            // (Maintenance otherwise leaves confirmed visits untouched, which froze the end time.)
            extendConfirmedOngoingVisits(confirmedVisits, latest, now())

            rebuildTrips(loadStart, loadEnd, dayStart, dayEnd)
            // Show the trip that is *currently in progress* (you've left a place but not arrived yet).
            // rebuildTrips needs both endpoints, so the tail after the last visit is otherwise invisible
            // until you become stationary.
            buildOngoingTrip(loadStart, loadEnd, dayStart, dayEnd, latest)
            merger.merge(loadStart, loadEnd)

            // Deleting/rebuilding unconfirmed visits and merging can leave a surviving confirmed trip
            // pointing at a visit id that no longer exists. Sweep the whole table (this also cleans
            // orphans inherited from older data) so the trip graph stays reconstructable from a backup.
            tripDao.detachDanglingVisits()
        }

        // Let matched places drift toward where the user actually goes. recordVisitToPlace weights
        // confirmed visits 4x and decays old ones, so auto-detected visits nudge the center/radius
        // while confirmations anchor it. Fixed places are skipped inside recordVisitToPlace.
        visitDao.overlapping(loadStart, loadEnd).mapNotNull { it.placeId }.distinct()
            .forEach { placeRepository.recordVisitToPlace(it) }

        return insertedCount
    }

    /** A candidate plus everything phase 2 needs to materialize it without further network I/O. */
    private data class ResolvedCandidate(
        val candidate: VisitCandidate,
        val match: PlaceMatch?,
        val initialGeom: StayGeometry,
        val usable: List<LocationSampleEntity>,
    )

    /** Phase-1 half of materialization: derive the candidate's initial geometry from the in-memory
     *  samples and resolve its place match (the network call). Returns null when the candidate has
     *  no usable samples at all. */
    private suspend fun resolveCandidate(
        candidate: VisitCandidate,
        samples: List<LocationSampleEntity>,
    ): ResolvedCandidate? {
        val span = samples.inRange(candidate.startMs, candidate.endMs + 1)
        val usable = span.filter { it.includedInComputation }.ifEmpty { span }
        if (usable.isEmpty()) return null

        val initialGeom = VisitGeometry.compute(
            usable,
            candidate.centroidLatitude,
            candidate.centroidLongitude,
        )
        val match = runCatching {
            matchPlace(initialGeom.latitude, initialGeom.longitude)
        }.onFailure {
            log("place match failed; keeping visit unmatched")
        }.getOrNull()
        return ResolvedCandidate(candidate, match, initialGeom, usable)
    }

    /** Phase-2 half of materialization: persist a resolved candidate using its pre-resolved match —
     *  runs inside the write transaction, so only local DB work happens here. */
    private suspend fun materializeVisit(
        resolved: ResolvedCandidate,
        latest: LocationSampleEntity?,
    ): Long? {
        val (candidate, match, initialGeom, usable) = resolved

        if (match is PlaceMatch.Local) {
            locationRepository.excludeDriftOutside(
                startMs = candidate.startMs,
                endMs = candidate.endMs,
                centerLat = match.place.latitude,
                centerLon = match.place.longitude,
                radiusMeters = match.place.radiusMeters,
            )
        }

        val clean = sampleDao.rangeForComputation(candidate.startMs, candidate.endMs + 1)
            .ifEmpty { usable }
        val fallbackLat = (match as? PlaceMatch.Local)?.place?.latitude ?: initialGeom.latitude
        val fallbackLon = (match as? PlaceMatch.Local)?.place?.longitude ?: initialGeom.longitude
        val geom = VisitGeometry.compute(clean, fallbackLat, fallbackLon)
        val isOngoing = latest != null &&
                latest.timestampMs in candidate.startMs..candidate.endMs &&
                latest.devicePhysicalState == DevicePhysicalState.STATIONARY

        return visitDao.insert(
            applyMatch(
                VisitEntity(
                    placeId = null,
                    candidateName = null,
                    candidateGooglePlaceId = null,
                    candidateLatitude = null,
                    candidateLongitude = null,
                    startMs = candidate.startMs,
                    endMs = candidate.endMs,
                    dayEpoch = candidate.dayEpoch,
                    centroidLatitude = geom.latitude,
                    centroidLongitude = geom.longitude,
                    radiusMeters = geom.radiusMeters,
                    sampleCount = clean.size,
                    reliability = geom.reliability.toFloat(),
                    confirmed = false,
                    confidence = 0f,
                    isOngoing = isOngoing,
                ),
                match,
            )
        )
    }

    /**
     * Keep the *single* current confirmed visit's end time tracking the present while the device is
     * still stationary near it, and finalize it once the user moves on. **At most one visit may be
     * ongoing**, so any other confirmed visit still flagged `isOngoing` (stale flags from confirming
     * while ongoing) is finalized here — otherwise every one of them would be extended to the same
     * `now`, producing duplicate end times. Confirmed visits are otherwise preserved by maintenance.
     */
    private suspend fun extendConfirmedOngoingVisits(
        confirmedVisits: List<VisitEntity>,
        latest: LocationSampleEntity?,
        now: Long,
    ) {
        val ongoing = confirmedVisits.filter { it.isOngoing }
        if (ongoing.isEmpty()) return
        // Only the most recent stay can be ongoing; finalize the rest so they don't all get extended
        // to the same `now` (which would produce duplicate end times).
        val current = ongoing.maxByOrNull { it.startMs }!!
        ongoing.asSequence().filter { it.id != current.id }
            .forEach { visitDao.update(it.copy(isOngoing = false)) }

        if (latest == null) return
        val nearby = Geo.distanceMeters(
            current.centroidLatitude, current.centroidLongitude, latest.latitude, latest.longitude,
        ) <= maxOf(current.radiusMeters, Constants.STATIONARY_RADIUS_METERS)
        val stillThere = latest.timestampMs >= current.startMs && latest.includedInComputation &&
                latest.devicePhysicalState == DevicePhysicalState.STATIONARY &&
                (latest.speed ?: 0f) <= 0.8f && nearby
        // Evidence-gap policy: beyond MAX_EVIDENCE_GAP_MS without a sample, presence is no longer
        // assumed — a recording outage must not keep inflating the stay to `now`.
        val freshEvidence = now - latest.timestampMs <= Constants.MAX_EVIDENCE_GAP_MS
        if (stillThere && freshEvidence) {
            // Recompute the visit's own center/radius from its (now longer) sample span so the
            // visit circle stays accurate as the stay grows — this also feeds the place's
            // recency-weighted radius/center.
            val span = sampleDao.rangeForComputation(current.startMs, now + 1)
            val geom = if (span.size >= 2) {
                VisitGeometry.compute(span, current.centroidLatitude, current.centroidLongitude)
            } else {
                null
            }
            visitDao.update(
                current.copy(
                    endMs = maxOf(current.endMs, now),
                    centroidLatitude = geom?.latitude ?: current.centroidLatitude,
                    centroidLongitude = geom?.longitude ?: current.centroidLongitude,
                    radiusMeters = geom?.radiusMeters ?: current.radiusMeters,
                    sampleCount = if (geom != null) span.size else current.sampleCount,
                    reliability = geom?.reliability?.toFloat() ?: current.reliability,
                ),
            )
        } else if (stillThere) {
            // No departure evidence, but the trail is stale: close the stay at the last recorded
            // sample instead of fabricating presence. Fresh post-gap samples form a new stay (the
            // detector splits clusters across such a gap for the same reason).
            visitDao.update(
                current.copy(endMs = maxOf(current.endMs, latest.timestampMs), isOngoing = false),
            )
        } else if (latest.timestampMs > current.endMs) {
            visitDao.update(current.copy(isOngoing = false))
        }
    }

    private fun ongoingStationaryCandidate(
        samples: List<LocationSampleEntity>,
        dayStart: Long,
        dayEnd: Long,
        latest: LocationSampleEntity?,
    ): VisitCandidate? {
        val latestInDay =
            samples.lastOrNull { it.timestampMs >= dayStart && it.timestampMs < dayEnd }
        if (latest == null || latestInDay?.id != latest.id) return null
        if (!latest.includedInComputation || latest.devicePhysicalState != DevicePhysicalState.STATIONARY) return null
        val speed = latest.speed ?: 0f
        if (speed > 0.6f) return null
        val trustedStationary = latest.devicePhysicalStateConfidence >= 0.45f ||
                latest.arActivity?.uppercase() == "STILL"
        if (!trustedStationary) return null

        val tail = ArrayDeque<LocationSampleEntity>()
        for (sample in samples.asReversed()) {
            if (sample.timestampMs < dayStart || sample.timestampMs >= dayEnd) continue
            if (!sample.includedInComputation || sample.devicePhysicalState != DevicePhysicalState.STATIONARY) break
            if ((sample.speed ?: 0f) > 0.8f) break
            if ((sample.accuracy ?: Constants.SAMPLE_ACCURACY_GATE_METERS) >
                Constants.SAMPLE_ACCURACY_GATE_METERS
            ) {
                break
            }
            tail.addFirst(sample)
        }
        if (tail.isEmpty()) tail.add(latest)
        val good = tail.toList()
        val geom = VisitGeometry.compute(good, latest.latitude, latest.longitude)
        // Extend the ongoing stay to `now` only while the evidence is fresh; once the latest sample
        // is older than the gap cap, presence is no longer assumed and the stay closes at it.
        val nowMs = now()
        val endMs =
            if (nowMs - latest.timestampMs <= Constants.MAX_EVIDENCE_GAP_MS) {
                maxOf(good.last().timestampMs, nowMs)
            } else {
                good.last().timestampMs
            }
        return VisitCandidate(
            startMs = good.first().timestampMs,
            endMs = endMs,
            centroidLatitude = geom.latitude,
            centroidLongitude = geom.longitude,
            sampleCount = good.size,
        )
    }

    /**
     * Materialize the in-progress trip: when the latest fix shows the user is moving, segment the
     * movement from the most recent visit's end up to now and persist it with no destination
     * (`toVisitId = null`). Rebuilt each pass so it extends as movement continues; once the user
     * arrives and a destination visit forms, [rebuildTrips] replaces it with the bounded trip.
     */
    private suspend fun buildOngoingTrip(
        loadStart: Long,
        loadEnd: Long,
        dayStart: Long,
        dayEnd: Long,
        latest: LocationSampleEntity?,
    ) {
        if (latest == null || latest.devicePhysicalState == DevicePhysicalState.STATIONARY) return
        // Like ongoingStationaryCandidate: the in-progress trip only belongs to the day the latest
        // sample falls in. Rebuilding a *past* day while currently moving must not segment days of
        // samples into a bogus trip spanning from that day's last visit to now.
        if (latest.timestampMs < dayStart || latest.timestampMs >= dayEnd) return
        val visits = visitDao.overlapping(loadStart, loadEnd).sortedBy { it.startMs }
        val origin = visits.lastOrNull { it.endMs <= latest.timestampMs } ?: return
        val confirmedTrips =
            tripDao.confirmedOverlapping(loadStart, loadEnd).map { it.startMs to it.endMs }
        fillMovement(
            origin.endMs,
            latest.timestampMs,
            confirmedTrips,
            dayStart,
            dayEnd,
            origin.id,
            null
        )
    }

    /**
     * Rebuild the derived trips between stays. Each inter-stay gap is filled with the movement it
     * actually contains, **working around any confirmed trips that already cover part of it** — the
     * uncovered sub-intervals are segmented (so a multi-modal journey becomes several trips) and the
     * confirmed runs are left in place. A confirmed stub in a gap no longer suppresses the rest of
     * the journey, which is what made hand-edited days silently lose their moving trips.
     */
    private suspend fun rebuildTrips(loadStart: Long, loadEnd: Long, dayStart: Long, dayEnd: Long) {
        val visits = visitDao.overlapping(loadStart, loadEnd).sortedBy { it.startMs }
        if (visits.size < 2) return
        val confirmedTrips =
            tripDao.confirmedOverlapping(loadStart, loadEnd).map { it.startMs to it.endMs }
        for (i in 0 until visits.lastIndex) {
            val from = visits[i]
            val to = visits[i + 1]
            fillMovement(from.endMs, to.startMs, confirmedTrips, dayStart, dayEnd, from.id, to.id)
        }
    }

    /**
     * Segment and persist the movement in [gapStart, gapEnd) that is not already covered by a
     * confirmed trip, restricted to the sub-intervals that overlap [dayStart, dayEnd) (so this day's
     * rebuild only touches its own region). Splits the gap around the confirmed runs and lets
     * [segmentTrips] break each remaining piece into its own single-mode trips — so a multi-modal
     * journey (walk -> bus -> walk), or a stretch left bare beside a hand-confirmed stub, is rebuilt in
     * full instead of being skipped wholesale.
     */
    private suspend fun fillMovement(
        gapStart: Long,
        gapEnd: Long,
        confirmedTripRanges: List<Pair<Long, Long>>,
        dayStart: Long,
        dayEnd: Long,
        fromVisitId: Long,
        toVisitId: Long?,
    ) {
        if (gapEnd <= gapStart) return
        for ((subStart, subEnd) in subtractRanges(gapStart, gapEnd, confirmedTripRanges)) {
            if (subEnd <= subStart) continue
            if (subEnd <= dayStart || subStart >= dayEnd) continue // only the part touching this day
            val movingSamples = sampleDao.rangeForComputation(subStart, subEnd)
            val runs = segmentTrips(movingSamples)
            if (runs.isNotEmpty()) recordingRepository.saveTrips(fromVisitId, toVisitId, runs)
        }
    }

    private fun applyMatch(visit: VisitEntity, match: PlaceMatch?): VisitEntity = when (match) {
        is PlaceMatch.Local -> visit.copy(
            placeId = match.place.id,
            candidateName = match.place.name,
            confidence = match.confidence,
            // Maintenance can be highly confident, but user/editor confirmations are the only
            // ground truth rows that should survive future rebuilds unchanged.
            confirmed = false,
        )

        is PlaceMatch.Candidate -> visit.copy(
            candidateName = match.candidate.name,
            candidateGooglePlaceId = match.candidate.googlePlaceId,
            candidateLatitude = match.candidate.latitude,
            candidateLongitude = match.candidate.longitude,
            confidence = match.confidence,
            confirmed = false,
        )

        PlaceMatch.None, null -> visit
    }

    private fun VisitCandidate.overlaps(startMs: Long, endMs: Long): Boolean =
        this.startMs < endMs && this.endMs > startMs

    private fun VisitEntity.overlaps(startMs: Long, endMs: Long): Boolean =
        this.startMs < endMs && this.endMs > startMs

    private fun List<LocationSampleEntity>.inRange(
        startMs: Long,
        endMs: Long
    ): List<LocationSampleEntity> =
        filter { it.timestampMs >= startMs && it.timestampMs < endMs }
}

/** [baseStart, baseEnd) minus every block in [blocks], as an ordered list of leftover gaps.
 *  Top-level so the range arithmetic is directly table-testable. */
internal fun subtractRanges(
    baseStart: Long,
    baseEnd: Long,
    blocks: List<Pair<Long, Long>>,
): List<Pair<Long, Long>> {
    val overlapping = blocks
        .filter { it.second > baseStart && it.first < baseEnd }
        .sortedBy { it.first }
    if (overlapping.isEmpty()) return listOf(baseStart to baseEnd)
    val out = ArrayList<Pair<Long, Long>>()
    var cursor = baseStart
    for ((blockStart, blockEnd) in overlapping) {
        if (blockStart > cursor) out.add(cursor to minOf(blockStart, baseEnd))
        cursor = maxOf(cursor, blockEnd)
        if (cursor >= baseEnd) break
    }
    if (cursor < baseEnd) out.add(cursor to baseEnd)
    return out.filter { it.second > it.first }
}
