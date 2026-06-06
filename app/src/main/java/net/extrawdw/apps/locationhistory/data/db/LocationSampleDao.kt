package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationSampleDao {

    @Insert
    suspend fun insert(sample: LocationSampleEntity): Long

    @Insert
    suspend fun insertAll(samples: List<LocationSampleEntity>): List<Long>

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

    @Query("SELECT * FROM location_samples ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<LocationSampleEntity>

    @Query("SELECT * FROM location_samples ORDER BY timestampMs DESC LIMIT 1")
    suspend fun mostRecent(): LocationSampleEntity?

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
