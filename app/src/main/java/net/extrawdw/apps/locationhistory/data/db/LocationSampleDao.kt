package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationSampleDao {

    @Insert
    suspend fun insert(sample: LocationSampleEntity): Long

    @Insert
    suspend fun insertAll(samples: List<LocationSampleEntity>): List<Long>

    @Query("SELECT * FROM deleted_time_ranges WHERE startMs < :endMs AND endMs > :startMs ORDER BY startMs")
    suspend fun storedDeletedRanges(startMs: Long, endMs: Long): List<DeletedTimeRangeEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_time_ranges WHERE endMs > :cutoffMs OR endMs <= startMs)")
    suspend fun hasInvalidDeletedRanges(cutoffMs: Long): Boolean

    @Query("DELETE FROM deleted_time_ranges WHERE startMs >= :cutoffMs OR endMs <= startMs")
    suspend fun removeInvalidDeletedRanges(cutoffMs: Long)

    @Query("UPDATE deleted_time_ranges SET endMs = :cutoffMs WHERE endMs > :cutoffMs")
    suspend fun capDeletedRanges(cutoffMs: Long)

    /** Repair a bad future bound once, durably; a moving read-time cap would swallow new recordings. */
    @Transaction
    suspend fun validateDeletedRanges() {
        val now = System.currentTimeMillis()
        if (hasInvalidDeletedRanges(now)) {
            removeInvalidDeletedRanges(now)
            capDeletedRanges(now)
        }
    }

    @Transaction
    suspend fun deletedRanges(startMs: Long, endMs: Long): List<DeletedTimeRangeEntity> {
        validateDeletedRanges()
        return storedDeletedRanges(startMs, endMs)
    }

    @Query("SELECT * FROM deleted_time_ranges ORDER BY startMs")
    fun observeDeletedRanges(): Flow<List<DeletedTimeRangeEntity>>

    /** Serialize the purge check and batch insert with deletion's write transaction. */
    @Transaction
    suspend fun insertRecorded(samples: List<LocationSampleEntity>): List<Long> {
        if (samples.isEmpty()) return emptyList()
        val deleted = deletedRanges(samples.minOf { it.timestampMs }, samples.maxOf { it.timestampMs } + 1)
        return insertAll(samples.filter { sample ->
            deleted.none { sample.timestampMs >= it.startMs && sample.timestampMs < it.endMs }
        })
    }

    /** Keep the watchdog's extra fix only if a normal delivery hasn't already filled the gap. */
    @Transaction
    suspend fun insertIfStillStale(sample: LocationSampleEntity, cutoffMs: Long): Long? {
        val latest = mostRecent()
        if (latest != null && latest.timestampMs >= cutoffMs) return null
        return insertRecorded(listOf(sample)).firstOrNull()
    }

    @Query("SELECT * FROM location_samples WHERE dayEpoch = :dayEpoch ORDER BY timestampMs ASC")
    fun observeByDay(dayEpoch: Long): Flow<List<LocationSampleEntity>>

    @Query(
        "SELECT * FROM location_samples " +
                "WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
                "AND includedInComputation = 1 ORDER BY timestampMs ASC"
    )
    suspend fun rangeForComputation(startMs: Long, endMs: Long): List<LocationSampleEntity>

    @Query("SELECT * FROM location_samples WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs ASC")
    suspend fun range(startMs: Long, endMs: Long): List<LocationSampleEntity>

    /** The NEWEST [limit] samples of the window, descending — the data API's `limit=` path (the
     *  caller re-ascends). LIMIT lives in SQL so the cap saves DB work, not just IPC marshaling. */
    @Query(
        "SELECT * FROM location_samples WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
                "ORDER BY timestampMs DESC LIMIT :limit"
    )
    suspend fun rangeNewest(startMs: Long, endMs: Long, limit: Int): List<LocationSampleEntity>

    @Query("SELECT * FROM location_samples ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<LocationSampleEntity>

    @Query("SELECT * FROM location_samples ORDER BY timestampMs DESC LIMIT 1")
    suspend fun mostRecent(): LocationSampleEntity?

    @Query("SELECT * FROM location_samples ORDER BY timestampMs DESC, id DESC LIMIT 1")
    fun observeMostRecent(): Flow<LocationSampleEntity?>

    /** Flag a single sample as excluded from computation (e.g. GPS drift outside a place). */
    @Query("UPDATE location_samples SET includedInComputation = 0, exclusionReason = :reason WHERE id = :id")
    suspend fun markExcluded(id: Long, reason: String)

    /** Restore a sample after the user says a previously excluded drift fix was real movement. */
    @Query("UPDATE location_samples SET includedInComputation = 1, exclusionReason = NULL WHERE id = :id")
    suspend fun markIncluded(id: Long)

    @Query("SELECT COUNT(*) FROM location_samples")
    fun observeCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM location_samples")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM location_samples WHERE includedInComputation = 0")
    suspend fun excludedCount(): Long

    /** Distinct local-day keys that contain samples, for the timeline date picker. */
    @Query("SELECT DISTINCT dayEpoch FROM location_samples ORDER BY dayEpoch DESC")
    fun observeRecordedDays(): Flow<List<Long>>
}
