package net.extrawdw.apps.locationhistory.api

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Reference client** for Pathline's data API — the reader-side twin of [PathlineContract].
 *
 * Like the contract, this file is self-contained (Android + coroutines only, no app types) and is
 * meant to be copied **verbatim** into a consumer app at the same package
 * (`net.extrawdw.apps.locationhistory.api`); app-specific helpers belong in the app, not here.
 * Pathline itself never instantiates it — it lives in this repo so every contract change updates
 * the client in the same commit and consumers sync by `cp`, not by hand-merging
 * (see `docs/backlog-api-and-refactors.md` #8).
 *
 * It is a **superset**: every endpoint and column the contract documents has a method/field here,
 * whether or not a given consumer uses it. All calls are suspend (off the main thread). A missing
 * runtime permission or the user's access switch being off surfaces as a [SecurityException] from
 * [ContentResolver.query] — let it propagate and prompt. Columns newer than the installed
 * provider are read leniently (null), mirroring [PathlineContract.API_VERSION]'s
 * degrade-gracefully rule.
 */

/** A confirmed or candidate stay at a location, as returned by Pathline's `visits` endpoint. */
data class Visit(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val placeId: Long?,
    val placeName: String?,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val confidence: Float,
    val confirmed: Boolean,
    val isOngoing: Boolean,
)

/** A single-mode movement between two visits. [encodedPolyline] is null without a route tier
 *  permission (READ_TIMELINE_ROUTES or READ_LOCATION_HISTORY). */
data class Trip(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val mode: String,
    val modeConfidence: Float,
    val distanceMeters: Double,
    val encodedPolyline: String?,
    val confirmed: Boolean,
    val fromVisitId: Long?,
    val toVisitId: Long?,
)

/** A saved/known place behind one or more visits. */
data class Place(
    val id: Long,
    val name: String,
    val address: String?,
    val category: String?,
    /** Comma-separated full Google place-type list, or null. [category] is the primary one. */
    val types: String?,
    val source: String,
    val googlePlaceId: String?,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    /** Meters from the query point — populated only by [PathlineClient.placesNear], else null. */
    val distanceMeters: Double? = null,
)

/** A raw recorded location fix returned by Pathline (needs READ_LOCATION_HISTORY). */
data class Sample(
    val id: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val bearing: Float?,
    val speed: Float?,
    val provider: String?,
    val isMock: Boolean,
    val deviceState: String,
    val arActivity: String?,
    val networkTransport: String?,
    val includedInComputation: Boolean,
)

/** A tag returned by Pathline (from the global list, a search, or one target's tag row). */
data class Tag(
    val id: Long,
    /** Display spelling (most recently written). */
    val name: String,
    /** Normalized identity key — spellings differing in case/separators share it. */
    val canonicalName: String,
    val createdAtMs: Long,
    /** Whether THIS app created the tag (false = another app, null = the user in Pathline). */
    val createdByMe: Boolean?,
    /** Whether THIS app attached it to the parent target — per-target rows only, else null. */
    val attachedByMe: Boolean?,
)

/** A target's single free-text note. */
data class Note(
    val content: String,
    val updatedAtMs: Long,
    /** Whether THIS app last wrote it (false = another app, null = the user/Pathline). */
    val updatedByMe: Boolean?,
)

/** One entry of a target's memory map (the agent's KV scratchpad, with its confidence). */
data class Memory(
    val key: String,
    val value: String,
    /** The writer's confidence in this entry, in [0, 1]. */
    val confidence: Float,
    /** The writer's provenance note ("user statement", "inferred from visit:675 note"), or null. */
    val source: String?,
    /** When THIS entry was last written, or null for entries stored before per-entry stamps. */
    val updatedAtMs: Long?,
    /** Whether THIS app last wrote it (false = another app, null = Pathline/pre-attribution). */
    val updatedByMe: Boolean?,
)

/**
 * A concept — a first-class semantic group places/visits/trips join as members, carrying its own
 * kind/description plus regular annotations. Identity is [id]; the name is renameable but dedups
 * under the same canonical folding as tags.
 */
data class Concept(
    val id: Long,
    val name: String,
    val canonicalName: String,
    /** Canonicalized discriminator ("trip", "project"), or null = untyped. */
    val kind: String?,
    /** Definitional prose (what this concept IS), or null. */
    val description: String?,
    val createdAtMs: Long,
    /** When name/kind/description last changed (membership/annotations don't bump it). */
    val updatedAtMs: Long,
    val createdByMe: Boolean?,
    val updatedByMe: Boolean?,
    /** Whether THIS app attached the parent target — per-target listings only, else null. */
    val attachedByMe: Boolean?,
    /** Number of members, on every concept row. Null when the installed provider predates the
     *  column (pre-v3-batch). */
    val memberCount: Int? = null,
    /** When the concept was archived, or null = active (or pre-v3 provider). Archived concepts
     *  are omitted from listings unless asked for — see [PathlineClient.concepts]. */
    val archivedAtMs: Long? = null,
    /** Whether THIS app archived it (null = active, archived by Pathline, or pre-v3 provider). */
    val archivedByMe: Boolean? = null,
)

/** One membership row of a concept: a typed pointer at a place/visit/trip/concept. */
data class ConceptMember(
    /** `place`, `visit`, `trip` or `concept` (nested concepts are pointers too — never expanded). */
    val targetType: String,
    val targetId: Long,
    val attachedAtMs: Long,
    val attachedByMe: Boolean?,
)

/** The four things annotations attach to, mapped to their collection URIs. */
enum class AnnotationTarget(internal val collection: Uri) {
    PLACE(PathlineContract.Places.CONTENT_URI),
    VISIT(PathlineContract.Visits.CONTENT_URI),
    TRIP(PathlineContract.Trips.CONTENT_URI),
    CONCEPT(PathlineContract.Concepts.CONTENT_URI),
}

/** [apiVersion] is the provider's live contract version; null when the installed Pathline predates
 *  the column (< 3). Compare against [PathlineContract.API_VERSION]. */
data class ApiStatus(val accessEnabled: Boolean, val apiVersion: Int? = null)

/** Per-place aggregate over confirmed visits in a window (`place_stats`), most-visited first. */
data class PlaceStats(
    val placeId: Long,
    val visitCount: Int,
    /** Sum of full visit durations (overlap semantics — edge visits count whole). */
    val totalDurationMs: Long,
    val firstVisitMs: Long,
    val lastVisitMs: Long,
)

/**
 * Thin suspend client over Pathline's exported ContentProvider. Windows are `[startMs, endMs)`
 * epoch-ms pairs; a shared [group] key (a ~now epoch-ms value) tags related reads so they appear
 * as one access group in Pathline's audit log; [limit] caps rows server-side (chronological
 * collections keep the NEWEST rows, ranked/named listings the first — see
 * [PathlineContract.QueryParams.LIMIT]).
 */
class PathlineClient(private val resolver: ContentResolver) {

    // ---- Timeline & samples --------------------------------------------------------------------

    suspend fun visits(
        startMs: Long,
        endMs: Long,
        limit: Int? = null,
        group: Long? = null,
    ): List<Visit> = withContext(Dispatchers.IO) {
        query(windowUri(PathlineContract.Visits.CONTENT_URI, startMs, endMs, group, limit)) { c -> visit(c) }
    }

    /** ONE visit by id — the resolver for stored `visit:<id>` references. Null when invisible
     *  (unconfirmed horizon aside, outside the readable horizon) or nonexistent. */
    suspend fun visitById(id: Long, group: Long? = null): Visit? = withContext(Dispatchers.IO) {
        query(PathlineContract.Visits.itemUri(id).withGroup(group)) { c -> visit(c) }.firstOrNull()
    }

    suspend fun trips(
        startMs: Long,
        endMs: Long,
        limit: Int? = null,
        group: Long? = null,
    ): List<Trip> = withContext(Dispatchers.IO) {
        query(windowUri(PathlineContract.Trips.CONTENT_URI, startMs, endMs, group, limit)) { c -> trip(c) }
    }

    /** ONE trip by id — same visibility rules as [visitById]. */
    suspend fun tripById(id: Long, group: Long? = null): Trip? = withContext(Dispatchers.IO) {
        query(PathlineContract.Trips.itemUri(id).withGroup(group)) { c -> trip(c) }.firstOrNull()
    }

    /** Raw samples in the window (point-in-window semantics). Needs READ_LOCATION_HISTORY. */
    suspend fun samples(
        startMs: Long,
        endMs: Long,
        limit: Int? = null,
        group: Long? = null,
    ): List<Sample> = withContext(Dispatchers.IO) {
        query(windowUri(PathlineContract.Samples.CONTENT_URI, startMs, endMs, group, limit)) { c ->
            Sample(
                id = c.reqLong(PathlineContract.Samples.ID),
                timestampMs = c.reqLong(PathlineContract.Samples.TIMESTAMP_MS),
                latitude = c.reqDouble(PathlineContract.Samples.LATITUDE),
                longitude = c.reqDouble(PathlineContract.Samples.LONGITUDE),
                altitude = c.optDouble(PathlineContract.Samples.ALTITUDE),
                accuracy = c.optFloat(PathlineContract.Samples.ACCURACY),
                bearing = c.optFloat(PathlineContract.Samples.BEARING),
                speed = c.optFloat(PathlineContract.Samples.SPEED),
                provider = c.optString(PathlineContract.Samples.PROVIDER),
                isMock = c.reqInt(PathlineContract.Samples.IS_MOCK) == 1,
                deviceState = c.reqString(PathlineContract.Samples.DEVICE_STATE),
                arActivity = c.optString(PathlineContract.Samples.AR_ACTIVITY),
                networkTransport = c.optString(PathlineContract.Samples.NETWORK_TRANSPORT),
                includedInComputation = c.reqInt(PathlineContract.Samples.INCLUDED_IN_COMPUTATION) == 1,
            )
        }
    }

    // ---- Places --------------------------------------------------------------------------------

    /**
     * Saved-place details for [ids] (or every place this app may see when null/empty). Not
     * time-windowed. With READ_ALL_PLACES this is the whole saved-place corpus; without it, only
     * places already seen on a confirmed `visits` read come back (silently, never an error).
     */
    suspend fun places(
        ids: List<Long>? = null,
        limit: Int? = null,
        group: Long? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        val uri = PathlineContract.Places.CONTENT_URI.buildUpon()
            .apply {
                if (!ids.isNullOrEmpty()) {
                    appendQueryParameter(PathlineContract.QueryParams.IDS, ids.joinToString(","))
                }
                appendLimitAndGroup(limit, group)
            }
            .build()
        query(uri) { c -> place(c) }
    }

    /**
     * Place search: rows matching [q] in [fields] (null = every field the permissions allow; see
     * [PathlineContract.SearchFields]), most-relevant first. Needs SEARCH_DATA; coverage follows
     * READ_ALL_PLACES; annotation fields need READ_ANNOTATIONS.
     */
    suspend fun searchPlaces(
        q: String,
        fields: List<String>? = null,
        limit: Int? = null,
        group: Long? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        query(searchUri(PathlineContract.Places.CONTENT_URI, q, fields, group, limit = limit)) { c -> place(c) }
    }

    /**
     * Places within [radiusM] meters of a point, **nearest first**, each with
     * [Place.distanceMeters] populated. Same visibility scope as [places]; proximity never widens
     * it. Radius defaults to 500 m server-side and clamps at 50 km.
     */
    suspend fun placesNear(
        lat: Double,
        lng: Double,
        radiusM: Double? = null,
        limit: Int? = null,
        group: Long? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        val uri = PathlineContract.Places.CONTENT_URI.buildUpon()
            .appendQueryParameter(PathlineContract.QueryParams.NEAR, "$lat,$lng")
            .apply {
                if (radiusM != null) {
                    appendQueryParameter(PathlineContract.QueryParams.RADIUS_M, radiusM.toString())
                }
                appendLimitAndGroup(limit, group)
            }
            .build()
        query(uri) { c -> place(c) }
    }

    /**
     * A place's visit history over `[startMs, endMs)`. These are lean rows with no place name (the
     * place is [placeId]); the mapped [Visit.placeName] is therefore null. Pass `startMs = 0` for
     * the whole history (needs READ_EXTENDED_HISTORY past 30 days). A place this app hasn't been
     * granted comes back empty.
     */
    suspend fun placeVisits(
        placeId: Long,
        startMs: Long,
        endMs: Long,
        limit: Int? = null,
        group: Long? = null,
    ): List<Visit> = withContext(Dispatchers.IO) {
        val h = PathlineContract.Places.VisitHistory
        query(
            windowUri(PathlineContract.Places.visitHistoryUri(placeId), startMs, endMs, group, limit),
        ) { c ->
            Visit(
                id = c.reqLong(h.ID),
                startMs = c.reqLong(h.START_MS),
                endMs = c.reqLong(h.END_MS),
                placeId = c.optLong(h.PLACE_ID),
                placeName = null,
                latitude = c.reqDouble(h.LATITUDE),
                longitude = c.reqDouble(h.LONGITUDE),
                radiusMeters = c.reqDouble(h.RADIUS_METERS),
                confidence = c.reqFloat(h.CONFIDENCE),
                confirmed = c.reqInt(h.CONFIRMED) == 1,
                isOngoing = c.reqInt(h.IS_ONGOING) == 1,
            )
        }
    }

    /**
     * Per-place visit aggregates over `[startMs, endMs)` — the one-call backend for "most visited"
     * questions. Already most-visited-first; scoped to this app's granted places; [ids] filters to
     * specific places.
     */
    suspend fun placeStats(
        startMs: Long,
        endMs: Long,
        ids: List<Long>? = null,
        limit: Int? = null,
        group: Long? = null,
    ): List<PlaceStats> = withContext(Dispatchers.IO) {
        val s = PathlineContract.PlaceStats
        val uri = windowUri(s.CONTENT_URI, startMs, endMs, group, limit).buildUpon()
            .apply {
                if (!ids.isNullOrEmpty()) {
                    appendQueryParameter(PathlineContract.QueryParams.IDS, ids.joinToString(","))
                }
            }
            .build()
        query(uri) { c ->
            PlaceStats(
                placeId = c.reqLong(s.PLACE_ID),
                visitCount = c.reqInt(s.VISIT_COUNT),
                totalDurationMs = c.reqLong(s.TOTAL_DURATION_MS),
                firstVisitMs = c.reqLong(s.FIRST_VISIT_MS),
                lastVisitMs = c.reqLong(s.LAST_VISIT_MS),
            )
        }
    }

    // ---- Search over visits / trips ---------------------------------------------------------------

    /**
     * Visit search over saved-place name / tags / notes. A null [startMs] searches the whole history
     * the permissions allow (clamps without READ_EXTENDED_HISTORY); an explicit window is enforced
     * exactly like a plain read. Results stay chronological.
     */
    suspend fun searchVisits(
        q: String,
        fields: List<String>? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        limit: Int? = null,
        group: Long? = null,
    ): List<Visit> = withContext(Dispatchers.IO) {
        query(
            searchUri(PathlineContract.Visits.CONTENT_URI, q, fields, group, startMs, endMs, limit),
        ) { c -> visit(c) }
    }

    /** Trip search over endpoint place name / tags / notes — same window semantics as [searchVisits]. */
    suspend fun searchTrips(
        q: String,
        fields: List<String>? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        limit: Int? = null,
        group: Long? = null,
    ): List<Trip> = withContext(Dispatchers.IO) {
        query(
            searchUri(PathlineContract.Trips.CONTENT_URI, q, fields, group, startMs, endMs, limit),
        ) { c -> trip(c) }
    }

    // ---- Tags & per-target annotations -----------------------------------------------------------

    /**
     * Every tag on a target this app can see — or, with [q], the ones whose name matches it
     * (most-relevant first). Needs READ_ANNOTATIONS (+ SEARCH_DATA when searching).
     */
    suspend fun tags(q: String? = null, limit: Int? = null, group: Long? = null): List<Tag> =
        withContext(Dispatchers.IO) {
            val uri =
                if (q == null) PathlineContract.Tags.CONTENT_URI.buildUpon()
                    .apply { appendLimitAndGroup(limit, group) }.build()
                else searchUri(PathlineContract.Tags.CONTENT_URI, q, fields = null, group = group, limit = limit)
            query(uri) { c -> tag(c) }
        }

    // Per-target annotations (tags / note / memories). Reads need READ_ANNOTATIONS; an invisible
    // target simply comes back empty. Writes need WRITE_ANNOTATIONS (tags, memories) or
    // WRITE_ANNOTATIONS_NOTES (notes) and a visible, confirmed target.

    suspend fun tagsFor(target: AnnotationTarget, id: Long, group: Long? = null): List<Tag> =
        withContext(Dispatchers.IO) {
            query(PathlineContract.Annotations.tagsUri(target.collection, id).withGroup(group)) { c -> tag(c) }
        }

    suspend fun noteFor(target: AnnotationTarget, id: Long, group: Long? = null): Note? =
        withContext(Dispatchers.IO) {
            query(PathlineContract.Annotations.notesUri(target.collection, id).withGroup(group)) { c ->
                Note(
                    content = c.reqString(PathlineContract.Annotations.Notes.CONTENT),
                    updatedAtMs = c.reqLong(PathlineContract.Annotations.Notes.UPDATED_AT_MS),
                    updatedByMe = c.optBool(PathlineContract.Annotations.Notes.UPDATED_BY_ME),
                )
            }.firstOrNull()
        }

    /** The target's whole memory map — one call, one row per key. */
    suspend fun memoriesFor(target: AnnotationTarget, id: Long, group: Long? = null): List<Memory> =
        withContext(Dispatchers.IO) {
            query(PathlineContract.Annotations.memoriesUri(target.collection, id).withGroup(group)) { c ->
                Memory(
                    key = c.reqString(PathlineContract.Annotations.Memories.KEY),
                    value = c.reqString(PathlineContract.Annotations.Memories.VALUE),
                    confidence = c.reqFloat(PathlineContract.Annotations.Memories.CONFIDENCE),
                    source = c.optString(PathlineContract.Annotations.Memories.SOURCE),
                    updatedAtMs = c.optLong(PathlineContract.Annotations.Memories.UPDATED_AT_MS),
                    updatedByMe = c.optBool(PathlineContract.Annotations.Memories.UPDATED_BY_ME),
                )
            }
        }

    /** Apply [name] as a tag (any spelling; re-applying refreshes the spelling). */
    suspend fun applyTag(target: AnnotationTarget, id: Long, name: String) = withContext(Dispatchers.IO) {
        resolver.insert(
            PathlineContract.Annotations.tagsUri(target.collection, id),
            ContentValues().apply { put(PathlineContract.Tags.NAME, name) },
        )
        Unit
    }

    /** Remove the [name] tag from the target; true when a link was actually removed. */
    suspend fun removeTag(target: AnnotationTarget, id: Long, name: String): Boolean =
        withContext(Dispatchers.IO) {
            resolver.delete(PathlineContract.Annotations.tagUri(target.collection, id, name), null, null) > 0
        }

    /** Replace the target's note (blank clears it). */
    suspend fun setNote(target: AnnotationTarget, id: Long, content: String) = withContext(Dispatchers.IO) {
        resolver.insert(
            PathlineContract.Annotations.notesUri(target.collection, id),
            ContentValues().apply { put(PathlineContract.Annotations.Notes.CONTENT, content) },
        )
        Unit
    }

    /** Clear the target's note; true when there was one to remove. */
    suspend fun clearNote(target: AnnotationTarget, id: Long): Boolean = withContext(Dispatchers.IO) {
        resolver.delete(PathlineContract.Annotations.notesUri(target.collection, id), null, null) > 0
    }

    /** Put one memory entry; [confidence] in [0,1] (omit for 1.0 — stated as fact); [source] is
     *  the writer's optional provenance note. */
    suspend fun putMemory(
        target: AnnotationTarget,
        id: Long,
        key: String,
        value: String,
        confidence: Float? = null,
        source: String? = null,
    ) = withContext(Dispatchers.IO) {
        resolver.insert(
            PathlineContract.Annotations.memoriesUri(target.collection, id),
            ContentValues().apply {
                put(PathlineContract.Annotations.Memories.KEY, key)
                put(PathlineContract.Annotations.Memories.VALUE, value)
                if (confidence != null) put(PathlineContract.Annotations.Memories.CONFIDENCE, confidence)
                if (source != null) put(PathlineContract.Annotations.Memories.SOURCE, source)
            },
        )
        Unit
    }

    /** Remove one memory entry; true when the key existed. */
    suspend fun removeMemory(target: AnnotationTarget, id: Long, key: String): Boolean =
        withContext(Dispatchers.IO) {
            resolver.delete(PathlineContract.Annotations.memoryUri(target.collection, id, key), null, null) > 0
        }

    /** Clear the target's whole memory map; returns how many keys were removed. */
    suspend fun clearMemories(target: AnnotationTarget, id: Long): Int = withContext(Dispatchers.IO) {
        resolver.delete(PathlineContract.Annotations.memoriesUri(target.collection, id), null, null)
    }

    // ---- Concepts (reads need READ_ANNOTATIONS only; writes need WRITE_ANNOTATIONS) -------------

    /** Every concept; [kind] filters exactly (canonicalized), [q] searches name/kind/description
     *  (most-relevant first). Archived concepts are omitted unless [archived] says otherwise —
     *  one of [PathlineContract.QueryParams.ARCHIVED_INCLUDE] / [ARCHIVED_ONLY][PathlineContract.QueryParams.ARCHIVED_ONLY]. */
    suspend fun concepts(
        kind: String? = null,
        q: String? = null,
        limit: Int? = null,
        group: Long? = null,
        archived: String? = null,
    ): List<Concept> = withContext(Dispatchers.IO) {
        val base =
            if (q == null) PathlineContract.Concepts.CONTENT_URI.buildUpon()
                .apply { appendLimitAndGroup(limit, group) }.build()
            else searchUri(PathlineContract.Concepts.CONTENT_URI, q, fields = null, group = group, limit = limit)
        var uri =
            if (kind == null) base
            else base.buildUpon()
                .appendQueryParameter(PathlineContract.QueryParams.KIND, kind).build()
        if (archived != null) {
            uri = uri.buildUpon()
                .appendQueryParameter(PathlineContract.QueryParams.ARCHIVED, archived).build()
        }
        query(uri) { c -> concept(c) }
    }

    suspend fun conceptById(id: Long, group: Long? = null): Concept? = withContext(Dispatchers.IO) {
        query(PathlineContract.Concepts.itemUri(id).withGroup(group)) { c -> concept(c) }.firstOrNull()
    }

    /** The concept's members (typed id pointers; resolve them via the regular collections). */
    suspend fun conceptMembers(id: Long, group: Long? = null): List<ConceptMember> =
        withContext(Dispatchers.IO) {
            val m = PathlineContract.Concepts.Members
            query(PathlineContract.Concepts.membersUri(id).withGroup(group)) { c ->
                ConceptMember(
                    targetType = c.reqString(m.TARGET_TYPE),
                    targetId = c.reqLong(m.TARGET_ID),
                    attachedAtMs = c.reqLong(m.ATTACHED_AT_MS),
                    attachedByMe = c.optBool(m.ATTACHED_BY_ME),
                )
            }
        }

    /** The concepts a place/visit/trip — or, for nesting, a concept — belongs to (with
     *  [Concept.attachedByMe] populated). Archived containers are omitted unless [archived]
     *  overrides, like [concepts]. */
    suspend fun conceptsFor(
        target: AnnotationTarget,
        id: Long,
        group: Long? = null,
        archived: String? = null,
    ): List<Concept> =
        withContext(Dispatchers.IO) {
            var uri = PathlineContract.Concepts.forTargetUri(target.collection, id).withGroup(group)
            if (archived != null) {
                uri = uri.buildUpon()
                    .appendQueryParameter(PathlineContract.QueryParams.ARCHIVED, archived).build()
            }
            query(uri) { c -> concept(c) }
        }

    /** Create a concept; returns its id. A canonical-name collision throws with the existing id. */
    suspend fun createConcept(name: String, kind: String? = null, description: String? = null): Long =
        withContext(Dispatchers.IO) {
            val uri = resolver.insert(
                PathlineContract.Concepts.CONTENT_URI,
                ContentValues().apply {
                    put(PathlineContract.Concepts.NAME, name)
                    if (kind != null) put(PathlineContract.Concepts.KIND, kind)
                    if (description != null) put(PathlineContract.Concepts.DESCRIPTION, description)
                },
            )
            uri?.lastPathSegment?.toLongOrNull() ?: error("concept create returned no id")
        }

    /** Partial intrinsic edit: only non-null [name] / present flags change; explicit clears pass
     *  [clearKind]/[clearDescription]. True when the row existed. */
    suspend fun updateConcept(
        id: Long,
        name: String? = null,
        kind: String? = null,
        clearKind: Boolean = false,
        description: String? = null,
        clearDescription: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (name != null) put(PathlineContract.Concepts.NAME, name)
            if (kind != null) put(PathlineContract.Concepts.KIND, kind)
            else if (clearKind) putNull(PathlineContract.Concepts.KIND)
            if (description != null) put(PathlineContract.Concepts.DESCRIPTION, description)
            else if (clearDescription) putNull(PathlineContract.Concepts.DESCRIPTION)
        }
        resolver.update(PathlineContract.Concepts.itemUri(id), values, null, null) > 0
    }

    /** Delete a concept (members survive, its annotations don't); true when it existed. Prefer
     *  [setConceptArchived] when the user may want it back. */
    suspend fun deleteConcept(id: Long): Boolean = withContext(Dispatchers.IO) {
        resolver.delete(PathlineContract.Concepts.itemUri(id), null, null) > 0
    }

    /** Archive ([archived] = true) or unarchive a concept — a visibility flag, not a delete (see
     *  [Concept.archivedAtMs]). Already-in-state is a no-op. True when the row existed. */
    suspend fun setConceptArchived(id: Long, archived: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(PathlineContract.Concepts.ARCHIVED, archived)
            }
            resolver.update(PathlineContract.Concepts.itemUri(id), values, null, null) > 0
        }

    /** Attach a place/visit/trip — or, nesting, another concept — to a concept (re-adding is a
     *  no-op). A concept member that would close a membership cycle throws. */
    suspend fun addConceptMember(conceptId: Long, target: AnnotationTarget, targetId: Long) =
        withContext(Dispatchers.IO) {
            resolver.insert(
                PathlineContract.Concepts.membersUri(conceptId),
                ContentValues().apply {
                    put(PathlineContract.Concepts.Members.TARGET_TYPE, target.name.lowercase())
                    put(PathlineContract.Concepts.Members.TARGET_ID, targetId)
                },
            )
            Unit
        }

    /** Detach a member; true when it was attached. */
    suspend fun removeConceptMember(conceptId: Long, target: AnnotationTarget, targetId: Long): Boolean =
        withContext(Dispatchers.IO) {
            resolver.delete(
                PathlineContract.Concepts.memberUri(conceptId, target.name.lowercase(), targetId),
                null, null,
            ) > 0
        }

    // ---- Status --------------------------------------------------------------------------------

    /** Always answerable; null only if the provider can't be reached at all (Pathline not installed). */
    suspend fun status(): ApiStatus? = withContext(Dispatchers.IO) {
        resolver.query(PathlineContract.Status.CONTENT_URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            // api_version is absent on providers older than contract v3 — read it leniently.
            val versionCol = c.getColumnIndex(PathlineContract.Status.API_VERSION)
            ApiStatus(
                accessEnabled = c.reqInt(PathlineContract.Status.ACCESS_ENABLED) == 1,
                apiVersion = if (versionCol >= 0 && !c.isNull(versionCol)) c.getInt(versionCol) else null,
            )
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private inline fun <T> query(uri: Uri, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        resolver.query(uri, null, null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    private fun windowUri(base: Uri, startMs: Long, endMs: Long, group: Long?, limit: Int? = null): Uri =
        base.buildUpon()
            .appendQueryParameter(PathlineContract.QueryParams.START, startMs.toString())
            .appendQueryParameter(PathlineContract.QueryParams.END, endMs.toString())
            .apply { appendLimitAndGroup(limit, group) }
            .build()

    /** A collection URI in search mode: `q` plus the optional `fields`, window, cap and `group`. */
    private fun searchUri(
        base: Uri,
        q: String,
        fields: List<String>?,
        group: Long?,
        startMs: Long? = null,
        endMs: Long? = null,
        limit: Int? = null,
    ): Uri = base.buildUpon()
        .appendQueryParameter(PathlineContract.QueryParams.Q, q)
        .apply {
            if (!fields.isNullOrEmpty()) {
                appendQueryParameter(PathlineContract.QueryParams.FIELDS, fields.joinToString(","))
            }
            if (startMs != null) appendQueryParameter(PathlineContract.QueryParams.START, startMs.toString())
            if (endMs != null) appendQueryParameter(PathlineContract.QueryParams.END, endMs.toString())
            appendLimitAndGroup(limit, group)
        }
        .build()

    private fun Uri.Builder.appendLimitAndGroup(limit: Int?, group: Long?): Uri.Builder = apply {
        if (limit != null) appendQueryParameter(PathlineContract.QueryParams.LIMIT, limit.toString())
        if (group != null) appendQueryParameter(PathlineContract.QueryParams.GROUP, group.toString())
    }

    private fun Uri.withGroup(group: Long?): Uri =
        if (group == null) this
        else buildUpon().appendQueryParameter(PathlineContract.QueryParams.GROUP, group.toString()).build()

    // -- shared row mappers (plain reads and searches return the same row shapes) --

    private fun visit(c: Cursor) = Visit(
        id = c.reqLong(PathlineContract.Visits.ID),
        startMs = c.reqLong(PathlineContract.Visits.START_MS),
        endMs = c.reqLong(PathlineContract.Visits.END_MS),
        placeId = c.optLong(PathlineContract.Visits.PLACE_ID),
        placeName = c.optString(PathlineContract.Visits.PLACE_NAME),
        latitude = c.reqDouble(PathlineContract.Visits.LATITUDE),
        longitude = c.reqDouble(PathlineContract.Visits.LONGITUDE),
        radiusMeters = c.reqDouble(PathlineContract.Visits.RADIUS_METERS),
        confidence = c.reqFloat(PathlineContract.Visits.CONFIDENCE),
        confirmed = c.reqInt(PathlineContract.Visits.CONFIRMED) == 1,
        isOngoing = c.reqInt(PathlineContract.Visits.IS_ONGOING) == 1,
    )

    private fun trip(c: Cursor) = Trip(
        id = c.reqLong(PathlineContract.Trips.ID),
        startMs = c.reqLong(PathlineContract.Trips.START_MS),
        endMs = c.reqLong(PathlineContract.Trips.END_MS),
        mode = c.reqString(PathlineContract.Trips.MODE),
        modeConfidence = c.reqFloat(PathlineContract.Trips.MODE_CONFIDENCE),
        distanceMeters = c.reqDouble(PathlineContract.Trips.DISTANCE_METERS),
        encodedPolyline = c.optString(PathlineContract.Trips.ENCODED_POLYLINE),
        confirmed = c.reqInt(PathlineContract.Trips.CONFIRMED) == 1,
        fromVisitId = c.optLong(PathlineContract.Trips.FROM_VISIT_ID),
        toVisitId = c.optLong(PathlineContract.Trips.TO_VISIT_ID),
    )

    private fun place(c: Cursor) = Place(
        id = c.reqLong(PathlineContract.Places.ID),
        name = c.reqString(PathlineContract.Places.NAME),
        address = c.optString(PathlineContract.Places.ADDRESS),
        category = c.optString(PathlineContract.Places.CATEGORY),
        types = c.optString(PathlineContract.Places.TYPES),
        source = c.reqString(PathlineContract.Places.SOURCE),
        googlePlaceId = c.optString(PathlineContract.Places.GOOGLE_PLACE_ID),
        latitude = c.reqDouble(PathlineContract.Places.LATITUDE),
        longitude = c.reqDouble(PathlineContract.Places.LONGITUDE),
        radiusMeters = c.reqDouble(PathlineContract.Places.RADIUS_METERS),
        // Lenient: the column is absent on pre-proximity providers (API_VERSION < 3 batch).
        distanceMeters = c.lenientDouble(PathlineContract.Places.DISTANCE_M),
    )

    private fun tag(c: Cursor) = Tag(
        id = c.reqLong(PathlineContract.Tags.ID),
        name = c.reqString(PathlineContract.Tags.NAME),
        canonicalName = c.reqString(PathlineContract.Tags.CANONICAL_NAME),
        createdAtMs = c.reqLong(PathlineContract.Tags.CREATED_AT_MS),
        createdByMe = c.optBool(PathlineContract.Tags.CREATED_BY_ME),
        attachedByMe = c.optBool(PathlineContract.Tags.ATTACHED_BY_ME),
    )

    private fun concept(c: Cursor) = Concept(
        id = c.reqLong(PathlineContract.Concepts.ID),
        name = c.reqString(PathlineContract.Concepts.NAME),
        canonicalName = c.reqString(PathlineContract.Concepts.CANONICAL_NAME),
        kind = c.optString(PathlineContract.Concepts.KIND),
        description = c.optString(PathlineContract.Concepts.DESCRIPTION),
        createdAtMs = c.reqLong(PathlineContract.Concepts.CREATED_AT_MS),
        updatedAtMs = c.reqLong(PathlineContract.Concepts.UPDATED_AT_MS),
        createdByMe = c.optBool(PathlineContract.Concepts.CREATED_BY_ME),
        updatedByMe = c.optBool(PathlineContract.Concepts.UPDATED_BY_ME),
        attachedByMe = c.optBool(PathlineContract.Concepts.ATTACHED_BY_ME),
        // Lenient: absent on providers older than the v3 batch.
        memberCount = c.lenientInt(PathlineContract.Concepts.MEMBER_COUNT),
        // Lenient: absent on providers older than the v3 batch.
        archivedAtMs = c.lenientLong(PathlineContract.Concepts.ARCHIVED_AT_MS),
        archivedByMe = c.lenientBool(PathlineContract.Concepts.ARCHIVED_BY_ME),
    )

    // -- small cursor helpers, by column name --

    private fun Cursor.reqLong(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.reqInt(col: String) = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.reqFloat(col: String) = getFloat(getColumnIndexOrThrow(col))
    private fun Cursor.reqDouble(col: String) = getDouble(getColumnIndexOrThrow(col))
    private fun Cursor.reqString(col: String) = getString(getColumnIndexOrThrow(col)) ?: ""

    private fun Cursor.optLong(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.optFloat(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getFloat(it) }

    private fun Cursor.optDouble(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }

    private fun Cursor.optString(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.optBool(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) != 0 }

    /** Like the opt* readers but tolerant of the column not existing at all (older provider). */
    private fun Cursor.lenientInt(col: String) =
        getColumnIndex(col).let { if (it >= 0 && !isNull(it)) getInt(it) else null }

    private fun Cursor.lenientLong(col: String) =
        getColumnIndex(col).let { if (it >= 0 && !isNull(it)) getLong(it) else null }

    private fun Cursor.lenientBool(col: String) =
        getColumnIndex(col).let { if (it >= 0 && !isNull(it)) getInt(it) != 0 else null }

    private fun Cursor.lenientDouble(col: String) =
        getColumnIndex(col).let { if (it >= 0 && !isNull(it)) getDouble(it) else null }
}
