package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.ConceptDao
import net.extrawdw.apps.locationhistory.data.db.ConceptEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptMemberEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The domain entry point for **concepts** — first-class semantic groups places/visits/trips join
 * (see [ConceptEntity]). Owns the rules the raw DAO doesn't: name/kind canonicalization (shared
 * with tags via [NameCanonicalizer]), the explicit create/rename/delete lifecycle with canonical
 * collision errors (unlike tags, a colliding concept name is rejected rather than silently reused —
 * the caller may have meant different kind/description), the no-nesting rule, and the cascade that
 * drops a deleted concept's memberships and annotations.
 *
 * Intrinsic edits (name/kind/description) bump the row's `updatedAtMs`/`updatedBy`; membership and
 * annotation changes don't — they carry their own attribution. `writer` follows the same
 * null-means-Pathline convention as [AnnotationStore].
 */
@Singleton
class ConceptStore @Inject constructor(
    private val conceptDao: ConceptDao,
    private val annotationStore: AnnotationStore,
) {
    private fun now() = System.currentTimeMillis()

    /**
     * Create a concept. [displayName] must canonicalize to something; [kind] is canonicalized the
     * same way (blank -> null). Throws [IllegalArgumentException] when the canonical name is empty
     * or already taken — concept creation is explicit, never a silent reuse.
     */
    suspend fun create(
        displayName: String,
        kind: String? = null,
        description: String? = null,
        writer: String? = null,
    ): ConceptEntity {
        val name = displayName.trim()
        val canonical = NameCanonicalizer.canonicalize(name)
        require(canonical.isNotEmpty()) { "Concept name must contain at least one word character" }
        val ts = now()
        val rowId = conceptDao.insertIgnore(
            ConceptEntity(
                canonicalName = canonical,
                displayName = name,
                kind = canonicalKind(kind),
                description = description?.trim()?.ifEmpty { null },
                createdAtMs = ts, updatedAtMs = ts,
                createdBy = writer, updatedBy = writer,
            ),
        )
        if (rowId == -1L) {
            val existing = conceptDao.byCanonicalName(canonical)
            throw IllegalArgumentException(
                "Concept '$name' already exists" + (existing?.let { " (id ${it.id})" } ?: ""),
            )
        }
        return checkNotNull(conceptDao.byId(rowId))
    }

    /**
     * Partially update a concept's intrinsic fields. A null [displayName] leaves the name alone;
     * [setKind]/[setDescription] discriminate "leave alone" from "set (possibly to null = clear)" so
     * a partial write can't accidentally wipe a field. Bumps `updatedAtMs`/`updatedBy` only when a
     * field actually changed. Returns the row, or null when [id] doesn't exist. Throws on a rename
     * whose canonical name another concept already holds.
     */
    suspend fun update(
        id: Long,
        displayName: String? = null,
        kind: String? = null,
        setKind: Boolean = false,
        description: String? = null,
        setDescription: Boolean = false,
        writer: String? = null,
    ): ConceptEntity? {
        val current = conceptDao.byId(id) ?: return null
        var next = current
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            val canonical = NameCanonicalizer.canonicalize(name)
            require(canonical.isNotEmpty()) { "Concept name must contain at least one word character" }
            val holder = conceptDao.byCanonicalName(canonical)
            require(holder == null || holder.id == id) {
                "Concept '$name' already exists (id ${holder!!.id})"
            }
            next = next.copy(canonicalName = canonical, displayName = name)
        }
        if (setKind) next = next.copy(kind = canonicalKind(kind))
        if (setDescription) next = next.copy(description = description?.trim()?.ifEmpty { null })
        if (next == current) return current
        return next.copy(updatedAtMs = now(), updatedBy = writer)
            .also { conceptDao.update(it) }
    }

    /** Delete a concept: the row, its memberships, and its own annotations (tags/note/memories).
     *  Returns false when [id] doesn't exist. Members themselves are untouched. */
    suspend fun delete(id: Long): Boolean {
        if (conceptDao.delete(id) == 0) return false
        conceptDao.removeAllMembers(id)
        annotationStore.cascadeDelete(AnnotationTarget.CONCEPT, id)
        return true
    }

    /**
     * Attach [target]/[targetId] to concept [conceptId]. Re-adding is a no-op that keeps the
     * original attached-at/by (insert-ignore). Returns false when the concept doesn't exist.
     * Throws on a CONCEPT member — concepts never nest.
     */
    suspend fun addMember(
        conceptId: Long,
        target: AnnotationTarget,
        targetId: Long,
        writer: String? = null,
    ): Boolean {
        require(target != AnnotationTarget.CONCEPT) { "Concepts cannot contain concepts (no nesting)" }
        conceptDao.byId(conceptId) ?: return false
        conceptDao.addMember(
            ConceptMemberEntity(
                conceptId = conceptId, targetType = target, targetId = targetId,
                createdAtMs = now(), createdBy = writer,
            ),
        )
        return true
    }

    /** Detach one member; returns the number of rows removed (0 = wasn't a member). */
    suspend fun removeMember(conceptId: Long, target: AnnotationTarget, targetId: Long): Int =
        conceptDao.removeMember(conceptId, target, targetId)

    /** Canonicalize a kind string under the same folding as names; blank/empty -> null (untyped). */
    private fun canonicalKind(kind: String?): String? =
        kind?.let { NameCanonicalizer.canonicalize(it).ifEmpty { null } }
}
