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

    @Query("DELETE FROM backup_dirty_partitions WHERE stream = :stream AND weekStart = :weekStart")
    suspend fun clearDirty(stream: String, weekStart: Long)

    @Query("DELETE FROM backup_dirty_partitions")
    suspend fun clearAllDirty()

    /**
     * Delete exactly the given dirty rows, by key. The backup engine reads the dirty set at run
     * start (without consuming it) and calls this only AFTER its manifest commit, with the keys it
     * successfully emitted — so a run that dies anywhere before the commit leaves every marker in
     * place, and a week marked dirty during the run (a new key) is never touched. Known gap: the
     * dirty triggers insert-where-not-exists, so a claimed week re-marked mid-run keeps its
     * original row and is deleted here anyway; rows written to it after its emit wait for that
     * week's next change (the entity carries no generation counter to tell the two apart).
     */
    @Transaction
    suspend fun clearDirtySet(rows: List<BackupDirtyPartitionEntity>) {
        rows.forEach { clearDirty(it.stream, it.weekStart) }
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

    @Query("SELECT * FROM place_coordinate_repairs ORDER BY id ASC")
    suspend fun allPlaceCoordinateRepairs(): List<PlaceCoordinateRepairEntity>

    @Query("SELECT * FROM geofences")
    suspend fun allGeofences(): List<GeofenceEntity>

    @Query("SELECT * FROM tags ORDER BY id ASC")
    suspend fun allTags(): List<TagEntity>

    @Query("SELECT * FROM entity_tags ORDER BY tagId ASC")
    suspend fun allEntityTags(): List<EntityTagEntity>

    @Query("SELECT * FROM annotations ORDER BY id ASC")
    suspend fun allAnnotations(): List<AnnotationEntity>

    @Query("SELECT * FROM concepts ORDER BY id ASC")
    suspend fun allConcepts(): List<ConceptEntity>

    @Query("SELECT * FROM concept_members ORDER BY conceptId ASC")
    suspend fun allConceptMembers(): List<ConceptMemberEntity>

    // --- Restore (insert with explicit primary keys; REPLACE makes restore idempotent) ---------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSamples(rows: List<LocationSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePlaces(rows: List<PlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePlaceCoordinateRepairs(rows: List<PlaceCoordinateRepairEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreVisits(rows: List<VisitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTrips(rows: List<TripEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreGeofences(rows: List<GeofenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTags(rows: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreEntityTags(rows: List<EntityTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAnnotations(rows: List<AnnotationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreConcepts(rows: List<ConceptEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreConceptMembers(rows: List<ConceptMemberEntity>)

    /**
     * Recover old complete Google candidates where both historical coordinate interpretations are
     * provably exact identity. This runs before [clearUntrustedCandidates] during restore.
     */
    @Query(
        "UPDATE visits SET candidateCoordinateFrame = 'WGS84', candidateOrigin = 'MAPS' " +
                "WHERE candidateCoordinateFrame = 'UNKNOWN' AND candidateOrigin = 'UNKNOWN' AND " +
                "candidateName IS NOT NULL AND candidateGooglePlaceId IS NOT NULL AND " +
                "candidateLatitude IS NOT NULL AND candidateLongitude IS NOT NULL AND " +
                "candidateLatitude BETWEEN -90.0 AND 90.0 AND " +
                "candidateLongitude BETWEEN -180.0 AND 180.0 AND " +
                "(candidateLatitude < 18.1520757 OR candidateLatitude > 53.590963401 OR " +
                "candidateLongitude < 73.586083281 OR candidateLongitude > 134.760415954)",
    )
    suspend fun classifyIdentityFrameLegacyCandidates()

    /** Clear any candidate tuple that is not complete canonical Maps data, regardless of manifest. */
    @Query(
        "UPDATE visits SET candidateName = NULL, candidateGooglePlaceId = NULL, " +
                "candidateLatitude = NULL, candidateLongitude = NULL, " +
                "candidateCoordinateFrame = 'UNKNOWN', candidateOrigin = 'UNKNOWN' " +
                "WHERE candidateName IS NULL OR candidateLatitude IS NULL OR " +
                "candidateLongitude IS NULL OR candidateCoordinateFrame != 'WGS84' OR " +
                "candidateOrigin != 'MAPS'",
    )
    suspend fun clearUntrustedCandidates()

    /** Wipe all derived/recorded data before an as-is restore (snapshot tables + append tables). */
    @Transaction
    suspend fun wipeForRestore() {
        clearTrips(); clearVisits(); clearSamples()
        clearGeofences(); clearPlaces(); clearPlaceCoordinateRepairs()
        clearAnnotations(); clearEntityTags(); clearTags()
        clearConceptMembers(); clearConcepts()
        clearAllDirty()
    }

    @Query("DELETE FROM place_coordinate_repairs")
    suspend fun clearPlaceCoordinateRepairs()

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
     * After an as-is restore, drop polymorphic tag links / annotations / concept memberships whose
     * target row is absent. No FK spans the polymorphic edge (targets are
     * places/visits/trips/concepts), so a backup written by an older app, or one whose targets were
     * pruned, could leave rows pointing at a target that wasn't imported. Deleting the orphan is the
     * correct repair (there is no ref to null). Tags and concepts themselves are kept even when
     * unlinked/empty — both are legitimate without members.
     */
    @Transaction
    suspend fun purgeDanglingAnnotationsAndTags() {
        purgeDanglingEntityTags(); purgeDanglingAnnotations(); purgeDanglingConceptMembers()
    }

    @Query(
        "DELETE FROM entity_tags WHERE NOT (" +
                "(targetType = 'PLACE' AND targetId IN (SELECT id FROM places)) OR " +
                "(targetType = 'VISIT' AND targetId IN (SELECT id FROM visits)) OR " +
                "(targetType = 'TRIP'  AND targetId IN (SELECT id FROM trips)) OR " +
                "(targetType = 'CONCEPT' AND targetId IN (SELECT id FROM concepts)))",
    )
    suspend fun purgeDanglingEntityTags()

    @Query(
        "DELETE FROM annotations WHERE NOT (" +
                "(targetType = 'PLACE' AND targetId IN (SELECT id FROM places)) OR " +
                "(targetType = 'VISIT' AND targetId IN (SELECT id FROM visits)) OR " +
                "(targetType = 'TRIP'  AND targetId IN (SELECT id FROM trips)) OR " +
                "(targetType = 'CONCEPT' AND targetId IN (SELECT id FROM concepts)))",
    )
    suspend fun purgeDanglingAnnotations()

    /** Members must point at an imported target AND an imported concept — the CONCEPT leg keeps
     *  nested memberships whose child concept was imported, like the other target types. */
    @Query(
        "DELETE FROM concept_members WHERE conceptId NOT IN (SELECT id FROM concepts) OR NOT (" +
                "(targetType = 'PLACE' AND targetId IN (SELECT id FROM places)) OR " +
                "(targetType = 'VISIT' AND targetId IN (SELECT id FROM visits)) OR " +
                "(targetType = 'TRIP'  AND targetId IN (SELECT id FROM trips)) OR " +
                "(targetType = 'CONCEPT' AND targetId IN (SELECT id FROM concepts)))",
    )
    suspend fun purgeDanglingConceptMembers()

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

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM entity_tags")
    suspend fun clearEntityTags()

    @Query("DELETE FROM annotations")
    suspend fun clearAnnotations()

    @Query("DELETE FROM concepts")
    suspend fun clearConcepts()

    @Query("DELETE FROM concept_members")
    suspend fun clearConceptMembers()
}
