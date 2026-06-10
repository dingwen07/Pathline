package net.extrawdw.apps.locationhistory.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.AnnotationEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptDao
import net.extrawdw.apps.locationhistory.data.db.EntityTagEntity
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folds a human spelling down to the stable key two spellings of the same name share. Used for tag
 * names, concept names and concept kinds alike. Per the data-API design: lowercase, then collapse
 * every run of whitespace/`-`/`_` to a single `-`, with no leading or trailing separator. So
 * `My Home`, `my  home`, `my-home`, `my_home` and `My HoME` all canonicalize to `my-home` -> one
 * row, while `myhome` (no separator) stays distinct. Separators are normalized, not stripped, so
 * word boundaries survive (`ab cd` != `abc d`). [String.lowercase] is locale invariant, so the key
 * is stable regardless of the device locale.
 */
object NameCanonicalizer {
    fun canonicalize(displayName: String): String {
        val out = StringBuilder()
        var pendingSeparator = false
        for (ch in displayName.lowercase()) {
            if (ch.isWhitespace() || ch == '-' || ch == '_') {
                // Defer emitting a '-' so leading/trailing runs vanish and inner runs collapse to one.
                pendingSeparator = true
            } else {
                if (pendingSeparator && out.isNotEmpty()) out.append('-')
                pendingSeparator = false
                out.append(ch)
            }
        }
        return out.toString()
    }
}

/**
 * One **memory** entry: the string [value] an agent stored under a key, with the agent's
 * [confidence] in it (in [0,1]; 1 when never stated), an optional free-form [source] — the
 * writer's own provenance note ("user stated in chat", "inferred from visit:675 note") —
 * [updatedAtMs], when this key was last written (null only for entries stored before per-entry
 * stamps existed, which read back with no time at all), and [updatedBy], the package that last
 * wrote it (null = Pathline itself: a maintenance fold, or a pre-attribution legacy entry). Values
 * are always plain strings — the flat string->string rule of the data-API design; confidence,
 * source, stamp and writer are metadata beside the value, never inside it.
 */
data class MemoryEntry(
    val value: String,
    val confidence: Float = 1f,
    val source: String? = null,
    val updatedAtMs: Long? = null,
    val updatedBy: String? = null,
)

/**
 * Serialization for a **memory** payload: a *flat* key->[MemoryEntry] map stored as a JSON object of
 * `{"value": <string>, "confidence": <0..1>, "source": <string?>, "updatedAtMs": <long?>,
 * "updatedBy": <string?>}` objects. The map models an agent's structured KV scratchpad; JSON is only
 * the on-disk format. The "values must be plain strings" rule of the public API is enforced at the
 * provider write path (a memory write carries value, confidence and source as separate typed
 * fields), not here.
 */
object MemoryMap {
    private val json = Json
    private const val VALUE = "value"
    private const val CONFIDENCE = "confidence"
    private const val SOURCE = "source"
    private const val UPDATED_AT = "updatedAtMs"
    private const val UPDATED_BY = "updatedBy"

    /** Serialize a flat map to its stored JSON-object form (insertion order preserved). */
    fun encode(entries: Map<String, MemoryEntry>): String =
        JsonObject(
            entries.mapValues { (_, e) ->
                JsonObject(
                    buildMap {
                        put(VALUE, JsonPrimitive(e.value))
                        put(CONFIDENCE, JsonPrimitive(e.confidence))
                        e.source?.let { put(SOURCE, JsonPrimitive(it)) }
                        e.updatedAtMs?.let { put(UPDATED_AT, JsonPrimitive(it)) }
                        e.updatedBy?.let { put(UPDATED_BY, JsonPrimitive(it)) }
                    },
                )
            },
        ).toString()

    /**
     * Lenient read of our own storage: tolerate null/blank/garbage as an empty map and silently drop
     * any malformed entry (the writer never produces them; this just keeps a corrupt row from
     * throwing on read). A bare string value (the pre-confidence dev format) reads as confidence 1;
     * an out-of-range confidence is clamped; a missing/invalid stamp (any pre-stamp format) reads
     * as null.
     */
    fun decode(content: String?): Map<String, MemoryEntry> {
        if (content.isNullOrBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(content) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        return buildMap {
            for ((k, v) in obj) {
                when (v) {
                    is JsonPrimitive -> if (v.isString) put(k, MemoryEntry(v.content))
                    is JsonObject -> {
                        val value = (v[VALUE] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: continue
                        val confidence = (v[CONFIDENCE] as? JsonPrimitive)?.content?.toFloatOrNull()
                            ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
                        val source = (v[SOURCE] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?.trim()?.ifEmpty { null }
                        val updatedAt = (v[UPDATED_AT] as? JsonPrimitive)?.content?.toLongOrNull()
                            ?.takeIf { it > 0 }
                        val updatedBy = (v[UPDATED_BY] as? JsonPrimitive)?.takeIf { it.isString }
                            ?.content?.trim()?.ifEmpty { null }
                        put(k, MemoryEntry(value, confidence, source, updatedAt, updatedBy))
                    }

                    else -> continue
                }
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
 * Combine two memory-entry **sources** when a merge folds rows together: nulls/blanks contribute
 * nothing, equal sources collapse to one, differing sources join oldest-first with "; " — the
 * combined value came from both origins, so the provenance says so. Null when both are absent.
 */
internal fun foldSources(a: String?, b: String?): String? {
    val parts = listOfNotNull(a?.trim()?.ifEmpty { null }, b?.trim()?.ifEmpty { null }).distinct()
    return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/**
 * A target's user-editable annotations, bundled for the in-app editor: the single [note], the tag
 * [tags] (display spellings), and the read-only [memories] map. Memories are the agent's structured
 * KV scratchpad — surfaced for transparency, never hand-edited.
 */
data class AnnotationData(
    val note: String,
    val tags: List<String>,
    val memories: Map<String, MemoryEntry>,
)

/**
 * The domain entry point for tags, notes and memories. Owns the rules that the raw DAOs don't:
 * name canonicalization, the flat string->string memory contract, the **merge fold** that moves a
 * dying row's content onto the surviving row, and the **cascade delete** that drops a deleted row's
 * annotations and concept memberships (there are no foreign keys across the polymorphic edge —
 * integrity is kept in code).
 *
 * Every write path takes an optional `writer` — the calling package when the write arrives through
 * the data API, null when Pathline itself writes (the in-app editor, or a maintenance fold). The
 * null-means-Pathline convention is what the API's `*_by_me` columns are derived from, so callers
 * must never pass a placeholder.
 */
@Singleton
class AnnotationStore @Inject constructor(
    private val tagDao: TagDao,
    private val annotationDao: AnnotationDao,
    private val conceptDao: ConceptDao,
) {
    private fun now() = System.currentTimeMillis()

    // --- Tags ----------------------------------------------------------------------------------

    /**
     * Tag [target]/[id] with [displayName], creating the tag row on first use. The tag is identified
     * by its canonical name; [displayName] is refreshed to the latest human spelling. Returns the tag
     * id, or null when [displayName] canonicalizes to nothing (e.g. all separators). [writer] is
     * recorded only on rows this call actually creates — a re-apply never re-attributes.
     */
    suspend fun applyTag(
        target: AnnotationTarget,
        id: Long,
        displayName: String,
        writer: String? = null,
    ): Long? {
        val canonical = NameCanonicalizer.canonicalize(displayName)
        if (canonical.isEmpty()) return null
        val ts = now()
        tagDao.insertIgnore(
            TagEntity(
                canonicalName = canonical, displayName = displayName,
                createdAtMs = ts, createdBy = writer,
            ),
        )
        val tag = tagDao.byCanonicalName(canonical) ?: return null
        if (tag.displayName != displayName) tagDao.updateDisplayName(tag.id, displayName)
        tagDao.link(
            EntityTagEntity(
                tagId = tag.id,
                targetType = target,
                targetId = id,
                createdAtMs = ts,
                createdBy = writer,
            )
        )
        return tag.id
    }

    /** Remove the [displayName] tag from [target]/[id] (the tag row itself is left for reuse).
     *  Returns the number of links removed (0 when the tag didn't exist or wasn't applied). */
    suspend fun removeTag(target: AnnotationTarget, id: Long, displayName: String): Int {
        val tag = tagDao.byCanonicalName(NameCanonicalizer.canonicalize(displayName)) ?: return 0
        return tagDao.unlink(tag.id, target, id)
    }

    suspend fun tagsFor(target: AnnotationTarget, id: Long): List<TagEntity> =
        tagDao.tagsFor(target, id)

    // --- In-app editor (load / save the whole editable bundle) ---------------------------------

    /** Read [target]/[id]'s note, tags and (view-only) memories for the in-app annotation editor. */
    suspend fun loadEdits(target: AnnotationTarget, id: Long): AnnotationData = AnnotationData(
        note = getNote(target, id).orEmpty(),
        tags = tagsFor(target, id).map { it.displayName },
        memories = getMemories(target, id),
    )

    /**
     * Persist a user's edits to [target]/[id]'s note and tag set. **Stateless** — it reconciles the
     * given [tags] against what's currently stored, so the caller needn't track the originals: tags not
     * yet present are applied, stored tags no longer present are unlinked, and duplicates (by canonical
     * name) collapse. The note is written only when it actually changed, so an untouched editor doesn't
     * bump the row. Memories are intentionally not written here (view-only in the app).
     */
    suspend fun saveEdits(target: AnnotationTarget, id: Long, note: String?, tags: List<String>) {
        val normalizedNew = note?.takeIf { it.isNotBlank() }
        if (getNote(target, id) != normalizedNew) setNote(target, id, normalizedNew)

        // Desired tags keyed by canonical name (first human spelling wins; empties dropped).
        val desired = LinkedHashMap<String, String>()
        for (display in tags) {
            val trimmed = display.trim()
            val canonical = NameCanonicalizer.canonicalize(trimmed)
            if (canonical.isNotEmpty()) desired.putIfAbsent(canonical, trimmed)
        }
        val existingByKey = tagDao.tagsFor(target, id)
            .associateBy { NameCanonicalizer.canonicalize(it.displayName) }
        for ((key, display) in desired) if (key !in existingByKey) applyTag(target, id, display)
        for ((key, tag) in existingByKey) if (key !in desired) tagDao.unlink(tag.id, target, id)
    }

    // --- Notes ---------------------------------------------------------------------------------

    suspend fun getNote(target: AnnotationTarget, id: Long): String? =
        annotationDao.byTarget(target, id, AnnotationKind.NOTE)?.content

    /** Set (or clear, when [text] is null/blank) the single note on [target]/[id]. */
    suspend fun setNote(target: AnnotationTarget, id: Long, text: String?, writer: String? = null) =
        put(target, id, AnnotationKind.NOTE, text?.takeIf { it.isNotBlank() }, writer)

    // --- Memories ------------------------------------------------------------------------------

    suspend fun getMemories(target: AnnotationTarget, id: Long): Map<String, MemoryEntry> =
        MemoryMap.decode(annotationDao.byTarget(target, id, AnnotationKind.MEMORY)?.content)

    /** Replace the whole memory map on [target]/[id] (an empty map clears it). [writer] attributes
     *  the row-level write; the per-entry writers travel inside [entries]. */
    suspend fun setMemories(
        target: AnnotationTarget,
        id: Long,
        entries: Map<String, MemoryEntry>,
        writer: String? = null,
    ) = put(
        target,
        id,
        AnnotationKind.MEMORY,
        entries.takeIf { it.isNotEmpty() }?.let(MemoryMap::encode),
        writer,
    )

    /** Set one memory key, leaving the rest of the map intact. [confidence] is clamped to [0,1];
     *  [source] is the writer's provenance note (null = none; a rewrite replaces it). The entry is
     *  stamped with the write time and attributed to [writer]. */
    suspend fun putMemory(
        target: AnnotationTarget,
        id: Long,
        key: String,
        value: String,
        confidence: Float = 1f,
        source: String? = null,
        writer: String? = null,
    ) = setMemories(
        target, id,
        getMemories(target, id) +
                (
                        key to MemoryEntry(
                            value, confidence.coerceIn(0f, 1f), source?.trim()?.ifEmpty { null },
                            updatedAtMs = now(),
                            updatedBy = writer,
                        )
                        ),
        writer,
    )

    /** Remove one memory key. */
    suspend fun removeMemory(
        target: AnnotationTarget,
        id: Long,
        key: String,
        writer: String? = null
    ) {
        val current = getMemories(target, id)
        if (key in current) setMemories(target, id, current - key, writer)
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
        // Concept memberships: same union rule as tags.
        conceptDao.rekeyMembers(target, dyingId, survivorId)
        conceptDao.removeMembersForTarget(target, dyingId)
        // Note: survivor (older) precedes the dying row.
        foldNote(target, survivorId, dyingId)
        // Memory: key union; values that differ on a shared key concatenate.
        foldMemory(target, survivorId, dyingId)
        // The dying row's annotation rows have now been folded in; clear them.
        annotationDao.deleteForTarget(target, dyingId)
    }

    /**
     * Drop all annotations and concept memberships of a row being deleted (a visit/trip removed by
     * the editor, or a place). No FK cascades across the polymorphic edge, so the delete is done
     * here in code.
     */
    suspend fun cascadeDelete(target: AnnotationTarget, id: Long) {
        tagDao.unlinkAll(target, id)
        conceptDao.removeMembersForTarget(target, id)
        annotationDao.deleteForTarget(target, id)
    }

    private suspend fun foldNote(target: AnnotationTarget, survivorId: Long, dyingId: Long) {
        val dying = annotationDao.byTarget(target, dyingId, AnnotationKind.NOTE)?.content ?: return
        val survivor = annotationDao.byTarget(target, survivorId, AnnotationKind.NOTE)?.content
        // writer = null: the folded text is Pathline's own composite, not either app's write.
        foldText(survivor, dying)?.let {
            put(
                target,
                survivorId,
                AnnotationKind.NOTE,
                it,
                writer = null
            )
        }
    }

    private suspend fun foldMemory(target: AnnotationTarget, survivorId: Long, dyingId: Long) {
        val dying = MemoryMap.decode(
            annotationDao.byTarget(
                target,
                dyingId,
                AnnotationKind.MEMORY
            )?.content
        )
        if (dying.isEmpty()) return
        val merged = LinkedHashMap(
            MemoryMap.decode(
                annotationDao.byTarget(
                    target,
                    survivorId,
                    AnnotationKind.MEMORY
                )?.content
            ),
        )
        for ((k, d) in dying) {
            val s = merged[k]
            // The fold itself is not a write — the combined entry carries the later of the two
            // writers' stamps (null only when both halves predate per-entry stamps).
            val foldedAt = listOfNotNull(s?.updatedAtMs, d.updatedAtMs).maxOrNull()
            merged[k] = when {
                s == null -> d
                // Equal values collapse to one; the stronger claim survives (its confidence AND
                // its source — value, confidence and source travel as one claim). Tie -> the
                // survivor's source, falling back to the dying side's when the survivor has none.
                // The writer follows the winning claim VERBATIM (no ?: fallback): null means
                // "Pathline/user wrote this", which must not be re-attributed to the losing app.
                s.value.trim() == d.value.trim() ->
                    MemoryEntry(
                        foldText(s.value, d.value) ?: d.value,
                        maxOf(s.confidence, d.confidence),
                        if (d.confidence > s.confidence) d.source ?: s.source
                        else s.source ?: d.source,
                        foldedAt,
                        if (d.confidence > s.confidence) d.updatedBy else s.updatedBy,
                    )
                // Differing values concatenate oldest-first; the combined text is at most as
                // trustworthy as its weaker half, and its provenance is both origins. The composite
                // is Pathline's own making, so its writer is null (no single app wrote this text).
                else ->
                    MemoryEntry(
                        foldText(s.value, d.value) ?: d.value,
                        minOf(s.confidence, d.confidence),
                        foldSources(s.source, d.source),
                        foldedAt,
                        updatedBy = null,
                    )
            }
        }
        put(target, survivorId, AnnotationKind.MEMORY, MemoryMap.encode(merged), writer = null)
    }

    /** Upsert one annotation payload, or delete it when [content] is null. Preserves the row id.
     *  [writer] is the data-API calling package, or null for Pathline's own writes. */
    private suspend fun put(
        target: AnnotationTarget,
        id: Long,
        kind: AnnotationKind,
        content: String?,
        writer: String?,
    ) {
        if (content == null) {
            annotationDao.deleteForTargetKind(target, id, kind)
            return
        }
        val existing = annotationDao.byTarget(target, id, kind)
        if (existing == null) {
            annotationDao.upsert(
                AnnotationEntity(
                    targetType = target, targetId = id, kind = kind,
                    content = content, updatedAtMs = now(), updatedBy = writer,
                ),
            )
        } else {
            annotationDao.update(
                existing.copy(
                    content = content,
                    updatedAtMs = now(),
                    updatedBy = writer
                )
            )
        }
    }
}
