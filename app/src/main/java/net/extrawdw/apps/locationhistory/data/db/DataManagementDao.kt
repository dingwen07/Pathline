package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DataManagementDao {
    @Query("SELECT * FROM deleted_time_ranges ORDER BY startMs")
    suspend fun deletedRanges(): List<DeletedTimeRangeEntity>

    @Insert
    suspend fun insertDeletedRanges(ranges: List<DeletedTimeRangeEntity>)

    @Query("DELETE FROM deleted_time_ranges")
    suspend fun clearDeletedRanges()

    @Query("DELETE FROM location_samples WHERE timestampMs >= :startMs AND timestampMs < :endMs")
    suspend fun deleteSamples(startMs: Long, endMs: Long)

    @Query("SELECT COUNT(*) FROM location_samples WHERE timestampMs >= :startMs AND timestampMs < :endMs")
    suspend fun countSamples(startMs: Long, endMs: Long): Long

    // Include point activities, which the ordinary timeline overlap queries intentionally omit.
    @Query("SELECT * FROM visits WHERE startMs < :endMs AND endMs >= :startMs ORDER BY startMs")
    suspend fun visitsOverlapping(startMs: Long, endMs: Long): List<VisitEntity>

    @Query("SELECT * FROM trips WHERE startMs < :endMs AND endMs >= :startMs ORDER BY startMs")
    suspend fun tripsOverlapping(startMs: Long, endMs: Long): List<TripEntity>

    // Include every transport leg in each direction; endpoint IDs may name only the nearest leg.
    @Query("SELECT * FROM trips WHERE " +
        "(endMs <= :startMs AND startMs >= COALESCE((SELECT MAX(endMs) FROM visits WHERE id != :visitId AND endMs <= :startMs), -9223372036854775808)) OR " +
        "(startMs >= :endMs AND endMs <= COALESCE((SELECT MIN(startMs) FROM visits WHERE id != :visitId AND startMs >= :endMs), 9223372036854775807))")
    suspend fun tripsAdjacentToVisit(visitId: Long, startMs: Long, endMs: Long): List<TripEntity>

    @Query("SELECT * FROM trips WHERE fromVisitId = :visitId OR toVisitId = :visitId")
    suspend fun tripsTouchingVisit(visitId: Long): List<TripEntity>

    @Query("DELETE FROM place_coordinate_repairs WHERE placeId = :placeId")
    suspend fun deletePlaceRepairs(placeId: Long)

    @Query("SELECT * FROM location_samples ORDER BY timestampMs DESC, id DESC LIMIT 1")
    fun observeLatestSample(): Flow<LocationSampleEntity?>
}
