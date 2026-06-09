package net.extrawdw.apps.locationhistory.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.AnnotationEntity
import net.extrawdw.apps.locationhistory.data.db.EntityTagEntity
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folds a tag's human spelling down to the stable key two spellings of the same tag share. Per the
 * data-API design: lowercase, then strip every space/`-`/`_`, so `Coffee Shop`, `coffee-shop` and
 * `COFFEE_SHOP` all canonicalize to `coffeeshop` -> one tag row. [String.lowercase] is locale
 * invariant, so the key is stable regardless of the device locale.
 */
object TagCanonicalizer {
    fun canonicalize(displayName: String): String =
        displayName.lowercase().filterNot { it.isWhitespace() || it == '-' || it == '_' }
}

/**
 * Serialization for a **memory** payload: a *flat* string->string map stored as a JSON object. Values
 * must be strings — nesting and non-string JSON values are rejected ([parseStrict]). The map models
 * an agent's structured KV scratchpad; JSON is only the on-disk format.
 */
object MemoryMap {
    private val json = Json

    /** Serialize a flat map to its stored JSON-object form (insertion order preserved). */
    fun encode(entries: Map<String, String>): String =
        JsonObject(entries.mapValues { (_, v) -> JsonPrimitive(v) }).toString()

    /**
     * Lenient read of our own storage: tolerate null/blank/garbage as an empty map and silently drop
     * any non-string value (the writer never produces them; this just keeps a corrupt row from
     * throwing on read). Use [parseStrict] for untrusted input.
     */
    fun decode(content: String?): Map<String, String> {
        if (content.isNullOrBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(content) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        return buildMap {
            for ((k, v) in obj) {
                val prim = v as? JsonPrimitive ?: continue
                if (prim.isString) put(k, prim.content)
            }
        }
    }

    /**
     * Parse untrusted input (e.g. a write coming over the API) into a flat map, **rejecting** anything
     * that is not a JSON object of string values — a nested object/array or a numeric/boolean/null
     * value throws [IllegalArgumentException]. This is the enforcement point for the memory value rule.
     */
    fun parseStrict(content: String): Map<String, String> {
        val element = runCatching { json.parseToJsonElement(content) }.getOrElse {
            throw IllegalArgumentException("memory must be a JSON object", it)
        }
        require(element is JsonObject) { "memory must be a flat JSON object" }
        return buildMap {
            for ((k, v) in element) {
                require(v is JsonPrimitive && v.isString) {
                    "memory value for \"$k\" must be a string (no nesting, no JSON values)"
                }
                put(k, v.content)
            }
        }
    }
}

/**
 * Combine two pieces of user text when a merge folds one annotated row into another. Whitespace is
 * trimmed off each side first; a side that is blank contributes nothing; two equal texts collapse to
 * one (so re-merging the same content never duplicates it); otherwise they are joined oldest-first
 * with a blank line. Returns null only when both sides are blank.
 *
 * Used for both note concatenation and per-key memory-value conflicts — the rule is identical.
 */
internal fun foldText(a: String?, b: String?): String? {
    val sa = a?.trim().orEmpty()
    val sb = b?.trim().orEmpty()
    return when {
        sa.isEmpty() && sb.isEmpty() -> null
        sa.isEmpty() -> sb
        sb.isEmpty() -> sa
        sa == sb -> sa
        else -> "$sa\n\n$sb"
    }
}

/**
 * The domain entry point for tags, notes and memories. Owns the rules that the raw DAOs don't:
 * tag canonicalization, the flat string->string memory contract, the **merge fold** that moves a
 * dying row's content onto the surviving row, and the **cascade delete** that drops a deleted row's
 * annotations (there are no foreign keys across the polymorphic edge — integrity is kept in code).
 */
@Singleton
class AnnotationStore @Inject constructor(
    private val tagDao: TagDao,
    private val annotationDao: AnnotationDao,
) {
    private fun now() = System.currentTimeMillis()

    // --- Tags ----------------------------------------------------------------------------------

    /**
     * Tag [target]/[id] with [displayName], creating the tag row on first use. The tag is identified
     * by its canonical name; [displayName] is refreshed to the latest human spelling. Returns the tag
     * id, or null when [displayName] canonicalizes to nothing (e.g. all separators).
     */
    suspend fun applyTag(target: AnnotationTarget, id: Long, displayName: String): Long? {
        val canonical = TagCanonicalizer.canonicalize(displayName)
        if (canonical.isEmpty()) return null
        val ts = now()
        tagDao.insertIgnore(
            TagEntity(canonicalName = canonical, displayName = displayName, createdAtMs = ts),
        )
        val tag = tagDao.byCanonicalName(canonical) ?: return null
        if (tag.displayName != displayName) tagDao.updateDisplayName(tag.id, displayName)
        tagDao.link(EntityTagEntity(tagId = tag.id, targetType = target, targetId = id, createdAtMs = ts))
        return tag.id
    }

    /** Remove the [displayName] tag from [target]/[id] (the tag row itself is left for reuse). */
    suspend fun removeTag(target: AnnotationTarget, id: Long, displayName: String) {
        val tag = tagDao.byCanonicalName(TagCanonicalizer.canonicalize(displayName)) ?: return
        tagDao.unlink(tag.id, target, id)
    }

    suspend fun tagsFor(target: AnnotationTarget, id: Long): List<TagEntity> = tagDao.tagsFor(target, id)

    // --- Notes ---------------------------------------------------------------------------------

    suspend fun getNote(target: AnnotationTarget, id: Long): String? =
        annotationDao.byTarget(target, id, AnnotationKind.NOTE)?.content

    /** Set (or clear, when [text] is null/blank) the single note on [target]/[id]. */
    suspend fun setNote(target: AnnotationTarget, id: Long, text: String?) =
        put(target, id, AnnotationKind.NOTE, text?.takeIf { it.isNotBlank() })

    // --- Memories ------------------------------------------------------------------------------

    suspend fun getMemories(target: AnnotationTarget, id: Long): Map<String, String> =
        MemoryMap.decode(annotationDao.byTarget(target, id, AnnotationKind.MEMORY)?.content)

    /** Replace the whole memory map on [target]/[id] (an empty map clears it). */
    suspend fun setMemories(target: AnnotationTarget, id: Long, entries: Map<String, String>) =
        put(target, id, AnnotationKind.MEMORY, entries.takeIf { it.isNotEmpty() }?.let(MemoryMap::encode))

    /** Set one memory key, leaving the rest of the map intact. */
    suspend fun putMemory(target: AnnotationTarget, id: Long, key: String, value: String) =
        setMemories(target, id, getMemories(target, id) + (key to value))

    /** Remove one memory key. */
    suspend fun removeMemory(target: AnnotationTarget, id: Long, key: String) {
        val current = getMemories(target, id)
        if (key in current) setMemories(target, id, current - key)
    }

    // --- Merge fold & cascade delete -----------------------------------------------------------

    /**
     * Fold the annotations of the dying row [dyingId] onto the surviving row [survivorId] (same
     * [target] type) when a confirmed visit/trip merge keeps the earlier row's id and deletes the
     * later one. Tags become the set union; the note and any conflicting memory values are concatenated
     * oldest-first (survivor precedes the dying row, which is always later). Nothing is lost even though
     * the dying row's id disappears. Call inside the merge's update(A)+delete(B) sequence.
     */
    suspend fun foldOnMerge(target: AnnotationTarget, survivorId: Long, dyingId: Long) {
        if (survivorId == dyingId) return
        // Tags: union. Re-point B's links to A (OR IGNORE collapses ones A already has), then drop the
        // collided leftovers still keyed to B.
        tagDao.rekeyLinks(target, dyingId, survivorId)
        tagDao.unlinkAll(target, dyingId)
        // Note: survivor (older) precedes the dying row.
        foldNote(target, survivorId, dyingId)
        // Memory: key union; values that differ on a shared key concatenate.
        foldMemory(target, survivorId, dyingId)
        // The dying row's annotation rows have now been folded in; clear them.
        annotationDao.deleteForTarget(target, dyingId)
    }

    /**
     * Drop all annotations of a row being deleted (a visit/trip removed by the editor, or a place).
     * No FK cascades across the polymorphic edge, so the delete is done here in code.
     */
    suspend fun cascadeDelete(target: AnnotationTarget, id: Long) {
        tagDao.unlinkAll(target, id)
        annotationDao.deleteForTarget(target, id)
    }

    private suspend fun foldNote(target: AnnotationTarget, survivorId: Long, dyingId: Long) {
        val dying = annotationDao.byTarget(target, dyingId, AnnotationKind.NOTE)?.content ?: return
        val survivor = annotationDao.byTarget(target, survivorId, AnnotationKind.NOTE)?.content
        foldText(survivor, dying)?.let { put(target, survivorId, AnnotationKind.NOTE, it) }
    }

    private suspend fun foldMemory(target: AnnotationTarget, survivorId: Long, dyingId: Long) {
        val dying = MemoryMap.decode(annotationDao.byTarget(target, dyingId, AnnotationKind.MEMORY)?.content)
        if (dying.isEmpty()) return
        val merged = LinkedHashMap(
            MemoryMap.decode(annotationDao.byTarget(target, survivorId, AnnotationKind.MEMORY)?.content),
        )
        for ((k, v) in dying) merged[k] = foldText(merged[k], v) ?: v
        put(target, survivorId, AnnotationKind.MEMORY, MemoryMap.encode(merged))
    }

    /** Upsert one annotation payload, or delete it when [content] is null. Preserves the row id. */
    private suspend fun put(target: AnnotationTarget, id: Long, kind: AnnotationKind, content: String?) {
        if (content == null) {
            annotationDao.deleteForTargetKind(target, id, kind)
            return
        }
        val existing = annotationDao.byTarget(target, id, kind)
        if (existing == null) {
            annotationDao.upsert(
                AnnotationEntity(
                    targetType = target, targetId = id, kind = kind,
                    content = content, updatedAtMs = now(),
                ),
            )
        } else {
            annotationDao.update(existing.copy(content = content, updatedAtMs = now()))
        }
    }
}
