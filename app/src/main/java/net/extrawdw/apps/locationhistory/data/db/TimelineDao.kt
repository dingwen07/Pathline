package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** placeId -> visit count projection for [VisitDao.observePlaceVisitCounts]. */
data class PlaceVisitCount(val placeId: Long, val visits: Int)

/** Per-place aggregate over confirmed visits in a window, for the data API's `place_stats`. */
data class PlaceStatsRow(
    val placeId: Long,
    val visitCount: Int,
    val totalDurationMs: Long,
    val firstVisitMs: Long,
    val lastVisitMs: Long,
)

@Dao
interface VisitDao {

    @Insert
    suspend fun insert(visit: VisitEntity): Long

    @Update
    suspend fun update(visit: VisitEntity)

    /** Visits that overlap [startMs, endMs) — includes stays that cross midnight into the day. */
    @Query("SELECT * FROM visits WHERE startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    fun observeOverlapping(startMs: Long, endMs: Long): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun byId(id: Long): VisitEntity?

    @Query("SELECT * FROM visits ORDER BY startMs DESC LIMIT 1")
    suspend fun mostRecent(): VisitEntity?

    @Query("SELECT * FROM visits WHERE isOngoing = 1 ORDER BY startMs DESC LIMIT 1")
    suspend fun ongoing(): VisitEntity?

    @Query("SELECT * FROM visits WHERE confirmed = 0 ORDER BY startMs DESC")
    fun observeUnconfirmed(): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE placeId = :placeId ORDER BY startMs DESC")
    fun observeForPlace(placeId: Long): Flow<List<VisitEntity>>

    @Query("SELECT COUNT(*) FROM visits WHERE placeId = :placeId")
    suspend fun countForPlace(placeId: Long): Int

    @Query("SELECT * FROM visits WHERE placeId = :placeId")
    suspend fun listForPlace(placeId: Long): List<VisitEntity>

    /** A place's visit history overlapping [startMs, endMs), oldest first — for the data API. */
    @Query("SELECT * FROM visits WHERE placeId = :placeId AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun forPlaceOverlapping(placeId: Long, startMs: Long, endMs: Long): List<VisitEntity>

    /** placeId -> number of visits, for the Places list. */
    @Query("SELECT placeId AS placeId, COUNT(*) AS visits FROM visits WHERE placeId IS NOT NULL GROUP BY placeId")
    fun observePlaceVisitCounts(): Flow<List<PlaceVisitCount>>

    /**
     * Per-place aggregates over **confirmed** visits overlapping [startMs, endMs) — the data API's
     * `place_stats`. Overlap semantics match the visits collection: an overlapping visit counts
     * whole (durations are full visit spans, not clipped to the window). Most-visited first.
     */
    @Query(
        "SELECT placeId AS placeId, COUNT(*) AS visitCount, " +
                "SUM(endMs - startMs) AS totalDurationMs, " +
                "MIN(startMs) AS firstVisitMs, MAX(endMs) AS lastVisitMs " +
                "FROM visits WHERE confirmed = 1 AND placeId IS NOT NULL " +
                "AND startMs < :endMs AND endMs > :startMs " +
                "GROUP BY placeId ORDER BY visitCount DESC, totalDurationMs DESC"
    )
    suspend fun placeStatsOverlapping(startMs: Long, endMs: Long): List<PlaceStatsRow>

    @Query("SELECT * FROM visits WHERE dayEpoch = :dayEpoch ORDER BY startMs ASC")
    suspend fun byDay(dayEpoch: Long): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun overlapping(startMs: Long, endMs: Long): List<VisitEntity>

    /** The newest [limit] overlapping visits, newest first — the data API's `limit=` cap pushed
     *  into SQL (callers re-ascend with `asReversed()`), mirroring [LocationSampleDao.rangeNewest]. */
    @Query("SELECT * FROM visits WHERE startMs < :endMs AND endMs > :startMs ORDER BY startMs DESC LIMIT :limit")
    suspend fun overlappingNewest(startMs: Long, endMs: Long, limit: Int): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE confirmed = 1 AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun confirmedOverlapping(startMs: Long, endMs: Long): List<VisitEntity>

    /** Of [ids], the ones naming a confirmed visit ending after [minEndMs] — the data API's
     *  visibility filter for annotation targets (clamped to 30 days without extended history). */
    @Query("SELECT id FROM visits WHERE id IN (:ids) AND confirmed = 1 AND endMs > :minEndMs")
    suspend fun confirmedIdsAmong(ids: List<Long>, minEndMs: Long): List<Long>

    @Query("SELECT * FROM visits WHERE id IN (:ids) AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun byIdsOverlapping(ids: List<Long>, startMs: Long, endMs: Long): List<VisitEntity>

    /** Visits attributed to any of [placeIds] in the window — the place-name leg of visit search. */
    @Query("SELECT * FROM visits WHERE placeId IN (:placeIds) AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun forPlacesOverlapping(
        placeIds: List<Long>,
        startMs: Long,
        endMs: Long
    ): List<VisitEntity>

    /** Candidate-name leg of visit search; saved-place names are handled by [forPlacesOverlapping]. */
    @Query(
        "SELECT * FROM visits WHERE candidateName IS NOT NULL " +
                "AND candidateName LIKE :pattern ESCAPE '\\' " +
                "AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC"
    )
    suspend fun candidateNameLikeOverlapping(
        pattern: String,
        startMs: Long,
        endMs: Long
    ): List<VisitEntity>

    /** Earliest start of an UNCONFIRMED visit overlapping [startMs, endMs). The rebuilder widens its
     *  sample window to cover the rows it is about to delete — the delete scope must never exceed
     *  the re-detection scope (see [net.extrawdw.apps.locationhistory.core.Constants.REBUILD_SCOPE_MARGIN_MS]). */
    @Query("SELECT MIN(startMs) FROM visits WHERE confirmed = 0 AND startMs < :endMs AND endMs > :startMs")
    suspend fun minUnconfirmedStartOverlapping(startMs: Long, endMs: Long): Long?

    /** Latest end of an UNCONFIRMED visit overlapping [startMs, endMs) — see [minUnconfirmedStartOverlapping]. */
    @Query("SELECT MAX(endMs) FROM visits WHERE confirmed = 0 AND startMs < :endMs AND endMs > :startMs")
    suspend fun maxUnconfirmedEndOverlapping(startMs: Long, endMs: Long): Long?

    @Query("DELETE FROM visits WHERE confirmed = 0 AND startMs < :endMs AND endMs > :startMs")
    suspend fun deleteUnconfirmedOverlapping(startMs: Long, endMs: Long)

    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM visits")
    suspend fun count(): Int
}

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    /** Trips that overlap [startMs, endMs) — includes trips that cross midnight into the day. */
    @Query("SELECT * FROM trips WHERE startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    fun observeOverlapping(startMs: Long, endMs: Long): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE dayEpoch = :dayEpoch ORDER BY startMs ASC")
    suspend fun byDay(dayEpoch: Long): List<TripEntity>

    @Query("SELECT * FROM trips WHERE startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun overlapping(startMs: Long, endMs: Long): List<TripEntity>

    /** The newest [limit] overlapping trips, newest first — see [VisitDao.overlappingNewest]. */
    @Query("SELECT * FROM trips WHERE startMs < :endMs AND endMs > :startMs ORDER BY startMs DESC LIMIT :limit")
    suspend fun overlappingNewest(startMs: Long, endMs: Long, limit: Int): List<TripEntity>

    @Query("SELECT * FROM trips WHERE confirmed = 1 AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun confirmedOverlapping(startMs: Long, endMs: Long): List<TripEntity>

    /** Of [ids], the ones naming a confirmed trip ending after [minEndMs] — see the visit twin. */
    @Query("SELECT id FROM trips WHERE id IN (:ids) AND confirmed = 1 AND endMs > :minEndMs")
    suspend fun confirmedIdsAmong(ids: List<Long>, minEndMs: Long): List<Long>

    @Query("SELECT * FROM trips WHERE id IN (:ids) AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC")
    suspend fun byIdsOverlapping(ids: List<Long>, startMs: Long, endMs: Long): List<TripEntity>

    /** Trips departing from or arriving at a visit attributed to any of [placeIds] in the window —
     *  the place-name leg of trip search. */
    @Query(
        "SELECT * FROM trips WHERE (" +
                "fromVisitId IN (SELECT id FROM visits WHERE placeId IN (:placeIds)) OR " +
                "toVisitId IN (SELECT id FROM visits WHERE placeId IN (:placeIds))) " +
                "AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC",
    )
    suspend fun forEndpointPlacesOverlapping(
        placeIds: List<Long>,
        startMs: Long,
        endMs: Long
    ): List<TripEntity>

    /** Candidate-name leg of trip endpoint search; saved-place names use [forEndpointPlacesOverlapping]. */
    @Query(
        "SELECT * FROM trips WHERE (" +
                "fromVisitId IN (SELECT id FROM visits WHERE candidateName IS NOT NULL " +
                "AND candidateName LIKE :pattern ESCAPE '\\') OR " +
                "toVisitId IN (SELECT id FROM visits WHERE candidateName IS NOT NULL " +
                "AND candidateName LIKE :pattern ESCAPE '\\')) " +
                "AND startMs < :endMs AND endMs > :startMs ORDER BY startMs ASC"
    )
    suspend fun forEndpointCandidateNamesOverlapping(
        pattern: String,
        startMs: Long,
        endMs: Long
    ): List<TripEntity>

    /** Earliest start of an UNCONFIRMED trip overlapping [startMs, endMs) — see the visit twin. */
    @Query("SELECT MIN(startMs) FROM trips WHERE confirmed = 0 AND startMs < :endMs AND endMs > :startMs")
    suspend fun minUnconfirmedStartOverlapping(startMs: Long, endMs: Long): Long?

    /** Latest end of an UNCONFIRMED trip overlapping [startMs, endMs) — see the visit twin. */
    @Query("SELECT MAX(endMs) FROM trips WHERE confirmed = 0 AND startMs < :endMs AND endMs > :startMs")
    suspend fun maxUnconfirmedEndOverlapping(startMs: Long, endMs: Long): Long?

    @Query("DELETE FROM trips WHERE confirmed = 0 AND startMs < :endMs AND endMs > :startMs")
    suspend fun deleteUnconfirmedOverlapping(startMs: Long, endMs: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    /**
     * Null out any trip endpoint that referenced [visitId]. [TripEntity.fromVisitId]/[TripEntity.toVisitId]
     * are journey-grouping hints with no FK or cascade, so when a visit is deleted (editor) or replaced
     * by a rebuild (maintenance reinserts unconfirmed visits with fresh ids), a surviving confirmed trip
     * would otherwise keep pointing at a row that no longer exists. Detach instead of dangle.
     */
    @Query("UPDATE trips SET fromVisitId = NULL WHERE fromVisitId = :visitId")
    suspend fun detachFromVisit(visitId: Long)

    @Query("UPDATE trips SET toVisitId = NULL WHERE toVisitId = :visitId")
    suspend fun detachToVisit(visitId: Long)

    /** Detach both endpoints of every trip that pointed at [visitId]. Call right after deleting a visit. */
    suspend fun detachVisit(visitId: Long) {
        detachFromVisit(visitId)
        detachToVisit(visitId)
    }

    @Query("UPDATE trips SET fromVisitId = NULL WHERE fromVisitId IS NOT NULL AND fromVisitId NOT IN (SELECT id FROM visits)")
    suspend fun detachDanglingFromVisits()

    @Query("UPDATE trips SET toVisitId = NULL WHERE toVisitId IS NOT NULL AND toVisitId NOT IN (SELECT id FROM visits)")
    suspend fun detachDanglingToVisits()

    /**
     * Whole-table sweep: detach every trip endpoint whose referenced visit no longer exists. Used after
     * a rebuild/merge (where deleted visits aren't enumerated one by one) and to clean orphans inherited
     * from an older app version or imported data.
     */
    suspend fun detachDanglingVisits() {
        detachDanglingFromVisits()
        detachDanglingToVisits()
    }

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int
}
