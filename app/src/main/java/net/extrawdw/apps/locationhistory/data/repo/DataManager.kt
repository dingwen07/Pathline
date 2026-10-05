package net.extrawdw.apps.locationhistory.data.repo

import androidx.room3.withWriteTransaction
import androidx.room3.withReadTransaction
import androidx.room3.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.*
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.MemoryMap
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import net.extrawdw.apps.locationhistory.domain.VisitGeometry
import net.extrawdw.apps.locationhistory.domain.subtractRanges
import net.extrawdw.apps.locationhistory.domain.unionTimeRanges
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class EdgeActivityBehavior { CAP, DELETE_WHOLE }

data class DeletePlaceOptions(
    val deleteConnectedTrips: Boolean = true,
    val deleteVisitDays: Boolean = false,
)

data class DataDeletionSummary(
    val samples: Long,
    val visits: Int,
    val trips: Int,
    val places: Int,
    val cappedActivities: Int = 0,
    val notes: Int = 0,
    val memories: Int = 0,
    val tagLinks: Int = 0,
    val groupMemberships: Int = 0,
    val tags: Int = 0,
    val groups: Int = 0,
    val logFiles: Int? = null,
    val dataApiLogs: Int? = null,
)

/** All destructive recorded-data changes commit atomically, including raw samples and annotations. */
@Singleton
class DataManager @Inject constructor(
    private val db: AppDatabase,
    private val annotations: AnnotationStore,
    private val writeLock: TimelineWriteLock,
    private val operationLock: DataOperationLock,
) {
    private val dao get() = db.dataManagementDao()

    suspend fun deleteDateRange(
        firstDay: Long,
        lastDay: Long,
        edgeBehavior: EdgeActivityBehavior,
        deleteEmptyPlaces: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ) = mutate {
        purge(dateRanges(firstDay, lastDay, edgeBehavior, zone), deleteEmptyPlaces, zone)
    }

    private suspend fun dateRanges(
        firstDay: Long, lastDay: Long, edgeBehavior: EdgeActivityBehavior, zone: ZoneId,
    ): List<Pair<Long, Long>> {
        require(firstDay <= lastDay)
        val today = LocalDate.now(zone).toEpochDay()
        require(firstDay <= today) { "Cannot delete future days" }
        val start = LocalDate.ofEpochDay(firstDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.ofEpochDay(minOf(lastDay, today)).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ranges = mutableListOf(start to end)
        if (edgeBehavior == EdgeActivityBehavior.DELETE_WHOLE) {
            dao.visitsOverlapping(start, end).filter { overlaps(it.startMs, it.endMs, start, end) }
                .forEach { ranges += it.startMs to (it.endMs + 1) }
            dao.tripsOverlapping(start, end).filter { overlaps(it.startMs, it.endMs, start, end) }
                .forEach { ranges += it.startMs to (it.endMs + 1) }
        }
        return unionTimeRanges(ranges)
    }

    suspend fun deletePlace(
        placeId: Long,
        options: DeletePlaceOptions,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = mutate {
        if (db.placeDao().byId(placeId) == null) return@mutate false
        purge(placeRanges(placeId, options, zone), deleteEmptyPlaces = false, zone)
        check(db.placeDao().deleteIfUnvisited(placeId) == 1)
        dao.deletePlaceRepairs(placeId)
        db.backupDao().purgeDanglingAnnotationsAndTags()
        true
    }

    private suspend fun placeRanges(placeId: Long, options: DeletePlaceOptions, zone: ZoneId): List<Pair<Long, Long>> {
        val visits = db.visitDao().listForPlace(placeId)
        val ranges = visits.map { it.startMs to (it.endMs + 1) }.toMutableList()
        if (options.deleteConnectedTrips) {
            visits.flatMap { dao.tripsAdjacentToVisit(it.id, it.startMs, it.endMs) }.distinctBy { it.id }
                .forEach { ranges += it.startMs to (it.endMs + 1) }
        }
        if (options.deleteVisitDays) {
            for (visit in visits) {
                // A midnight end does not add the following day. A point visit still has one day.
                val first = TimeBuckets.dayEpoch(visit.startMs, zone)
                val last = TimeBuckets.dayEpoch(maxOf(visit.startMs, visit.endMs - 1), zone)
                ranges += TimeBuckets.dayRangeMillis(first, zone).first to
                    (TimeBuckets.dayRangeMillis(last, zone).last + 1)
            }
        }
        return unionTimeRanges(ranges)
    }

    suspend fun previewDateRange(first: Long, last: Long, edge: EdgeActivityBehavior, emptyPlaces: Boolean,
        zone: ZoneId = ZoneId.systemDefault()): DataDeletionSummary = readPreview {
        summarize(dateRanges(first, last, edge, zone), emptyPlaces)
    }

    suspend fun previewPlace(placeId: Long, options: DeletePlaceOptions,
        zone: ZoneId = ZoneId.systemDefault()): DataDeletionSummary = readPreview {
        check(db.placeDao().byId(placeId) != null) { "Place no longer exists" }
        summarize(placeRanges(placeId, options, zone), false, setOf(placeId))
    }

    suspend fun previewReset(): DataDeletionSummary = readPreview {
        withAnnotationCounts(DataDeletionSummary(db.locationSampleDao().count(), db.visitDao().count(),
            db.tripDao().count(), db.placeDao().count(), tags = db.backupDao().allTags().size,
            groups = db.backupDao().allConcepts().size), targets = null)
    }

    private suspend fun <T> readPreview(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        db.withReadTransaction { block() }
    }

    /** Uses the same ranges and fragment arithmetic as the write; counts are a read-only snapshot. */
    private suspend fun summarize(ranges: List<Pair<Long, Long>>, emptyPlaces: Boolean,
        explicitPlaces: Set<Long> = emptySet()): DataDeletionSummary {
        val visits = ranges.flatMap { dao.visitsOverlapping(it.first, it.second) }.distinctBy { it.id }
        val trips = ranges.flatMap { dao.tripsOverlapping(it.first, it.second) }.distinctBy { it.id }
        val removedVisits = visits.filter { retained(it.startMs, it.endMs, ranges).isEmpty() }
        val removedTrips = trips.filter { retained(it.startMs, it.endMs, ranges).isEmpty() }
        val capped = visits.count { isCapped(it.startMs, it.endMs, ranges) } +
            trips.count { isCapped(it.startMs, it.endMs, ranges) }
        val places = explicitPlaces + if (emptyPlaces) removedVisits.mapNotNull { it.placeId }.distinct().filter { id ->
            db.visitDao().countForPlace(id) == removedVisits.count { it.placeId == id }
        } else emptyList()
        var samples = 0L
        for ((start, end) in ranges) samples += dao.countSamples(start, end)
        val targets = (removedVisits.map { AnnotationTarget.VISIT to it.id } +
            removedTrips.map { AnnotationTarget.TRIP to it.id } + places.map { AnnotationTarget.PLACE to it }).toSet()
        return withAnnotationCounts(DataDeletionSummary(samples, removedVisits.size, removedTrips.size, places.size, capped), targets)
    }

    /** Null targets means reset; annotations belonging to retained fragments are never counted. */
    private suspend fun withAnnotationCounts(summary: DataDeletionSummary,
        targets: Set<Pair<AnnotationTarget, Long>>?): DataDeletionSummary {
        val backup = db.backupDao()
        val rows = backup.allAnnotations().filter { targets == null || (it.targetType to it.targetId) in targets }
        return summary.copy(
            notes = rows.count { it.kind == AnnotationKind.NOTE },
            memories = rows.filter { it.kind == AnnotationKind.MEMORY }.sumOf { MemoryMap.decode(it.content).size },
            tagLinks = backup.allEntityTags().count { targets == null || (it.targetType to it.targetId) in targets },
            groupMemberships = backup.allConceptMembers().count { targets == null || (it.targetType to it.targetId) in targets },
        )
    }

    private fun isCapped(start: Long, end: Long, ranges: List<Pair<Long, Long>>): Boolean =
        retained(start, end, ranges).let { it.isNotEmpty() && it != listOf(start to end) }

    /** Called after recording is stopped. A reset leaves no deletion history or backup bookkeeping. */
    suspend fun resetAllData() = mutate {
        db.backupDao().wipeForRestore()
        // FTS sync triggers clear their indexes along with their content tables. Reset identities
        // too; the reset coordinator clears external place grants before these IDs can be reused.
        db.useWriterConnection { connection ->
            connection.usePrepared("DELETE FROM sqlite_sequence") { it.step() }
        }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        operationLock.withLock {
            writeLock.withLock {
                annotations.withMemoryWriteLock { db.withWriteTransaction { block() } }
            }
        }
    }

    private suspend fun purge(
        requestedRanges: List<Pair<Long, Long>>,
        deleteEmptyPlaces: Boolean,
        zone: ZoneId,
    ) {
        // Remove all matching stored rows (even imported future-dated data). Only the durable
        // recording exclusion ends at the operation's instant, allowing new recording afterwards.
        val nowEnd = System.currentTimeMillis()
        val ranges = unionTimeRanges(requestedRanges)
        if (ranges.isEmpty()) return
        val visits = ranges.flatMap { dao.visitsOverlapping(it.first, it.second) }.distinctBy { it.id }
        val trips = ranges.flatMap { dao.tripsOverlapping(it.first, it.second) }.distinctBy { it.id }
        val affectedPlaces = visits.mapNotNull { it.placeId }.toSet()
        for ((start, end) in ranges) dao.deleteSamples(start, end)
        val allRanges = unionTimeRanges((dao.deletedRanges().map { it.startMs to it.endMs } + ranges)
            .map { it.first to minOf(it.second, nowEnd) })
        dao.clearDeletedRanges()
        dao.insertDeletedRanges(allRanges.map { DeletedTimeRangeEntity(it.first, it.second) })

        for (visit in visits) capVisit(visit, ranges, zone)
        for (trip in trips) {
            // Visit capping may have repaired this trip's endpoints since it was loaded.
            db.tripDao().byId(trip.id)?.let { capTrip(it, ranges, zone) }
        }
        db.tripDao().detachDanglingVisits()
        if (deleteEmptyPlaces) {
            for (id in affectedPlaces) {
                if (db.placeDao().deleteIfUnvisited(id) == 1) dao.deletePlaceRepairs(id)
            }
        }
        db.backupDao().purgeDanglingAnnotationsAndTags()
    }

    private suspend fun capVisit(visit: VisitEntity, deleted: List<Pair<Long, Long>>, zone: ZoneId) {
        val pieces = retained(visit.startMs, visit.endMs, deleted)
        if (pieces == listOf(visit.startMs to visit.endMs)) return
        val survivors = pieces.mapIndexed { index, (start, end) ->
            val samples = db.locationSampleDao().rangeForComputation(start, end + 1)
            val geometry = VisitGeometry.compute(samples, visit.centroidLatitude, visit.centroidLongitude)
            val row = visit.copy(
                id = if (index == 0) visit.id else 0,
                startMs = start, endMs = end, dayEpoch = TimeBuckets.dayEpoch(start, zone),
                centroidLatitude = geometry.latitude, centroidLongitude = geometry.longitude,
                radiusMeters = geometry.radiusMeters, sampleCount = samples.size,
                reliability = geometry.reliability.toFloat(), stopMerge = true,
                isOngoing = visit.isOngoing && end == visit.endMs,
            )
            if (index == 0) { db.visitDao().update(row); row }
            else {
                val id = db.visitDao().insert(row)
                copyAnnotations(AnnotationTarget.VISIT, visit.id, id)
                row.copy(id = id)
            }
        }
        if (survivors.isEmpty()) db.visitDao().delete(visit.id)
        // Endpoints belong to the retained side of a split visit. Never leave dangling identities.
        for (trip in dao.tripsTouchingVisit(visit.id)) {
            db.tripDao().update(trip.copy(
                fromVisitId = if (trip.fromVisitId == visit.id)
                    survivors.lastOrNull { it.endMs <= trip.startMs }?.id else trip.fromVisitId,
                toVisitId = if (trip.toVisitId == visit.id)
                    survivors.firstOrNull { it.startMs >= trip.endMs }?.id else trip.toVisitId,
                stopMerge = true,
            ))
        }
    }

    private suspend fun capTrip(trip: TripEntity, deleted: List<Pair<Long, Long>>, zone: ZoneId) {
        val pieces = retained(trip.startMs, trip.endMs, deleted)
        if (pieces == listOf(trip.startMs to trip.endMs)) return
        if (pieces.isEmpty()) { db.tripDao().deleteTrip(trip.id); return }
        pieces.forEachIndexed { index, (start, end) ->
            // Rebuild geometry only from kept fixes; an old polyline may contain deleted locations.
            val points = db.locationSampleDao().rangeForComputation(start, end + 1)
                .map { it.latitude to it.longitude }
            val row = trip.copy(
                id = if (index == 0) trip.id else 0,
                startMs = start, endMs = end, dayEpoch = TimeBuckets.dayEpoch(start, zone),
                fromVisitId = trip.fromVisitId.takeIf { start == trip.startMs },
                toVisitId = trip.toVisitId.takeIf { end == trip.endMs },
                encodedPolyline = Geo.encodePolyline(points), distanceMeters = Geo.pathLengthMeters(points),
                stopMerge = true,
            )
            if (index == 0) db.tripDao().update(row)
            else copyAnnotations(AnnotationTarget.TRIP, trip.id, db.tripDao().insert(row))
        }
    }

    private suspend fun copyAnnotations(target: AnnotationTarget, fromId: Long, toId: Long) {
        db.annotationDao().allForTarget(target, fromId).forEach {
            db.annotationDao().upsert(it.copy(id = 0, targetId = toId))
        }
        db.tagDao().linksFor(target, fromId).forEach { db.tagDao().link(it.copy(targetId = toId)) }
        db.conceptDao().membershipsFor(target, fromId).forEach {
            db.conceptDao().addMember(it.copy(targetId = toId))
        }
    }

    private fun retained(start: Long, end: Long, deleted: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (start == end) return if (deleted.any { start >= it.first && start < it.second }) emptyList()
            else listOf(start to end)
        return subtractRanges(start, end, deleted)
    }

    private fun overlaps(start: Long, end: Long, purgeStart: Long, purgeEnd: Long): Boolean =
        start < purgeEnd && (end > purgeStart || start == end && start >= purgeStart)
}
