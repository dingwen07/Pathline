package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Data access for the backup pipeline: reading the dirty-partition set the triggers maintain,
 * reading whole-week slices of each stream to serialize, and restoring rows back **with their
 * original primary keys** so the relational links (visit→place, trip→visit, segment→trip) survive.
 */
@Dao
interface BackupDao {

    // --- Dirty-partition tracker --------------------------------------------------------------

    @Query("SELECT * FROM backup_dirty_partitions")
    suspend fun allDirty(): List<BackupDirtyPartitionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markDirty(rows: List<BackupDirtyPartitionEntity>)

    @Query("DELETE FROM backup_dirty_partitions WHERE stream = :stream AND weekStart = :weekStart")
    suspend fun clearDirty(stream: String, weekStart: Long)

    @Query("DELETE FROM backup_dirty_partitions")
    suspend fun clearAllDirty()

    /**
     * Atomically read and remove the current dirty set ("claim"). Because this runs in a single
     * write transaction, no concurrent writer can commit between the read and the delete; any rows
     * a writer adds afterwards re-fire the triggers and survive for the next run. Callers re-mark a
     * partition (via [markDirty]) if emitting it fails, so nothing is lost.
     */
    @Transaction
    suspend fun claimDirty(): List<BackupDirtyPartitionEntity> {
        val claimed = allDirty()
        clearAllDirty()
        return claimed
    }

    // --- Whole-week slices for serialization (keyed by dayEpoch, matching the triggers) --------

    @Query("SELECT * FROM location_samples WHERE dayEpoch >= :startDay AND dayEpoch < :endDay ORDER BY id ASC")
    suspend fun samplesForDays(startDay: Long, endDay: Long): List<LocationSampleEntity>

    @Query("SELECT * FROM visits WHERE dayEpoch >= :startDay AND dayEpoch < :endDay ORDER BY id ASC")
    suspend fun visitsForDays(startDay: Long, endDay: Long): List<VisitEntity>

    @Query("SELECT * FROM trips WHERE dayEpoch >= :startDay AND dayEpoch < :endDay ORDER BY id ASC")
    suspend fun tripsForDays(startDay: Long, endDay: Long): List<TripEntity>

    // --- Distinct populated weeks per stream (for full dumps / reclaim reconciliation) ---------

    @Query("SELECT DISTINCT ((dayEpoch + 3) / 7) * 7 - 3 FROM location_samples")
    suspend fun sampleWeeks(): List<Long>

    /** Weeks holding samples within an inclusive-start / exclusive-end dayEpoch range (GPX range export). */
    @Query("SELECT DISTINCT ((dayEpoch + 3) / 7) * 7 - 3 FROM location_samples WHERE dayEpoch >= :startDay AND dayEpoch < :endDay")
    suspend fun sampleWeeksInDays(startDay: Long, endDay: Long): List<Long>

    /** Weeks touched by any sample recorded at/after [sinceMs] (incremental GPX auto-export). */
    @Query("SELECT DISTINCT ((dayEpoch + 3) / 7) * 7 - 3 FROM location_samples WHERE timestampMs >= :sinceMs")
    suspend fun sampleWeeksSince(sinceMs: Long): List<Long>

    @Query("SELECT DISTINCT ((dayEpoch + 3) / 7) * 7 - 3 FROM visits")
    suspend fun visitWeeks(): List<Long>

    @Query("SELECT DISTINCT ((dayEpoch + 3) / 7) * 7 - 3 FROM trips")
    suspend fun tripWeeks(): List<Long>

    // --- Snapshot reads (small, whole-table) ---------------------------------------------------

    @Query("SELECT * FROM places ORDER BY id ASC")
    suspend fun allPlaces(): List<PlaceEntity>

    @Query("SELECT * FROM geofences")
    suspend fun allGeofences(): List<GeofenceEntity>

    @Query("SELECT * FROM state_training_examples ORDER BY id ASC")
    suspend fun allStateExamples(): List<StateTrainingExampleEntity>

    @Query("SELECT * FROM transport_training_examples ORDER BY id ASC")
    suspend fun allTransportExamples(): List<TransportTrainingExampleEntity>

    // --- Restore (insert with explicit primary keys; REPLACE makes restore idempotent) ---------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSamples(rows: List<LocationSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePlaces(rows: List<PlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreVisits(rows: List<VisitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTrips(rows: List<TripEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreGeofences(rows: List<GeofenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreStateExamples(rows: List<StateTrainingExampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTransportExamples(rows: List<TransportTrainingExampleEntity>)

    /** Wipe all derived/recorded data before an as-is restore (snapshot tables + append tables). */
    @Transaction
    suspend fun wipeForRestore() {
        clearTrips(); clearVisits(); clearSamples()
        clearGeofences(); clearPlaces(); clearStateExamples(); clearTransportExamples()
        clearAllDirty()
    }

    @Query("DELETE FROM trips") suspend fun clearTrips()
    @Query("DELETE FROM visits") suspend fun clearVisits()
    @Query("DELETE FROM location_samples") suspend fun clearSamples()
    @Query("DELETE FROM geofences") suspend fun clearGeofences()
    @Query("DELETE FROM places") suspend fun clearPlaces()
    @Query("DELETE FROM state_training_examples") suspend fun clearStateExamples()
    @Query("DELETE FROM transport_training_examples") suspend fun clearTransportExamples()
}
