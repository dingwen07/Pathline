package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget

/**
 * Tags + the polymorphic tag<->target join. Lookup/merge/search logic is layered on in later phases;
 * this is the durable storage surface introduced with the v2 schema.
 */
@Dao
interface TagDao {

    /** Insert a tag, ignoring if its [TagEntity.canonicalName] already exists; returns rowid or -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE canonicalName = :canonicalName LIMIT 1")
    suspend fun byCanonicalName(canonicalName: String): TagEntity?

    /** Refresh a tag's human spelling — [TagEntity.displayName] keeps the most recent one written. */
    @Query("UPDATE tags SET displayName = :displayName WHERE id = :tagId")
    suspend fun updateDisplayName(tagId: Long, displayName: String)

    @Query("SELECT * FROM tags ORDER BY displayName")
    suspend fun all(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id IN (:ids) ORDER BY displayName")
    suspend fun byIds(ids: List<Long>): List<TagEntity>

    /** Every tag<->target link. Small by nature (only user-curated content); the data API loads it
     *  whole to scope the tag list to the caller's visible targets, which span two databases. */
    @Query("SELECT * FROM entity_tags")
    suspend fun allLinks(): List<EntityTagEntity>

    /** Targets of [type] carrying any of [tagIds] — the tag leg of the data API's search. */
    @Query("SELECT DISTINCT targetId FROM entity_tags WHERE targetType = :type AND tagId IN (:tagIds)")
    suspend fun targetIdsForTags(type: AnnotationTarget, tagIds: List<Long>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: EntityTagEntity)

    @Query(
        "SELECT t.* FROM tags t JOIN entity_tags e ON e.tagId = t.id " +
                "WHERE e.targetType = :type AND e.targetId = :id ORDER BY t.displayName",
    )
    suspend fun tagsFor(type: AnnotationTarget, id: Long): List<TagEntity>

    /** The link rows of one target — carries each link's attached-at/by beside [tagsFor]'s rows. */
    @Query("SELECT * FROM entity_tags WHERE targetType = :type AND targetId = :id")
    suspend fun linksFor(type: AnnotationTarget, id: Long): List<EntityTagEntity>

    @Query("DELETE FROM entity_tags WHERE tagId = :tagId AND targetType = :type AND targetId = :id")
    suspend fun unlink(tagId: Long, type: AnnotationTarget, id: Long): Int

    /** Drop every tag link for a target (used when the target is deleted; no FK to cascade). */
    @Query("DELETE FROM entity_tags WHERE targetType = :type AND targetId = :id")
    suspend fun unlinkAll(type: AnnotationTarget, id: Long)

    /**
     * Re-point every tag link of [fromId] onto [toId] (same [type]) when a merge folds the later row
     * into the earlier survivor. `OR IGNORE` collapses links to tags the survivor already carries (the
     * set-union rule); the leftover duplicate rows on [fromId] are then cleared by [unlinkAll].
     */
    @Query(
        "UPDATE OR IGNORE entity_tags SET targetId = :toId " +
                "WHERE targetType = :type AND targetId = :fromId",
    )
    suspend fun rekeyLinks(type: AnnotationTarget, fromId: Long, toId: Long)
}

/** Notes + memories. One note and one memory per target (enforced by the unique index). */
@Dao
interface AnnotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(annotation: AnnotationEntity): Long

    @Update
    suspend fun update(annotation: AnnotationEntity)

    @Query("SELECT * FROM annotations WHERE targetType = :type AND targetId = :id AND kind = :kind LIMIT 1")
    suspend fun byTarget(type: AnnotationTarget, id: Long, kind: AnnotationKind): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE targetType = :type AND targetId = :id")
    suspend fun allForTarget(type: AnnotationTarget, id: Long): List<AnnotationEntity>

    /** Targets of [type] whose note/memory text contains [pattern] (a `%…%` LIKE pattern with `\`
     *  as escape) — the annotation leg of the data API's search. Substring, case-insensitive. */
    @Query(
        "SELECT DISTINCT targetId FROM annotations " +
                "WHERE targetType = :type AND kind = :kind AND content LIKE :pattern ESCAPE '\\'",
    )
    suspend fun targetIdsWithContentLike(
        type: AnnotationTarget,
        kind: AnnotationKind,
        pattern: String,
    ): List<Long>

    /** Every annotation row of one [kind] for one target [type] — small by nature (one row per
     *  annotated target). The data API's memory search decodes these and matches in code, because a
     *  raw LIKE over the stored JSON would also hit its structural keys. */
    @Query("SELECT * FROM annotations WHERE targetType = :type AND kind = :kind")
    suspend fun allOfKind(type: AnnotationTarget, kind: AnnotationKind): List<AnnotationEntity>

    /** Clear just one payload (note OR memory) of a target, leaving the other intact. */
    @Query("DELETE FROM annotations WHERE targetType = :type AND targetId = :id AND kind = :kind")
    suspend fun deleteForTargetKind(type: AnnotationTarget, id: Long, kind: AnnotationKind)

    /** Clear every annotation of a target (used on cascade-delete and after a merge folds B into A). */
    @Query("DELETE FROM annotations WHERE targetType = :type AND targetId = :id")
    suspend fun deleteForTarget(type: AnnotationTarget, id: Long)
}

/**
 * Concepts + the polymorphic concept<->member join. Same integrity model as [TagDao]: no FK across
 * the polymorphic edge; delete/merge integrity is kept in code (see `AnnotationStore` and
 * `ConceptStore`).
 */
@Dao
interface ConceptDao {

    /** Insert a concept, ignoring if its [ConceptEntity.canonicalName] already exists; rowid or -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(concept: ConceptEntity): Long

    @Update
    suspend fun update(concept: ConceptEntity)

    @Query("SELECT * FROM concepts WHERE id = :id")
    suspend fun byId(id: Long): ConceptEntity?

    @Query("SELECT * FROM concepts WHERE canonicalName = :canonicalName LIMIT 1")
    suspend fun byCanonicalName(canonicalName: String): ConceptEntity?

    @Query("SELECT * FROM concepts ORDER BY displayName")
    suspend fun all(): List<ConceptEntity>

    @Query("SELECT * FROM concepts WHERE kind = :kind ORDER BY displayName")
    suspend fun byKind(kind: String): List<ConceptEntity>

    @Query("SELECT * FROM concepts WHERE id IN (:ids) ORDER BY displayName")
    suspend fun byIds(ids: List<Long>): List<ConceptEntity>

    @Query("DELETE FROM concepts WHERE id = :id")
    suspend fun delete(id: Long): Int

    // --- Members ---------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMember(member: ConceptMemberEntity)

    @Query("SELECT * FROM concept_members WHERE conceptId = :conceptId ORDER BY createdAtMs, targetType, targetId")
    suspend fun membersOf(conceptId: Long): List<ConceptMemberEntity>

    /** Membership rows of one target — the reverse edge, with per-row attached-at/by intact. */
    @Query("SELECT * FROM concept_members WHERE targetType = :type AND targetId = :id")
    suspend fun membershipsFor(type: AnnotationTarget, id: Long): List<ConceptMemberEntity>

    @Query(
        "SELECT c.* FROM concepts c JOIN concept_members m ON m.conceptId = c.id " +
                "WHERE m.targetType = :type AND m.targetId = :id ORDER BY c.displayName",
    )
    suspend fun conceptsFor(type: AnnotationTarget, id: Long): List<ConceptEntity>

    @Query("DELETE FROM concept_members WHERE conceptId = :conceptId AND targetType = :type AND targetId = :id")
    suspend fun removeMember(conceptId: Long, type: AnnotationTarget, id: Long): Int

    /** Drop every membership of one concept (concept deletion). */
    @Query("DELETE FROM concept_members WHERE conceptId = :conceptId")
    suspend fun removeAllMembers(conceptId: Long)

    /** Drop every membership of one target (target deleted; no FK to cascade). */
    @Query("DELETE FROM concept_members WHERE targetType = :type AND targetId = :id")
    suspend fun removeMembersForTarget(type: AnnotationTarget, id: Long)

    /** Re-point memberships on a merge, like [TagDao.rekeyLinks] — OR IGNORE unions collisions. */
    @Query(
        "UPDATE OR IGNORE concept_members SET targetId = :toId " +
                "WHERE targetType = :type AND targetId = :fromId",
    )
    suspend fun rekeyMembers(type: AnnotationTarget, fromId: Long, toId: Long)
}
