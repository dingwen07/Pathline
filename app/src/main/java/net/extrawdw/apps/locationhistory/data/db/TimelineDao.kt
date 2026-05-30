package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** placeId -> visit count projection for [VisitDao.observePlaceVisitCounts]. */
data class PlaceVisitCount(val placeId: Long, val visits: Int)

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

    /** placeId -> number of visits, for the Places list. */
    @Query("SELECT placeId AS placeId, COUNT(*) AS visits FROM visits WHERE placeId IS NOT NULL GROUP BY placeId")
    fun observePlaceVisitCounts(): Flow<List<PlaceVisitCount>>

    @Query("SELECT * FROM visits WHERE dayEpoch = :dayEpoch ORDER BY startMs ASC")
    suspend fun byDay(dayEpoch: Long): List<VisitEntity>

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

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Insert
    suspend fun insertSegment(segment: TripSegmentEntity): Long

    @Update
    suspend fun updateSegment(segment: TripSegmentEntity)

    @Query("DELETE FROM trip_segments WHERE tripId = :tripId")
    suspend fun deleteSegmentsForTrip(tripId: Long)

    @Query("SELECT * FROM trip_segments WHERE tripId = :tripId ORDER BY startMs ASC")
    suspend fun segmentsForTrip(tripId: Long): List<TripSegmentEntity>

    @Query(
        "SELECT * FROM trip_segments WHERE tripId IN " +
            "(SELECT id FROM trips WHERE startMs < :endMs AND endMs > :startMs) ORDER BY startMs ASC"
    )
    fun observeSegmentsOverlapping(startMs: Long, endMs: Long): Flow<List<TripSegmentEntity>>

    @Query("SELECT * FROM trip_segments WHERE id = :id")
    suspend fun segmentById(id: Long): TripSegmentEntity?

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int
}
