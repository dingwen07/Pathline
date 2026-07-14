package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceStatsRow
import net.extrawdw.apps.locationhistory.data.db.PlaceVisitCount
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity

// In-memory Visit/Trip/Place DAO fakes, the timeline companions of [AnnotationFakes]. They mirror
// the SQL semantics the gate/merger code relies on (overlap-window predicates, confirmed filters,
// LIKE-with-escape patterns) so [net.extrawdw.apps.locationhistory.api.ApiGate] and
// [TimelineMerger] are table-testable without a device.

/** Mirror of `LIKE ? ESCAPE '\'` for a `%text%` pattern: unescape, then substring match. */
internal fun likeMatches(pattern: String, value: String?): Boolean {
    if (value == null) return false
    val needle = pattern.removePrefix("%").removeSuffix("%")
        .replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\")
    return value.contains(needle, ignoreCase = true)
}

internal class FakeVisitDao : VisitDao {
    val visits = mutableListOf<VisitEntity>()
    private var nextId = 1L

    fun seed(vararg rows: VisitEntity) {
        rows.forEach { visits.add(if (it.id == 0L) it.copy(id = nextId++) else it.also { r -> nextId = maxOf(nextId, r.id + 1) }) }
    }

    override suspend fun insert(visit: VisitEntity): Long {
        val row = visit.copy(id = if (visit.id == 0L) nextId++ else visit.id)
        nextId = maxOf(nextId, row.id + 1)
        visits.add(row)
        return row.id
    }

    override suspend fun update(visit: VisitEntity) {
        visits.replaceAll { if (it.id == visit.id) visit else it }
    }

    override fun observeOverlapping(startMs: Long, endMs: Long): Flow<List<VisitEntity>> =
        flowOf(visits.filter { it.startMs < endMs && it.endMs > startMs }.sortedBy { it.startMs })

    override suspend fun byId(id: Long): VisitEntity? = visits.firstOrNull { it.id == id }

    override suspend fun mostRecent(): VisitEntity? = visits.maxByOrNull { it.startMs }

    override suspend fun ongoing(): VisitEntity? =
        visits.filter { it.isOngoing }.maxByOrNull { it.startMs }

    override fun observeUnconfirmed(): Flow<List<VisitEntity>> =
        flowOf(visits.filter { !it.confirmed }.sortedByDescending { it.startMs })

    override fun observeForPlace(placeId: Long): Flow<List<VisitEntity>> =
        flowOf(visits.filter { it.placeId == placeId }.sortedByDescending { it.startMs })

    override suspend fun countForPlace(placeId: Long): Int =
        visits.count { it.placeId == placeId }

    override suspend fun listForPlace(placeId: Long): List<VisitEntity> =
        visits.filter { it.placeId == placeId }

    override suspend fun forPlaceOverlapping(placeId: Long, startMs: Long, endMs: Long): List<VisitEntity> =
        visits.filter { it.placeId == placeId && it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    override fun observePlaceVisitCounts(): Flow<List<PlaceVisitCount>> =
        flowOf(
            visits.filter { it.placeId != null }.groupBy { it.placeId!! }
                .map { (placeId, rows) -> PlaceVisitCount(placeId, rows.size) },
        )

    override suspend fun placeStatsOverlapping(startMs: Long, endMs: Long): List<PlaceStatsRow> =
        visits
            .filter { it.confirmed && it.placeId != null && it.startMs < endMs && it.endMs > startMs }
            .groupBy { it.placeId!! }
            .map { (placeId, rows) ->
                PlaceStatsRow(
                    placeId = placeId,
                    visitCount = rows.size,
                    totalDurationMs = rows.sumOf { it.endMs - it.startMs },
                    firstVisitMs = rows.minOf { it.startMs },
                    lastVisitMs = rows.maxOf { it.endMs },
                )
            }
            .sortedWith(
                compareByDescending<PlaceStatsRow> { it.visitCount }
                    .thenByDescending { it.totalDurationMs },
            )

    override suspend fun byDay(dayEpoch: Long): List<VisitEntity> =
        visits.filter { it.dayEpoch == dayEpoch }.sortedBy { it.startMs }

    override suspend fun overlapping(startMs: Long, endMs: Long): List<VisitEntity> =
        visits.filter { it.startMs < endMs && it.endMs > startMs }.sortedBy { it.startMs }

    override suspend fun overlappingNewest(startMs: Long, endMs: Long, limit: Int): List<VisitEntity> =
        visits.filter { it.startMs < endMs && it.endMs > startMs }
            .sortedByDescending { it.startMs }.take(limit)

    override suspend fun confirmedOverlapping(startMs: Long, endMs: Long): List<VisitEntity> =
        visits.filter { it.confirmed && it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    override suspend fun confirmedIdsAmong(ids: List<Long>, minEndMs: Long): List<Long> =
        visits.filter { it.id in ids.toSet() && it.confirmed && it.endMs > minEndMs }.map { it.id }

    override suspend fun byIdsOverlapping(ids: List<Long>, startMs: Long, endMs: Long): List<VisitEntity> =
        visits.filter { it.id in ids.toSet() && it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    override suspend fun forPlacesOverlapping(
        placeIds: List<Long>,
        startMs: Long,
        endMs: Long,
    ): List<VisitEntity> =
        visits.filter { it.placeId in placeIds.toSet() && it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    override suspend fun candidateNameLikeOverlapping(
        pattern: String,
        startMs: Long,
        endMs: Long,
    ): List<VisitEntity> =
        visits.filter {
            it.candidateName != null && likeMatches(pattern, it.candidateName) &&
                it.startMs < endMs && it.endMs > startMs
        }.sortedBy { it.startMs }

    override suspend fun minUnconfirmedStartOverlapping(startMs: Long, endMs: Long): Long? =
        visits.filter { !it.confirmed && it.startMs < endMs && it.endMs > startMs }
            .minOfOrNull { it.startMs }

    override suspend fun maxUnconfirmedEndOverlapping(startMs: Long, endMs: Long): Long? =
        visits.filter { !it.confirmed && it.startMs < endMs && it.endMs > startMs }
            .maxOfOrNull { it.endMs }

    override suspend fun deleteUnconfirmedOverlapping(startMs: Long, endMs: Long) {
        visits.removeAll { !it.confirmed && it.startMs < endMs && it.endMs > startMs }
    }

    override suspend fun delete(id: Long) {
        visits.removeAll { it.id == id }
    }

    override suspend fun count(): Int = visits.size
}

internal class FakeTripDao(private val visitDao: FakeVisitDao? = null) : TripDao {
    val trips = mutableListOf<TripEntity>()
    private var nextId = 1L

    fun seed(vararg rows: TripEntity) {
        rows.forEach { trips.add(if (it.id == 0L) it.copy(id = nextId++) else it.also { r -> nextId = maxOf(nextId, r.id + 1) }) }
    }

    override suspend fun insert(trip: TripEntity): Long {
        val row = trip.copy(id = if (trip.id == 0L) nextId++ else trip.id)
        nextId = maxOf(nextId, row.id + 1)
        trips.add(row)
        return row.id
    }

    override suspend fun update(trip: TripEntity) {
        trips.replaceAll { if (it.id == trip.id) trip else it }
    }

    override fun observeOverlapping(startMs: Long, endMs: Long): Flow<List<TripEntity>> =
        flowOf(trips.filter { it.startMs < endMs && it.endMs > startMs }.sortedBy { it.startMs })

    override suspend fun byId(id: Long): TripEntity? = trips.firstOrNull { it.id == id }

    override suspend fun byDay(dayEpoch: Long): List<TripEntity> =
        trips.filter { it.dayEpoch == dayEpoch }.sortedBy { it.startMs }

    override suspend fun overlapping(startMs: Long, endMs: Long): List<TripEntity> =
        trips.filter { it.startMs < endMs && it.endMs > startMs }.sortedBy { it.startMs }

    override suspend fun overlappingNewest(startMs: Long, endMs: Long, limit: Int): List<TripEntity> =
        trips.filter { it.startMs < endMs && it.endMs > startMs }
            .sortedByDescending { it.startMs }.take(limit)

    override suspend fun confirmedOverlapping(startMs: Long, endMs: Long): List<TripEntity> =
        trips.filter { it.confirmed && it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    override suspend fun confirmedIdsAmong(ids: List<Long>, minEndMs: Long): List<Long> =
        trips.filter { it.id in ids.toSet() && it.confirmed && it.endMs > minEndMs }.map { it.id }

    override suspend fun byIdsOverlapping(ids: List<Long>, startMs: Long, endMs: Long): List<TripEntity> =
        trips.filter { it.id in ids.toSet() && it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    private fun visitIdsWhere(predicate: (VisitEntity) -> Boolean): Set<Long> =
        visitDao?.visits?.filter(predicate)?.map { it.id }?.toSet() ?: emptySet()

    override suspend fun forEndpointPlacesOverlapping(
        placeIds: List<Long>,
        startMs: Long,
        endMs: Long,
    ): List<TripEntity> {
        val endpointIds = visitIdsWhere { it.placeId in placeIds.toSet() }
        return trips.filter {
            (it.fromVisitId in endpointIds || it.toVisitId in endpointIds) &&
                it.startMs < endMs && it.endMs > startMs
        }.sortedBy { it.startMs }
    }

    override suspend fun forEndpointCandidateNamesOverlapping(
        pattern: String,
        startMs: Long,
        endMs: Long,
    ): List<TripEntity> {
        val endpointIds =
            visitIdsWhere { it.candidateName != null && likeMatches(pattern, it.candidateName) }
        return trips.filter {
            (it.fromVisitId in endpointIds || it.toVisitId in endpointIds) &&
                it.startMs < endMs && it.endMs > startMs
        }.sortedBy { it.startMs }
    }

    override suspend fun minUnconfirmedStartOverlapping(startMs: Long, endMs: Long): Long? =
        trips.filter { !it.confirmed && it.startMs < endMs && it.endMs > startMs }
            .minOfOrNull { it.startMs }

    override suspend fun maxUnconfirmedEndOverlapping(startMs: Long, endMs: Long): Long? =
        trips.filter { !it.confirmed && it.startMs < endMs && it.endMs > startMs }
            .maxOfOrNull { it.endMs }

    override suspend fun deleteUnconfirmedOverlapping(startMs: Long, endMs: Long) {
        trips.removeAll { !it.confirmed && it.startMs < endMs && it.endMs > startMs }
    }

    override suspend fun deleteTrip(id: Long) {
        trips.removeAll { it.id == id }
    }

    override suspend fun detachFromVisit(visitId: Long) {
        trips.replaceAll { if (it.fromVisitId == visitId) it.copy(fromVisitId = null) else it }
    }

    override suspend fun detachToVisit(visitId: Long) {
        trips.replaceAll { if (it.toVisitId == visitId) it.copy(toVisitId = null) else it }
    }

    override suspend fun detachDanglingFromVisits() {
        val existing = visitDao?.visits?.map { it.id }?.toSet() ?: emptySet()
        trips.replaceAll {
            if (it.fromVisitId != null && it.fromVisitId !in existing) it.copy(fromVisitId = null) else it
        }
    }

    override suspend fun detachDanglingToVisits() {
        val existing = visitDao?.visits?.map { it.id }?.toSet() ?: emptySet()
        trips.replaceAll {
            if (it.toVisitId != null && it.toVisitId !in existing) it.copy(toVisitId = null) else it
        }
    }

    override suspend fun count(): Int = trips.size
}

internal class FakeLocationSampleDao : LocationSampleDao {
    val samples = mutableListOf<LocationSampleEntity>()
    private var nextId = 1L

    override suspend fun insert(sample: LocationSampleEntity): Long {
        val row = sample.copy(id = if (sample.id == 0L) nextId++ else sample.id)
        nextId = maxOf(nextId, row.id + 1)
        samples.add(row)
        return row.id
    }

    override suspend fun insertAll(samples: List<LocationSampleEntity>): List<Long> =
        samples.map { insert(it) }

    override fun observeByDay(dayEpoch: Long): Flow<List<LocationSampleEntity>> =
        flowOf(samples.filter { it.dayEpoch == dayEpoch }.sortedBy { it.timestampMs })

    override suspend fun rangeForComputation(startMs: Long, endMs: Long): List<LocationSampleEntity> =
        samples.filter {
            it.timestampMs in startMs until endMs && it.includedInComputation
        }.sortedBy { it.timestampMs }

    override suspend fun range(startMs: Long, endMs: Long): List<LocationSampleEntity> =
        samples.filter { it.timestampMs in startMs until endMs }.sortedBy { it.timestampMs }

    override suspend fun rangeNewest(startMs: Long, endMs: Long, limit: Int): List<LocationSampleEntity> =
        samples.filter { it.timestampMs in startMs until endMs }
            .sortedByDescending { it.timestampMs }.take(limit)

    override suspend fun latest(limit: Int): List<LocationSampleEntity> =
        samples.sortedByDescending { it.timestampMs }.take(limit)

    override suspend fun mostRecent(): LocationSampleEntity? =
        samples.maxByOrNull { it.timestampMs }

    override suspend fun markExcluded(id: Long, reason: String) {
        samples.replaceAll {
            if (it.id == id) it.copy(includedInComputation = false, exclusionReason = reason) else it
        }
    }

    override suspend fun markIncluded(id: Long) {
        samples.replaceAll {
            if (it.id == id) it.copy(includedInComputation = true, exclusionReason = null) else it
        }
    }

    override fun observeCount(): Flow<Long> = flowOf(samples.size.toLong())

    override suspend fun count(): Long = samples.size.toLong()

    override suspend fun excludedCount(): Long =
        samples.count { !it.includedInComputation }.toLong()

    override fun observeRecordedDays(): Flow<List<Long>> =
        flowOf(samples.map { it.dayEpoch }.distinct().sortedDescending())
}

internal class FakePlaceDao : PlaceDao {
    val places = mutableListOf<PlaceEntity>()
    private var nextId = 1L

    fun seed(vararg rows: PlaceEntity) {
        rows.forEach { places.add(if (it.id == 0L) it.copy(id = nextId++) else it.also { r -> nextId = maxOf(nextId, r.id + 1) }) }
    }

    override suspend fun insert(place: PlaceEntity): Long {
        val row = place.copy(id = if (place.id == 0L) nextId++ else place.id)
        nextId = maxOf(nextId, row.id + 1)
        places.add(row)
        return row.id
    }

    override suspend fun update(place: PlaceEntity) {
        places.replaceAll { if (it.id == place.id) place else it }
    }

    override fun observeAll(): Flow<List<PlaceEntity>> = flowOf(places.sortedBy { it.name })

    override suspend fun byId(id: Long): PlaceEntity? = places.firstOrNull { it.id == id }

    override suspend fun byIds(ids: List<Long>): List<PlaceEntity> =
        places.filter { it.id in ids.toSet() }

    override suspend fun all(): List<PlaceEntity> = places.sortedBy { it.name }

    override suspend fun unresolvedCoordinates(): List<PlaceEntity> =
        places.filter { it.coordinateState != net.extrawdw.apps.locationhistory.core.PlaceCoordinateState.WGS84_CANONICAL }

    override fun observeById(id: Long): Flow<PlaceEntity?> =
        flowOf(places.firstOrNull { it.id == id })

    override suspend fun inBoundingBox(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
    ): List<PlaceEntity> =
        places.filter { it.latitude in minLat..maxLat && it.longitude in minLon..maxLon }

    override suspend fun withRadiusAbove(radiusMeters: Double): List<PlaceEntity> =
        places.filter { it.radiusMeters > radiusMeters }

    override suspend fun updateCenterRadius(id: Long, lat: Double, lon: Double, radius: Double) {
        places.replaceAll {
            if (it.id == id) it.copy(latitude = lat, longitude = lon, radiusMeters = radius) else it
        }
    }

    override suspend fun deleteIfUnvisited(id: Long): Int {
        val before = places.size
        places.removeAll { it.id == id }
        return before - places.size
    }

    override suspend fun count(): Int = places.size
}
