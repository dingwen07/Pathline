package net.extrawdw.apps.locationhistory.api

import androidx.sqlite.db.SimpleSQLiteQuery
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.SearchDao
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.domain.MemoryMap

/**
 * The data API's search legs: FTS5 matching (places, tags, concepts), the annotation-backed
 * matchers, the visit/trip search unions, and `fields=` validation. Extracted from
 * [PathlineProvider] (backlog #2); pairs with the already-pure [ApiSearch] (query-text
 * sanitization) and leaves enforcement to [ApiGate] — the `deny` the field validators take is the
 * route's logged-denial closure.
 *
 * Result-order contract (see [PathlineContract.QueryParams.Q]): the FTS legs come bm25-ranked and
 * the id sets here preserve that order; visit/trip search returns **chronological** rows.
 */
internal class ApiSearchEngine(
    private val searchDao: SearchDao,
    private val tagDao: TagDao,
    private val annotationDao: AnnotationDao,
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
) {

    // ---- Field validation ------------------------------------------------------------------------

    /** The validated place-search field list — defaults to everything the caller may match. */
    fun placeSearchFields(caller: Caller, rawFields: String?, deny: (String) -> Nothing): List<String> {
        val fields = ApiSearch.parseFields(rawFields)
            ?: return PLACE_DETAIL_FIELDS +
                    if (caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)) ANNOTATION_FIELDS else emptyList()
        val unknown = fields.filter { it !in PLACE_DETAIL_FIELDS && it !in ANNOTATION_FIELDS }
        require(unknown.isEmpty()) { "Unknown place search fields: $unknown" }
        if (fields.any { it in ANNOTATION_FIELDS } &&
            !caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)
        ) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        return fields
    }

    /** The validated visit/trip-search field list — defaults to everything the caller may match. */
    fun timelineSearchFields(caller: Caller, rawFields: String?, deny: (String) -> Nothing): List<String> {
        val fields = ApiSearch.parseFields(rawFields)
            ?: return listOf(PathlineContract.SearchFields.PLACE_NAME) +
                    if (caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)) TIMELINE_ANNOTATION_FIELDS
                    else emptyList()
        val valid = TIMELINE_ANNOTATION_FIELDS + PathlineContract.SearchFields.PLACE_NAME
        val unknown = fields.filter { it !in valid }
        require(unknown.isEmpty()) { "Unknown visit/trip search fields: $unknown" }
        if (fields.any { it in TIMELINE_ANNOTATION_FIELDS } &&
            !caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)
        ) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        return fields
    }

    // ---- Place search ----------------------------------------------------------------------------

    /** Union of the place ids matching [q] in each requested field (unscoped — callers intersect
     *  with what the caller may see). **Relevance-ordered**: the FTS leg comes bm25-ranked and
     *  first; the tag/note/memory legs (no score) append after in stable order. */
    suspend fun matchedPlaceIds(q: String, fields: List<String>): LinkedHashSet<Long> {
        val ids = LinkedHashSet<Long>()
        val ftsColumns = fields.filter { it in PLACE_DETAIL_FIELDS }
        if (ftsColumns.isNotEmpty()) {
            ids += searchDao.matchRowIds(
                SimpleSQLiteQuery(
                    "SELECT rowid AS id FROM places_fts WHERE places_fts MATCH ? " +
                            "ORDER BY bm25(places_fts)",
                    arrayOf(ApiSearch.ftsQuery(q, ftsColumns)),
                ),
            ).map { it.id }
        }
        if (PathlineContract.SearchFields.TAGS in fields) {
            val tagIds = ftsTagIds(q)
            if (tagIds.isNotEmpty()) {
                ids += tagDao.targetIdsForTags(AnnotationTarget.PLACE, tagIds.toList())
            }
        }
        if (PathlineContract.SearchFields.NOTES in fields) {
            // One pattern per keyword (any-of), or a single pattern for a quoted phrase.
            ApiSearch.likePatterns(q).forEach { pattern ->
                ids += annotationDao.targetIdsWithContentLike(
                    AnnotationTarget.PLACE, AnnotationKind.NOTE, pattern,
                )
            }
        }
        if (PathlineContract.SearchFields.MEMORIES in fields) {
            // Memories are stored as JSON objects; a raw LIKE would also match the structural
            // "value"/"confidence" keys, so decode and match keys + values in code instead.
            val needles = ApiSearch.needles(q)
            ids += annotationDao
                .allOfKind(AnnotationTarget.PLACE, AnnotationKind.MEMORY)
                .filter { row ->
                    MemoryMap.decode(row.content).any { (key, entry) ->
                        needles.any { needle ->
                            key.contains(needle, ignoreCase = true) ||
                                    entry.value.contains(needle, ignoreCase = true)
                        }
                    }
                }
                .map { it.targetId }
        }
        return ids
    }

    // ---- Visit / trip search -----------------------------------------------------------------------

    suspend fun searchVisits(
        q: String,
        fields: List<String>,
        start: Long,
        end: Long,
    ): List<VisitEntity> {
        val byId = LinkedHashMap<Long, VisitEntity>()
        if (PathlineContract.SearchFields.PLACE_NAME in fields) {
            val placeIds = ftsPlaceIdsByName(q)
            if (placeIds.isNotEmpty()) {
                visitDao.forPlacesOverlapping(placeIds.toList(), start, end)
                    .forEach { byId[it.id] = it }
            }
            ApiSearch.likePatterns(q).forEach { pattern ->
                visitDao.candidateNameLikeOverlapping(pattern, start, end)
                    .forEach { byId[it.id] = it }
            }
        }
        if (PathlineContract.SearchFields.TAGS in fields) {
            val tagIds = ftsTagIds(q)
            if (tagIds.isNotEmpty()) {
                val ids = tagDao.targetIdsForTags(AnnotationTarget.VISIT, tagIds.toList())
                if (ids.isNotEmpty()) {
                    visitDao.byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
                }
            }
        }
        if (PathlineContract.SearchFields.NOTES in fields) {
            val ids = ApiSearch.likePatterns(q).flatMap { pattern ->
                annotationDao.targetIdsWithContentLike(
                    AnnotationTarget.VISIT, AnnotationKind.NOTE, pattern,
                )
            }.distinct()
            if (ids.isNotEmpty()) {
                visitDao.byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
            }
        }
        return byId.values.sortedBy { it.startMs }
    }

    suspend fun searchTrips(
        q: String,
        fields: List<String>,
        start: Long,
        end: Long,
    ): List<TripEntity> {
        val byId = LinkedHashMap<Long, TripEntity>()
        if (PathlineContract.SearchFields.PLACE_NAME in fields) {
            val placeIds = ftsPlaceIdsByName(q)
            if (placeIds.isNotEmpty()) {
                tripDao.forEndpointPlacesOverlapping(placeIds.toList(), start, end)
                    .forEach { byId[it.id] = it }
            }
            ApiSearch.likePatterns(q).forEach { pattern ->
                tripDao.forEndpointCandidateNamesOverlapping(pattern, start, end)
                    .forEach { byId[it.id] = it }
            }
        }
        if (PathlineContract.SearchFields.TAGS in fields) {
            val tagIds = ftsTagIds(q)
            if (tagIds.isNotEmpty()) {
                val ids = tagDao.targetIdsForTags(AnnotationTarget.TRIP, tagIds.toList())
                if (ids.isNotEmpty()) {
                    tripDao.byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
                }
            }
        }
        if (PathlineContract.SearchFields.NOTES in fields) {
            val ids = ApiSearch.likePatterns(q).flatMap { pattern ->
                annotationDao.targetIdsWithContentLike(
                    AnnotationTarget.TRIP, AnnotationKind.NOTE, pattern,
                )
            }.distinct()
            if (ids.isNotEmpty()) {
                tripDao.byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
            }
        }
        return byId.values.sortedBy { it.startMs }
    }

    // ---- FTS id legs -----------------------------------------------------------------------------

    /** Tag ids whose display name matches [q] — **relevance-ordered** (bm25, best first). */
    suspend fun ftsTagIds(q: String): LinkedHashSet<Long> =
        searchDao.matchRowIds(
            SimpleSQLiteQuery(
                "SELECT rowid AS id FROM tags_fts WHERE tags_fts MATCH ? ORDER BY bm25(tags_fts)",
                arrayOf(ApiSearch.ftsQuery(q, listOf("displayName"))),
            ),
        ).mapTo(LinkedHashSet()) { it.id }

    /** Union of the concept ids matching [q] in name/kind/description (FTS, **bm25-ranked** and
     *  first) plus the concept's own tags/notes/memories (no score, appended in stable order) — the
     *  concept analogue of [matchedPlaceIds]. Every concept read already holds READ_ANNOTATIONS
     *  (see [PathlineProvider]'s concepts route), so the annotation legs need no extra gate. */
    suspend fun matchedConceptIds(q: String): LinkedHashSet<Long> {
        val ids = LinkedHashSet<Long>()
        ids += searchDao.matchRowIds(
            SimpleSQLiteQuery(
                "SELECT rowid AS id FROM concepts_fts WHERE concepts_fts MATCH ? " +
                        "ORDER BY bm25(concepts_fts)",
                arrayOf(ApiSearch.ftsQuery(q, listOf("displayName", "kind", "description"))),
            ),
        ).map { it.id }
        val tagIds = ftsTagIds(q)
        if (tagIds.isNotEmpty()) {
            ids += tagDao.targetIdsForTags(AnnotationTarget.CONCEPT, tagIds.toList())
        }
        ApiSearch.likePatterns(q).forEach { pattern ->
            ids += annotationDao.targetIdsWithContentLike(
                AnnotationTarget.CONCEPT, AnnotationKind.NOTE, pattern,
            )
        }
        // Memories are JSON objects; decode and match keys + values in code (see [matchedPlaceIds]).
        val needles = ApiSearch.needles(q)
        ids += annotationDao
            .allOfKind(AnnotationTarget.CONCEPT, AnnotationKind.MEMORY)
            .filter { row ->
                MemoryMap.decode(row.content).any { (key, entry) ->
                    needles.any { needle ->
                        key.contains(needle, ignoreCase = true) ||
                                entry.value.contains(needle, ignoreCase = true)
                    }
                }
            }
            .map { it.targetId }
        return ids
    }

    /** Place ids whose **name** matches [q] — the place-name leg of visit/trip search (order
     *  irrelevant there: timeline search results stay chronological). */
    suspend fun ftsPlaceIdsByName(q: String): Set<Long> =
        searchDao.matchRowIds(
            SimpleSQLiteQuery(
                "SELECT rowid AS id FROM places_fts WHERE places_fts MATCH ?",
                arrayOf(ApiSearch.ftsQuery(q, listOf(PathlineContract.SearchFields.NAME))),
            ),
        ).mapTo(HashSet()) { it.id }

    companion object {
        /** Place search fields backed by the places FTS index — also its column names. */
        val PLACE_DETAIL_FIELDS = listOf(
            PathlineContract.SearchFields.NAME,
            PathlineContract.SearchFields.ADDRESS,
            PathlineContract.SearchFields.CATEGORY,
            PathlineContract.SearchFields.TYPES,
        )

        /** Annotation-backed search fields (gated by READ_ANNOTATIONS). */
        val ANNOTATION_FIELDS = listOf(
            PathlineContract.SearchFields.TAGS,
            PathlineContract.SearchFields.NOTES,
            PathlineContract.SearchFields.MEMORIES,
        )

        /** The annotation fields applicable to visits/trips (no memories matching there — memories
         *  are key→value data, not prose; a substring hit on them would be noise). */
        val TIMELINE_ANNOTATION_FIELDS = listOf(
            PathlineContract.SearchFields.TAGS,
            PathlineContract.SearchFields.NOTES,
        )
    }
}

/** [items] re-sorted to follow [order]'s id sequence — SQL `IN` fetches lose the relevance
 *  order the FTS legs produced, so search paths restore it here. */
internal inline fun <T> sortByIdOrder(
    items: List<T>,
    order: Collection<Long>,
    crossinline id: (T) -> Long,
): List<T> {
    val rank = order.withIndex().associate { (i, v) -> v to i }
    return items.sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
}
