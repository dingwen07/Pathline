package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Data access for the backup pipeline: reading the dirty-partition set the triggers maintain,
 * reading whole-week slices of each stream to serialize, and restoring rows back **with their
 * original primary keys** so the relational links (visit->place, trip->visit) survive.
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

    @Query("SELECT * FROM tags ORDER BY id ASC")
    suspend fun allTags(): List<TagEntity>

    @Query("SELECT * FROM entity_tags ORDER BY tagId ASC")
    suspend fun allEntityTags(): List<EntityTagEntity>

    @Query("SELECT * FROM annotations ORDER BY id ASC")
    suspend fun allAnnotations(): List<AnnotationEntity>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTags(rows: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreEntityTags(rows: List<EntityTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAnnotations(rows: List<AnnotationEntity>)

    /** Wipe all derived/recorded data before an as-is restore (snapshot tables + append tables). */
    @Transaction
    suspend fun wipeForRestore() {
        clearTrips(); clearVisits(); clearSamples()
        clearGeofences(); clearPlaces(); clearStateExamples(); clearTransportExamples()
        clearAnnotations(); clearEntityTags(); clearTags()
        clearAllDirty()
    }

    /**
     * After an as-is restore, detach any trip endpoint whose referenced visit was not in the backup,
     * so a snapshot written by an older app version (or with already-broken links) can't leave trips
     * pointing at visits that were never imported. fromVisitId/toVisitId are grouping hints, so nulling
     * is the correct repair; without it a restore reproduces the dangling references verbatim.
     */
    @Transaction
    suspend fun detachDanglingTripVisits() {
        detachDanglingTripFromVisits(); detachDanglingTripToVisits()
    }

    @Query("UPDATE trips SET fromVisitId = NULL WHERE fromVisitId IS NOT NULL AND fromVisitId NOT IN (SELECT id FROM visits)")
    suspend fun detachDanglingTripFromVisits()

    @Query("UPDATE trips SET toVisitId = NULL WHERE toVisitId IS NOT NULL AND toVisitId NOT IN (SELECT id FROM visits)")
    suspend fun detachDanglingTripToVisits()

    /**
     * After an as-is restore, drop polymorphic tag links / annotations whose target row is absent.
     * No FK spans the polymorphic edge (targets are places/visits/trips), so a backup written by an
     * older app, or one whose targets were pruned, could leave entity_tags/annotations pointing at a
     * row that wasn't imported. Deleting the orphan is the correct repair (there is no ref to null).
     * Tags themselves are kept even when unlinked — an unused tag is legitimate.
     */
    @Transaction
    suspend fun purgeDanglingAnnotationsAndTags() {
        purgeDanglingEntityTags(); purgeDanglingAnnotations()
    }

    @Query(
        "DELETE FROM entity_tags WHERE NOT (" +
                "(targetType = 'PLACE' AND targetId IN (SELECT id FROM places)) OR " +
                "(targetType = 'VISIT' AND targetId IN (SELECT id FROM visits)) OR " +
                "(targetType = 'TRIP'  AND targetId IN (SELECT id FROM trips)))",
    )
    suspend fun purgeDanglingEntityTags()

    @Query(
        "DELETE FROM annotations WHERE NOT (" +
                "(targetType = 'PLACE' AND targetId IN (SELECT id FROM places)) OR " +
                "(targetType = 'VISIT' AND targetId IN (SELECT id FROM visits)) OR " +
                "(targetType = 'TRIP'  AND targetId IN (SELECT id FROM trips)))",
    )
    suspend fun purgeDanglingAnnotations()

    @Query("DELETE FROM trips")
    suspend fun clearTrips()

    @Query("DELETE FROM visits")
    suspend fun clearVisits()

    @Query("DELETE FROM location_samples")
    suspend fun clearSamples()

    @Query("DELETE FROM geofences")
    suspend fun clearGeofences()

    @Query("DELETE FROM places")
    suspend fun clearPlaces()

    @Query("DELETE FROM state_training_examples")
    suspend fun clearStateExamples()

    @Query("DELETE FROM transport_training_examples")
    suspend fun clearTransportExamples()

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM entity_tags")
    suspend fun clearEntityTags()

    @Query("DELETE FROM annotations")
    suspend fun clearAnnotations()
}
