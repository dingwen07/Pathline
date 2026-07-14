package net.extrawdw.apps.locationhistory.api

import android.database.Cursor
import android.database.MatrixCursor
import net.extrawdw.apps.locationhistory.data.db.ConceptEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptMemberEntity
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceStatsRow
import net.extrawdw.apps.locationhistory.data.db.TagEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.routes.TravelTimeEstimate
import net.extrawdw.apps.locationhistory.domain.MemoryEntry

/**
 * The data API's row mappers: entity lists in, [MatrixCursor] out, in the column shapes
 * [PathlineContract] documents. Pure-ish — no DAO access, no permission logic; the only request
 * state is the [Caller] the `*_by_me` columns are derived from. Extracted from [PathlineProvider]
 * (backlog #2) so the provider keeps routing/enforcement and this keeps the shapes.
 */
internal object ApiCursors {

    /** [PathlineContract.Visits] rows. [names] resolves place ids to saved-place names (a missing
     *  or null-named entry falls back to the visit's candidate name, like the inline original). */
    fun visits(visits: List<VisitEntity>, names: Map<Long, String?>): Cursor {
        val cursor = MatrixCursor(PathlineContract.Visits.COLUMNS, visits.size)
        for (v in visits) {
            val resolvedName = v.placeId?.let { names[it] } ?: v.candidateName
            cursor.addRow(
                arrayOf<Any?>(
                    v.id,
                    v.startMs,
                    v.endMs,
                    v.placeId,
                    resolvedName,
                    v.centroidLatitude,
                    v.centroidLongitude,
                    v.radiusMeters,
                    v.confidence,
                    if (v.confirmed) 1 else 0,
                    if (v.isOngoing) 1 else 0,
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.Trips] rows; the route column only when [includeRoute]. */
    fun trips(trips: List<TripEntity>, includeRoute: Boolean): Cursor {
        val cursor = MatrixCursor(PathlineContract.Trips.COLUMNS, trips.size)
        for (t in trips) {
            cursor.addRow(
                arrayOf<Any?>(
                    t.id,
                    t.startMs,
                    t.endMs,
                    t.mode.name,
                    t.modeConfidence,
                    t.distanceMeters,
                    if (includeRoute) t.encodedPolyline else null,
                    if (t.confirmed) 1 else 0,
                    t.fromVisitId,
                    t.toVisitId,
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.Samples] rows. */
    fun samples(samples: List<LocationSampleEntity>): Cursor {
        val cursor = MatrixCursor(PathlineContract.Samples.COLUMNS, samples.size)
        for (s in samples) {
            cursor.addRow(
                arrayOf<Any?>(
                    s.id,
                    s.timestampMs,
                    s.latitude,
                    s.longitude,
                    s.altitude,
                    s.accuracy,
                    s.bearing,
                    s.speed,
                    s.provider,
                    if (s.isMock) 1 else 0,
                    s.devicePhysicalState.name,
                    s.arActivity,
                    s.networkTransport?.name,
                    if (s.includedInComputation) 1 else 0,
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.Places] rows. [distances] populates DISTANCE_M in proximity mode
     *  (place id -> meters from the `near` point); null everywhere else. */
    fun places(places: List<PlaceEntity>, distances: Map<Long, Double>? = null): Cursor {
        val cursor = MatrixCursor(PathlineContract.Places.COLUMNS, places.size)
        for (p in places) {
            cursor.addRow(
                arrayOf<Any?>(
                    p.id,
                    p.name,
                    p.address,
                    p.category,
                    p.types,
                    p.source.name,
                    p.googlePlaceId,
                    p.latitude,
                    p.longitude,
                    p.coordinateState.name,
                    p.radiusMeters,
                    distances?.get(p.id),
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.TravelTimes] rows. Column order must match [PathlineContract.TravelTimes.COLUMNS]. */
    fun travelTimes(rows: List<TravelTimeEstimate>): Cursor {
        val t = PathlineContract.TravelTimes
        val cursor = MatrixCursor(t.COLUMNS, rows.size)
        for (r in rows) {
            cursor.addRow(
                arrayOf<Any?>(
                    r.routeIndex.toLong(),
                    r.routeIndex,
                    r.originPlaceId,
                    r.destinationPlaceId,
                    r.travelMode,
                    r.durationSeconds,
                    r.staticDurationSeconds,
                    r.distanceMeters,
                    r.routeDepartureTimeMs,
                    r.routeArrivalTimeMs,
                    r.firstTransitDepartureTimeMs,
                    r.lastTransitArrivalTimeMs,
                    r.transitModes,
                    r.stepTravelModes,
                    r.localizedDuration,
                    r.localizedDistance,
                    r.localizedFare,
                    "Google Maps",
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.Places.VisitHistory] rows — lean, no place name/address. */
    fun placeVisits(visits: List<VisitEntity>): Cursor {
        val cursor = MatrixCursor(PathlineContract.Places.VisitHistory.COLUMNS, visits.size)
        for (v in visits) {
            cursor.addRow(
                arrayOf<Any?>(
                    v.id,
                    v.startMs,
                    v.endMs,
                    v.placeId,
                    v.centroidLatitude,
                    v.centroidLongitude,
                    v.radiusMeters,
                    v.confidence,
                    if (v.confirmed) 1 else 0,
                    if (v.isOngoing) 1 else 0,
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.PlaceStats] rows. */
    fun placeStats(rows: List<PlaceStatsRow>): Cursor {
        val cursor = MatrixCursor(PathlineContract.PlaceStats.COLUMNS, rows.size)
        for (r in rows) {
            cursor.addRow(
                arrayOf<Any?>(
                    r.placeId, r.visitCount, r.totalDurationMs, r.firstVisitMs, r.lastVisitMs,
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.Tags] rows. [attachedBy] is the per-link writer map of a per-target
     *  listing (tagId -> link createdBy), or null on the global `tags` collection where
     *  ATTACHED_BY_ME is always null. */
    fun tags(
        caller: Caller,
        tags: List<TagEntity>,
        attachedBy: Map<Long, String?>? = null
    ): Cursor {
        val cursor = MatrixCursor(PathlineContract.Tags.COLUMNS, tags.size)
        for (t in tags) {
            cursor.addRow(
                arrayOf<Any?>(
                    t.id, t.displayName, t.canonicalName, t.createdAtMs,
                    caller.byMe(t.createdBy),
                    attachedBy?.let { caller.byMe(it[t.id]) },
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.Concepts] rows. [attachedBy] populates ATTACHED_BY_ME on per-target
     *  listings (conceptId -> membership createdBy); null elsewhere. [memberCounts] is the
     *  one-GROUP-BY projection of `ConceptDao.memberCounts` — a concept without a row counts 0. */
    fun concepts(
        caller: Caller,
        concepts: List<ConceptEntity>,
        memberCounts: Map<Long, Int>,
        attachedBy: Map<Long, String?>? = null,
    ): Cursor {
        val cursor = MatrixCursor(PathlineContract.Concepts.COLUMNS, concepts.size)
        for (c in concepts) {
            cursor.addRow(
                arrayOf<Any?>(
                    c.id, c.displayName, c.canonicalName, c.kind, c.description,
                    c.createdAtMs, c.updatedAtMs,
                    caller.byMe(c.createdBy), caller.byMe(c.updatedBy),
                    attachedBy?.let { caller.byMe(it[c.id]) },
                    memberCounts[c.id] ?: 0,
                    c.archivedAtMs,
                    c.archivedAtMs?.let { caller.byMe(c.archivedBy) },
                ),
            )
        }
        return cursor
    }

    /** [PathlineContract.Concepts.Members] rows. */
    fun conceptMembers(caller: Caller, members: List<ConceptMemberEntity>): Cursor {
        val cursor = MatrixCursor(PathlineContract.Concepts.Members.COLUMNS, members.size)
        for (m in members) {
            cursor.addRow(
                arrayOf<Any?>(
                    m.targetType.name.lowercase(),
                    m.targetId,
                    m.createdAtMs,
                    caller.byMe(m.createdBy),
                ),
            )
        }
        return cursor
    }

    /** The 0/1-row [PathlineContract.Annotations.Notes] cursor for one target's note. */
    fun note(
        caller: Caller,
        id: Long?,
        content: String?,
        updatedAtMs: Long?,
        updatedBy: String?
    ): Cursor {
        val cursor = MatrixCursor(PathlineContract.Annotations.Notes.COLUMNS, 1)
        if (id != null) {
            cursor.addRow(arrayOf<Any?>(id, content, updatedAtMs, caller.byMe(updatedBy)))
        }
        return cursor
    }

    /** [PathlineContract.Annotations.Memories] rows, one per key of the target's map. */
    fun memories(caller: Caller, entries: Map<String, MemoryEntry>): Cursor {
        val cursor = MatrixCursor(PathlineContract.Annotations.Memories.COLUMNS, entries.size)
        for ((k, e) in entries) {
            // Entries stored before per-entry stamps existed have no stamp; null rather than a
            // misleading fallback (the row's map-wide time moves with every write to the map).
            cursor.addRow(
                arrayOf<Any?>(
                    k,
                    e.value,
                    e.confidence,
                    e.source,
                    e.updatedAtMs,
                    caller.byMe(e.updatedBy),
                ),
            )
        }
        return cursor
    }

    /** The one-row [PathlineContract.Status] cursor. */
    fun status(accessEnabled: Boolean): Cursor {
        val cursor = MatrixCursor(PathlineContract.Status.COLUMNS, 1)
        cursor.addRow(arrayOf<Any?>(if (accessEnabled) 1 else 0, PathlineContract.API_VERSION))
        return cursor
    }
}
