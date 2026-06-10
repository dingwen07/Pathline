package net.extrawdw.apps.locationhistory.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.ApiAccessDao
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantDao
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantEntity
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.SearchDao
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TagEntity
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.MemoryMap
import net.extrawdw.apps.locationhistory.work.WorkScheduler

/**
 * On-device API exposing the user's timeline, recorded samples, saved places and annotations
 * (tags / notes / memories) to other apps, with full-text search over places and tags.
 *
 * See [PathlineContract] for the public surface (URIs, columns, permissions, query semantics). The
 * provider is exported but every collection enforces a custom runtime permission on the calling app,
 * and any window reaching past [PathlineContract.EXTENDED_HISTORY_WINDOW_MS] additionally requires
 * the extended-history permission (explicit windows throw without it; windowless reads clamp).
 * The provider is read-only **except** the annotation sub-collections, where insert/update/delete
 * implement the scoped write surface of [PathlineContract.Annotations]; every other URI still throws.
 *
 * Hilt cannot inject a [ContentProvider] directly (it is created before the Hilt component), so the
 * DAOs are pulled lazily from the application's [EntryPoint] on first query — by which time the app
 * is fully initialized. The DAO calls are `suspend`; [query] runs on a binder thread and bridges
 * them with [runBlocking], which is appropriate here since the IPC call is already synchronous.
 */
class PathlineProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DaoEntryPoint {
        fun visitDao(): VisitDao
        fun tripDao(): TripDao
        fun locationSampleDao(): LocationSampleDao
        fun placeDao(): PlaceDao
        fun tagDao(): TagDao
        fun annotationDao(): AnnotationDao
        fun searchDao(): SearchDao
        fun annotationStore(): AnnotationStore
        fun apiAccessDao(): ApiAccessDao
        fun apiPlaceGrantDao(): ApiPlaceGrantDao
        fun settingsRepository(): SettingsRepository
        fun workScheduler(): WorkScheduler
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        val visits = PathlineContract.Visits.PATH
        val trips = PathlineContract.Trips.PATH
        val places = PathlineContract.Places.PATH
        val tags = PathlineContract.Annotations.TAGS_PATH
        val notes = PathlineContract.Annotations.NOTES_PATH
        val memories = PathlineContract.Annotations.MEMORIES_PATH

        addURI(PathlineContract.AUTHORITY, visits, CODE_VISITS)
        addURI(PathlineContract.AUTHORITY, trips, CODE_TRIPS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Samples.PATH, CODE_SAMPLES)
        addURI(PathlineContract.AUTHORITY, places, CODE_PLACES)
        addURI(PathlineContract.AUTHORITY, "$places/#/${PathlineContract.Places.VISITS_PATH}", CODE_PLACE_VISITS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Status.PATH, CODE_STATUS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Tags.PATH, CODE_TAGS)

        // Annotation sub-collections of one place/visit/trip, plus the single-item forms used by
        // delete. (`places/#/visits` above predates these; the tags/notes/memories names can't
        // collide with it.)
        addURI(PathlineContract.AUTHORITY, "$places/#/$tags", CODE_PLACE_TAGS)
        addURI(PathlineContract.AUTHORITY, "$visits/#/$tags", CODE_VISIT_TAGS)
        addURI(PathlineContract.AUTHORITY, "$trips/#/$tags", CODE_TRIP_TAGS)
        addURI(PathlineContract.AUTHORITY, "$places/#/$tags/*", CODE_PLACE_TAG_NAME)
        addURI(PathlineContract.AUTHORITY, "$visits/#/$tags/*", CODE_VISIT_TAG_NAME)
        addURI(PathlineContract.AUTHORITY, "$trips/#/$tags/*", CODE_TRIP_TAG_NAME)
        addURI(PathlineContract.AUTHORITY, "$places/#/$notes", CODE_PLACE_NOTES)
        addURI(PathlineContract.AUTHORITY, "$visits/#/$notes", CODE_VISIT_NOTES)
        addURI(PathlineContract.AUTHORITY, "$trips/#/$notes", CODE_TRIP_NOTES)
        addURI(PathlineContract.AUTHORITY, "$places/#/$memories", CODE_PLACE_MEMORIES)
        addURI(PathlineContract.AUTHORITY, "$visits/#/$memories", CODE_VISIT_MEMORIES)
        addURI(PathlineContract.AUTHORITY, "$trips/#/$memories", CODE_TRIP_MEMORIES)
        addURI(PathlineContract.AUTHORITY, "$places/#/$memories/*", CODE_PLACE_MEMORY_KEY)
        addURI(PathlineContract.AUTHORITY, "$visits/#/$memories/*", CODE_VISIT_MEMORY_KEY)
        addURI(PathlineContract.AUTHORITY, "$trips/#/$memories/*", CODE_TRIP_MEMORY_KEY)
    }

    private val entryPoint: DaoEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context!!.applicationContext, DaoEntryPoint::class.java,
        )
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_VISITS -> PathlineContract.Visits.CONTENT_TYPE
        CODE_TRIPS -> PathlineContract.Trips.CONTENT_TYPE
        CODE_SAMPLES -> PathlineContract.Samples.CONTENT_TYPE
        CODE_PLACES -> PathlineContract.Places.CONTENT_TYPE
        CODE_PLACE_VISITS -> PathlineContract.Places.VisitHistory.CONTENT_TYPE
        CODE_STATUS -> PathlineContract.Status.CONTENT_TYPE
        CODE_TAGS, in TAGS_CODES -> PathlineContract.Tags.CONTENT_TYPE
        in TAG_NAME_CODES -> ITEM_TYPE_TAG
        in NOTES_CODES -> PathlineContract.Annotations.Notes.CONTENT_TYPE
        in MEMORIES_CODES -> PathlineContract.Annotations.Memories.CONTENT_TYPE
        in MEMORY_KEY_CODES -> ITEM_TYPE_MEMORY
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val code = matcher.match(uri)
        if (code == UriMatcher.NO_MATCH) {
            throw IllegalArgumentException("Unknown URI: $uri")
        }

        val now = System.currentTimeMillis()

        // The status route is always answerable: it reports the access switch + the caller's grants,
        // returns no personal data, and is exempt from the switch and the audit log.
        if (code == CODE_STATUS) return statusCursor()

        // The single access switch gates EVERYTHING below. When off, no per-app permission matters:
        // every data read is denied. The denial is logged (so the audit trail is honest) but does NOT
        // notify the user — an app reading while the user has turned the whole API off is not a
        // per-app breach worth alerting on. We attribute it to the install-time API gate.
        if (!apiAccessEnabled()) {
            logAccess(
                dataTypeFor(code), now, now, rowCount = 0, now, parseGroup(uri, now),
                routeWithheld = null,
                deniedPermission = PathlineContract.Permissions.API,
                notify = false,
            )
            throw SecurityException("Third-party access to Pathline data is turned off")
        }

        // The windowless collections (places, tags, per-target annotations) branch off before the
        // required-`start` parsing below; so do searches, where `start` becomes optional.
        when (code) {
            CODE_PLACES -> return placesQuery(uri, now)
            CODE_PLACE_VISITS -> return placeVisitsQuery(uri, now)
            CODE_TAGS -> return tagsQuery(uri, now)
            in TAGS_CODES -> return targetTagsQuery(uri, targetFor(code), now)
            in NOTES_CODES -> return targetNotesQuery(uri, targetFor(code), now)
            in MEMORIES_CODES -> return targetMemoriesQuery(uri, targetFor(code), now)
            // The single-item annotation URIs exist for delete only.
            in TAG_NAME_CODES, in MEMORY_KEY_CODES ->
                throw IllegalArgumentException("Not a queryable URI (delete-only): $uri")
        }
        if ((code == CODE_VISITS || code == CODE_TRIPS) &&
            uri.getQueryParameter(PathlineContract.QueryParams.Q) != null
        ) {
            return timelineSearchQuery(uri, code, now)
        }

        val start = uri.getQueryParameter(PathlineContract.QueryParams.START)?.toLongOrNull()
            ?: throw IllegalArgumentException(
                "Missing required '${PathlineContract.QueryParams.START}' query parameter (epoch ms)",
            )
        val end = uri.getQueryParameter(PathlineContract.QueryParams.END)?.toLongOrNull() ?: now
        require(end > start) { "'${PathlineContract.QueryParams.END}' must be greater than '${PathlineContract.QueryParams.START}'" }

        val dataType = uri.lastPathSegment ?: "?"
        val basePermission = when (code) {
            CODE_VISITS, CODE_TRIPS -> PathlineContract.Permissions.READ_TIMELINE
            CODE_SAMPLES -> PathlineContract.Permissions.READ_LOCATION_HISTORY
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }

        // Optional batch-correlation key — honoured only when ~now (fail-open: stale/invalid -> null,
        // never an error). Parsed before enforcement so a denied read is grouped too. Reads sharing it
        // from one app are aggregated in the access manager.
        val groupId = parseGroup(uri, now)

        // Enforce each required permission. A well-formed request the caller isn't authorized for is
        // recorded as a denied event (read nothing -> rowCount 0, no user notification) and then
        // rejected; the window is never silently narrowed.
        fun requirePermission(permission: String) {
            if (holds(permission)) return
            logAccess(
                dataType,
                start,
                end,
                rowCount = 0,
                now,
                groupId,
                routeWithheld = null,
                deniedPermission = permission
            )
            throw SecurityException("Caller must hold $permission")
        }
        requirePermission(basePermission)
        if (start < now - PathlineContract.EXTENDED_HISTORY_WINDOW_MS) {
            requirePermission(PathlineContract.Permissions.READ_EXTENDED_HISTORY)
        }

        // A trip's encoded route is the raw movement path, so it sits behind the location-trail tier:
        // a timeline-only caller still gets trip rows but with the polyline column nulled. Either the
        // route permission or full location-history unlocks it (a sample reader can rebuild the path).
        val routeWithheld: Boolean? = if (code == CODE_TRIPS) !routeUnlocked() else null

        val cursor = when (code) {
            CODE_VISITS -> visitsCursor(start, end)
            CODE_TRIPS -> tripsCursor(start, end, includeRoute = routeWithheld == false)
            CODE_SAMPLES -> samplesCursor(start, end)
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        logAccess(
            dataType,
            start,
            end,
            cursor.count,
            now,
            groupId,
            routeWithheld,
            deniedPermission = null
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** Append one row to the audit log so the in-app access manager and warnings can surface it.
     *  Best-effort: a logging failure must never break a caller's read. */
    private fun logAccess(
        dataType: String,
        startMs: Long,
        endMs: Long,
        rowCount: Int,
        nowMs: Long,
        groupId: Long?,
        routeWithheld: Boolean?,
        deniedPermission: String?,
        notify: Boolean = true,
        isWrite: Boolean = false,
    ) {
        val pkg = callingPackage ?: "unknown"
        runCatching {
            runBlocking {
                entryPoint.apiAccessDao().insert(
                    ApiAccessEventEntity(
                        packageName = pkg,
                        dataType = dataType,
                        startMs = startMs,
                        endMs = endMs,
                        rowCount = rowCount,
                        timestampMs = nowMs,
                        groupId = groupId,
                        routeWithheld = routeWithheld,
                        deniedPermission = deniedPermission,
                        isWrite = isWrite,
                    ),
                )
            }
        }
        // Alert the user about this access — a successful read/write OR a denied (unauthorized)
        // attempt, on separate per-app back-off lanes. Coalesced + rate-limited by WorkManager / the
        // worker. Skipped (logged-only) for denials while the whole API is switched off — see [query].
        if (!notify) return
        runCatching {
            entryPoint.workScheduler()
                .enqueueApiAccessNotification(pkg, denied = deniedPermission != null)
        }
    }

    // ---- Timeline & samples ------------------------------------------------------------------

    private fun visitsCursor(start: Long, end: Long): Cursor = runBlocking {
        buildVisitsCursor(entryPoint.visitDao().overlapping(start, end))
    }

    /** [PathlineContract.Visits] rows for [visits], resolving place names and recording grants. */
    private suspend fun buildVisitsCursor(visits: List<VisitEntity>): Cursor {
        val placeDao = entryPoint.placeDao()
        // Resolve place names once per distinct place (visit counts in a window are small).
        val names = HashMap<Long, String?>()
        val cursor = MatrixCursor(PathlineContract.Visits.COLUMNS, visits.size)
        for (v in visits) {
            val resolvedName = v.placeId?.let { id ->
                names.getOrPut(id) { placeDao.byId(id)?.name }
            } ?: v.candidateName
            cursor.addRow(
                arrayOf<Any?>(
                    v.id,
                    v.startMs,
                    v.endMs,
                    v.placeId,
                    resolvedName,
                    v.centroidLatitude,
                    v.centroidLongitude,
                    v.radiusMeters,
                    v.confidence,
                    if (v.confirmed) 1 else 0,
                    if (v.isOngoing) 1 else 0,
                ),
            )
        }
        // Record that this caller has now seen these saved places, so it may later resolve their
        // details and history via the `places` collection. Best-effort: never break the read.
        runCatching { recordPlaceGrants(visits) }
        return cursor
    }

    private fun tripsCursor(start: Long, end: Long, includeRoute: Boolean): Cursor = runBlocking {
        buildTripsCursor(entryPoint.tripDao().overlapping(start, end), includeRoute)
    }

    private fun buildTripsCursor(trips: List<TripEntity>, includeRoute: Boolean): Cursor {
        val cursor = MatrixCursor(PathlineContract.Trips.COLUMNS, trips.size)
        for (t in trips) {
            cursor.addRow(
                arrayOf<Any?>(
                    t.id,
                    t.startMs,
                    t.endMs,
                    t.mode.name,
                    t.modeConfidence,
                    t.distanceMeters,
                    if (includeRoute) t.encodedPolyline else null,
                    if (t.confirmed) 1 else 0,
                    t.fromVisitId,
                    t.toVisitId,
                ),
            )
        }
        return cursor
    }

    private fun samplesCursor(start: Long, end: Long): Cursor = runBlocking {
        val samples = entryPoint.locationSampleDao().range(start, end)
        val cursor = MatrixCursor(PathlineContract.Samples.COLUMNS, samples.size)
        for (s in samples) {
            cursor.addRow(
                arrayOf<Any?>(
                    s.id,
                    s.timestampMs,
                    s.latitude,
                    s.longitude,
                    s.altitude,
                    s.accuracy,
                    s.bearing,
                    s.speed,
                    s.provider,
                    if (s.isMock) 1 else 0,
                    s.devicePhysicalState.name,
                    s.arActivity,
                    s.networkTransport?.name,
                    if (s.includedInComputation) 1 else 0,
                ),
            )
        }
        cursor
    }

    // ---- Places --------------------------------------------------------------------------------

    /**
     * `places` collection: saved-place details for places the caller may see — its grant set (built
     * up by [buildVisitsCursor] reads) under READ_TIMELINE, or the whole corpus under
     * READ_ALL_PLACES. Not time-windowed (`start`/`end` ignored; the audit row records a zero-width
     * window at "now"). With `q` it switches into place search (SEARCH_DATA), matching the fields of
     * [PathlineContract.SearchFields] — annotation fields only under READ_ANNOTATIONS.
     */
    private fun placesQuery(uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        val dataType = PathlineContract.Places.PATH
        fun deny(permission: String): Nothing {
            logAccess(dataType, now, now, 0, now, groupId, null, permission)
            throw SecurityException("Caller must hold $permission")
        }

        val allPlaces = holds(PathlineContract.Permissions.READ_ALL_PLACES)
        if (!allPlaces && !holds(PathlineContract.Permissions.READ_TIMELINE)) {
            deny(PathlineContract.Permissions.READ_TIMELINE)
        }
        val q = uri.getQueryParameter(PathlineContract.QueryParams.Q)
        if (q != null && !holds(PathlineContract.Permissions.SEARCH_DATA)) {
            deny(PathlineContract.Permissions.SEARCH_DATA)
        }

        val pkg = callingPackage ?: "unknown"
        val places: List<PlaceEntity> = runBlocking {
            val grantDao = entryPoint.apiPlaceGrantDao()
            val requested = uri.getQueryParameter(PathlineContract.QueryParams.IDS)
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.distinct()
            if (q == null) {
                // Plain listing. Scope: the whole corpus (READ_ALL_PLACES) or the caller's grant
                // set; an `ids` filter is intersected with that scope, never an error.
                when {
                    allPlaces && requested == null -> entryPoint.placeDao().all()
                    allPlaces -> if (requested!!.isEmpty()) emptyList() else entryPoint.placeDao().byIds(requested)
                    else -> {
                        val allowed = when {
                            requested == null -> grantDao.grantedPlaceIds(pkg)
                            requested.isEmpty() -> emptyList()
                            else -> grantDao.grantedAmong(pkg, requested)
                        }
                        if (allowed.isEmpty()) emptyList() else entryPoint.placeDao().byIds(allowed)
                    }
                }
            } else {
                require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
                val fields = placeSearchFields(uri, ::deny)
                val matched = matchedPlaceIds(q, fields)
                val scoped =
                    if (allPlaces) matched.toList()
                    else if (matched.isEmpty()) emptyList()
                    else grantDao.grantedAmong(pkg, matched.toList())
                val filtered =
                    if (requested == null) scoped else scoped.filter { it in requested.toSet() }
                if (filtered.isEmpty()) emptyList() else entryPoint.placeDao().byIds(filtered)
            }
        }
        val cursor = placesCursorOf(places)
        logAccess(dataType, now, now, cursor.count, now, groupId, null, null)
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** The validated place-search field list — defaults to everything the caller may match. */
    private fun placeSearchFields(uri: Uri, deny: (String) -> Nothing): List<String> {
        val fields = ApiSearch.parseFields(uri.getQueryParameter(PathlineContract.QueryParams.FIELDS))
            ?: return PLACE_DETAIL_FIELDS +
                if (holds(PathlineContract.Permissions.READ_ANNOTATIONS)) ANNOTATION_FIELDS else emptyList()
        val unknown = fields.filter { it !in PLACE_DETAIL_FIELDS && it !in ANNOTATION_FIELDS }
        require(unknown.isEmpty()) { "Unknown place search fields: $unknown" }
        if (fields.any { it in ANNOTATION_FIELDS } &&
            !holds(PathlineContract.Permissions.READ_ANNOTATIONS)
        ) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        return fields
    }

    /** Union of the place ids matching [q] in each requested field (unscoped — callers intersect
     *  with what the caller may see). */
    private suspend fun matchedPlaceIds(q: String, fields: List<String>): LinkedHashSet<Long> {
        val ids = LinkedHashSet<Long>()
        val ftsColumns = fields.filter { it in PLACE_DETAIL_FIELDS }
        if (ftsColumns.isNotEmpty()) {
            ids += entryPoint.searchDao().matchRowIds(
                SimpleSQLiteQuery(
                    "SELECT rowid AS id FROM places_fts WHERE places_fts MATCH ?",
                    arrayOf(ApiSearch.ftsQuery(q, ftsColumns)),
                ),
            ).map { it.id }
        }
        if (PathlineContract.SearchFields.TAGS in fields) {
            val tagIds = ftsTagIds(q)
            if (tagIds.isNotEmpty()) {
                ids += entryPoint.tagDao().targetIdsForTags(AnnotationTarget.PLACE, tagIds.toList())
            }
        }
        if (PathlineContract.SearchFields.NOTES in fields) {
            ids += entryPoint.annotationDao().targetIdsWithContentLike(
                AnnotationTarget.PLACE, AnnotationKind.NOTE, ApiSearch.likePattern(q),
            )
        }
        if (PathlineContract.SearchFields.MEMORIES in fields) {
            // Memories are stored as JSON objects; a raw LIKE would also match the structural
            // "value"/"confidence" keys, so decode and match keys + values in code instead.
            val needle = q.trim()
            ids += entryPoint.annotationDao().allOfKind(AnnotationTarget.PLACE, AnnotationKind.MEMORY)
                .filter { row ->
                    MemoryMap.decode(row.content).any { (key, entry) ->
                        key.contains(needle, ignoreCase = true) ||
                            entry.value.contains(needle, ignoreCase = true)
                    }
                }
                .map { it.targetId }
        }
        return ids
    }

    /**
     * `places/<id>/visits` collection: one place's visit history, as lean rows (no place name/address
     * — the caller resolves those once from the parent place). Gated by READ_TIMELINE, with
     * READ_EXTENDED_HISTORY required for any portion older than 30 days (an all-time history, the
     * default when no `start` is given, needs it). Scoped to the caller's place grants, or the whole
     * corpus under READ_ALL_PLACES.
     */
    private fun placeVisitsQuery(uri: Uri, now: Long): Cursor {
        val placeId = uri.pathSegments.getOrNull(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid place id in URI: $uri")
        // Windowed like the other collections: `start` is required (callers must be explicit about how
        // far back they read), `end` defaults to now. Reaching past 30 days still needs extended history.
        val start = uri.getQueryParameter(PathlineContract.QueryParams.START)?.toLongOrNull()
            ?: throw IllegalArgumentException(
                "Missing required '${PathlineContract.QueryParams.START}' query parameter (epoch ms)",
            )
        val end = uri.getQueryParameter(PathlineContract.QueryParams.END)?.toLongOrNull() ?: now
        require(end > start) {
            "'${PathlineContract.QueryParams.END}' must be greater than '${PathlineContract.QueryParams.START}'"
        }
        val groupId = parseGroup(uri, now)
        val dataType = PathlineContract.Places.VISITS_DATA_TYPE

        fun requirePermission(permission: String) {
            if (holds(permission)) return
            logAccess(dataType, start, end, rowCount = 0, now, groupId, null, permission)
            throw SecurityException("Caller must hold $permission")
        }
        requirePermission(PathlineContract.Permissions.READ_TIMELINE)
        if (start < now - PathlineContract.EXTENDED_HISTORY_WINDOW_MS) {
            requirePermission(PathlineContract.Permissions.READ_EXTENDED_HISTORY)
        }

        val cursor = placeVisitsCursor(placeId, start, end)
        logAccess(dataType, start, end, cursor.count, now, groupId, null, null)
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    private fun placesCursorOf(places: List<PlaceEntity>): Cursor {
        val cursor = MatrixCursor(PathlineContract.Places.COLUMNS, places.size)
        for (p in places) {
            cursor.addRow(
                arrayOf<Any?>(
                    p.id,
                    p.name,
                    p.address,
                    p.category,
                    p.types,
                    p.source.name,
                    p.googlePlaceId,
                    p.latitude,
                    p.longitude,
                    p.radiusMeters,
                ),
            )
        }
        return cursor
    }

    private fun placeVisitsCursor(placeId: Long, start: Long, end: Long): Cursor = runBlocking {
        val pkg = callingPackage ?: "unknown"
        // Scope: a place's history is readable only if this app already saw the place in a `visits`
        // read (or holds READ_ALL_PLACES). An unseen (or non-existent) place returns nothing —
        // indistinguishable, so the caller cannot probe for ids it was never shown.
        val granted = holds(PathlineContract.Permissions.READ_ALL_PLACES) ||
            entryPoint.apiPlaceGrantDao().isGranted(pkg, placeId)
        val visits = if (granted) {
            entryPoint.visitDao().forPlaceOverlapping(placeId, start, end)
        } else {
            emptyList()
        }
        val cursor = MatrixCursor(PathlineContract.Places.VisitHistory.COLUMNS, visits.size)
        for (v in visits) {
            cursor.addRow(
                arrayOf<Any?>(
                    v.id,
                    v.startMs,
                    v.endMs,
                    v.placeId,
                    v.centroidLatitude,
                    v.centroidLongitude,
                    v.radiusMeters,
                    v.confidence,
                    if (v.confirmed) 1 else 0,
                    if (v.isOngoing) 1 else 0,
                ),
            )
        }
        cursor
    }

    /**
     * Grant the calling app access to every saved place referenced by a **confirmed** visit it just
     * read. An unconfirmed visit — even one matched to a saved place — does not by itself grant access;
     * but once any confirmed visit grants the place, the place's full history (including its unconfirmed
     * visits) becomes readable, which is fine since the place is legitimately in scope.
     */
    private suspend fun recordPlaceGrants(visits: List<VisitEntity>) {
        val pkg = callingPackage ?: return
        val placeIds = visits.filter { it.confirmed }.mapNotNull { it.placeId }.distinct()
        if (placeIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val dao = entryPoint.apiPlaceGrantDao()
        dao.insertIgnore(placeIds.map { ApiPlaceGrantEntity(pkg, it, now, now) })
        dao.touch(pkg, placeIds, now)
    }

    // ---- Tags & annotations (read) -------------------------------------------------------------

    /**
     * `tags` collection: every tag attached to at least one target the caller can see — its place
     * scope (grants or READ_ALL_PLACES) and, under READ_TIMELINE, confirmed visits/trips within the
     * allowed window. Windowless: without READ_EXTENDED_HISTORY the visit/trip leg **clamps** to the
     * last 30 days instead of throwing. With `q` it becomes a tag-name search (SEARCH_DATA).
     */
    private fun tagsQuery(uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        val dataType = PathlineContract.Tags.PATH
        fun deny(permission: String): Nothing {
            logAccess(dataType, now, now, 0, now, groupId, null, permission)
            throw SecurityException("Caller must hold $permission")
        }
        if (!holds(PathlineContract.Permissions.READ_ANNOTATIONS)) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        val q = uri.getQueryParameter(PathlineContract.QueryParams.Q)
        if (q != null && !holds(PathlineContract.Permissions.SEARCH_DATA)) {
            deny(PathlineContract.Permissions.SEARCH_DATA)
        }

        val tags: List<TagEntity> = runBlocking {
            val visible = visibleTagIds(now)
            val ids = if (q != null) {
                require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
                visible.intersect(ftsTagIds(q))
            } else {
                visible
            }
            if (ids.isEmpty()) emptyList() else entryPoint.tagDao().byIds(ids.toList())
        }
        val cursor = tagsCursorOf(tags)
        logAccess(dataType, now, now, cursor.count, now, groupId, null, null)
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `…/<target>/<id>/tags`: the tags of one place/visit/trip, as [PathlineContract.Tags] rows. */
    private fun targetTagsQuery(uri: Uri, target: AnnotationTarget, now: Long): Cursor {
        val id = targetIdFrom(uri)
        val groupId = parseGroup(uri, now)
        requireAnnotationRead(target, PathlineContract.Tags.PATH, now, groupId)
        val tags = runBlocking {
            if (targetVisible(target, id, now)) entryPoint.tagDao().tagsFor(target, id) else emptyList()
        }
        val cursor = tagsCursorOf(tags)
        logAccess(PathlineContract.Tags.PATH, now, now, cursor.count, now, groupId, null, null)
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `…/<target>/<id>/notes`: the target's single note (0/1 rows). */
    private fun targetNotesQuery(uri: Uri, target: AnnotationTarget, now: Long): Cursor {
        val id = targetIdFrom(uri)
        val groupId = parseGroup(uri, now)
        requireAnnotationRead(target, DATA_TYPE_NOTES, now, groupId)
        val note = runBlocking {
            if (targetVisible(target, id, now)) {
                entryPoint.annotationDao().byTarget(target, id, AnnotationKind.NOTE)
            } else {
                null
            }
        }
        val cursor = MatrixCursor(PathlineContract.Annotations.Notes.COLUMNS, 1)
        note?.let { cursor.addRow(arrayOf<Any?>(it.id, it.content, it.updatedAtMs)) }
        logAccess(DATA_TYPE_NOTES, now, now, cursor.count, now, groupId, null, null)
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `…/<target>/<id>/memories`: the target's memory map, one row per key. */
    private fun targetMemoriesQuery(uri: Uri, target: AnnotationTarget, now: Long): Cursor {
        val id = targetIdFrom(uri)
        val groupId = parseGroup(uri, now)
        requireAnnotationRead(target, DATA_TYPE_MEMORIES, now, groupId)
        val row = runBlocking {
            if (targetVisible(target, id, now)) {
                entryPoint.annotationDao().byTarget(target, id, AnnotationKind.MEMORY)
            } else {
                null
            }
        }
        val entries = MemoryMap.decode(row?.content)
        val cursor = MatrixCursor(PathlineContract.Annotations.Memories.COLUMNS, entries.size)
        for ((k, e) in entries) {
            cursor.addRow(arrayOf<Any?>(k, e.value, e.confidence, row!!.updatedAtMs))
        }
        logAccess(DATA_TYPE_MEMORIES, now, now, cursor.count, now, groupId, null, null)
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** Permissions shared by all per-target annotation reads: READ_ANNOTATIONS plus the read tier
     *  the target type lives under. Logs + throws on the first one missing. */
    private fun requireAnnotationRead(
        target: AnnotationTarget,
        dataType: String,
        now: Long,
        groupId: Long?,
    ) {
        fun deny(permission: String): Nothing {
            logAccess(dataType, now, now, 0, now, groupId, null, permission)
            throw SecurityException("Caller must hold $permission")
        }
        if (!holds(PathlineContract.Permissions.READ_ANNOTATIONS)) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        when (target) {
            AnnotationTarget.PLACE ->
                if (!holds(PathlineContract.Permissions.READ_ALL_PLACES) &&
                    !holds(PathlineContract.Permissions.READ_TIMELINE)
                ) {
                    deny(PathlineContract.Permissions.READ_TIMELINE)
                }
            AnnotationTarget.VISIT, AnnotationTarget.TRIP ->
                if (!holds(PathlineContract.Permissions.READ_TIMELINE)) {
                    deny(PathlineContract.Permissions.READ_TIMELINE)
                }
        }
    }

    /**
     * Whether one annotation target is visible to (and writable by) the caller: a granted/existing
     * place (any place under READ_ALL_PLACES), or a **confirmed** visit/trip inside the allowed
     * window — the last 30 days unless the caller holds READ_EXTENDED_HISTORY. An invisible target
     * reads as empty and rejects writes, indistinguishable from a nonexistent one.
     */
    private suspend fun targetVisible(target: AnnotationTarget, id: Long, now: Long): Boolean =
        when (target) {
            AnnotationTarget.PLACE -> {
                val pkg = callingPackage ?: "unknown"
                val inScope = holds(PathlineContract.Permissions.READ_ALL_PLACES) ||
                    entryPoint.apiPlaceGrantDao().isGranted(pkg, id)
                inScope && entryPoint.placeDao().byId(id) != null
            }
            AnnotationTarget.VISIT -> entryPoint.visitDao().byId(id)
                ?.let { it.confirmed && it.endMs > minVisibleEndMs(now) } == true
            AnnotationTarget.TRIP -> entryPoint.tripDao().byId(id)
                ?.let { it.confirmed && it.endMs > minVisibleEndMs(now) } == true
        }

    /** The end-time floor a visit/trip must reach to be visible on a windowless read (clamp). */
    private fun minVisibleEndMs(now: Long): Long =
        if (holds(PathlineContract.Permissions.READ_EXTENDED_HISTORY)) Long.MIN_VALUE
        else now - PathlineContract.EXTENDED_HISTORY_WINDOW_MS

    /**
     * Ids of every tag attached to at least one target visible to the caller. The tag links live in
     * the encrypted DB and the place grants in the audit DB, so the scoping joins in code: load the
     * (small, user-curated) link table whole, then keep links whose target the caller can see.
     */
    private suspend fun visibleTagIds(now: Long): Set<Long> {
        val links = entryPoint.tagDao().allLinks()
        if (links.isEmpty()) return emptySet()
        val result = HashSet<Long>()
        val minEnd = minVisibleEndMs(now)

        val placeLinks = links.filter { it.targetType == AnnotationTarget.PLACE }
        if (placeLinks.isNotEmpty()) {
            val placeIds = placeLinks.map { it.targetId }.distinct()
            val visible: Set<Long> = when {
                holds(PathlineContract.Permissions.READ_ALL_PLACES) -> placeIds.toSet()
                holds(PathlineContract.Permissions.READ_TIMELINE) -> {
                    val pkg = callingPackage ?: "unknown"
                    entryPoint.apiPlaceGrantDao().grantedAmong(pkg, placeIds).toSet()
                }
                else -> emptySet()
            }
            placeLinks.filter { it.targetId in visible }.mapTo(result) { it.tagId }
        }
        if (holds(PathlineContract.Permissions.READ_TIMELINE)) {
            val visitLinks = links.filter { it.targetType == AnnotationTarget.VISIT }
            if (visitLinks.isNotEmpty()) {
                val visible = entryPoint.visitDao()
                    .confirmedIdsAmong(visitLinks.map { it.targetId }.distinct(), minEnd).toSet()
                visitLinks.filter { it.targetId in visible }.mapTo(result) { it.tagId }
            }
            val tripLinks = links.filter { it.targetType == AnnotationTarget.TRIP }
            if (tripLinks.isNotEmpty()) {
                val visible = entryPoint.tripDao()
                    .confirmedIdsAmong(tripLinks.map { it.targetId }.distinct(), minEnd).toSet()
                tripLinks.filter { it.targetId in visible }.mapTo(result) { it.tagId }
            }
        }
        return result
    }

    private fun tagsCursorOf(tags: List<TagEntity>): Cursor {
        val cursor = MatrixCursor(PathlineContract.Tags.COLUMNS, tags.size)
        for (t in tags) {
            cursor.addRow(arrayOf<Any?>(t.id, t.displayName, t.canonicalName, t.createdAtMs))
        }
        return cursor
    }

    // ---- Search over visits / trips ------------------------------------------------------------

    /**
     * Search mode on the `visits` / `trips` collections (`q` present): rows whose place name, tags
     * or note match, in the collection's normal shape. Needs READ_TIMELINE + SEARCH_DATA (annotation
     * fields additionally READ_ANNOTATIONS). `start` is optional here: when omitted the window
     * clamps per extended-history; an explicit window is enforced exactly like a plain read.
     */
    private fun timelineSearchQuery(uri: Uri, code: Int, now: Long): Cursor {
        val dataType =
            if (code == CODE_VISITS) PathlineContract.Visits.PATH else PathlineContract.Trips.PATH
        val groupId = parseGroup(uri, now)
        fun deny(permission: String): Nothing {
            logAccess(dataType, now, now, 0, now, groupId, null, permission)
            throw SecurityException("Caller must hold $permission")
        }
        if (!holds(PathlineContract.Permissions.READ_TIMELINE)) {
            deny(PathlineContract.Permissions.READ_TIMELINE)
        }
        if (!holds(PathlineContract.Permissions.SEARCH_DATA)) {
            deny(PathlineContract.Permissions.SEARCH_DATA)
        }
        val q = uri.getQueryParameter(PathlineContract.QueryParams.Q)!!
        require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
        val fields = timelineSearchFields(uri, ::deny)

        val end = uri.getQueryParameter(PathlineContract.QueryParams.END)?.toLongOrNull() ?: now
        val explicitStart = uri.getQueryParameter(PathlineContract.QueryParams.START)?.toLongOrNull()
        val start: Long
        if (explicitStart != null) {
            require(end > explicitStart) {
                "'${PathlineContract.QueryParams.END}' must be greater than '${PathlineContract.QueryParams.START}'"
            }
            if (explicitStart < now - PathlineContract.EXTENDED_HISTORY_WINDOW_MS &&
                !holds(PathlineContract.Permissions.READ_EXTENDED_HISTORY)
            ) {
                logAccess(
                    dataType, explicitStart, end, 0, now, groupId, null,
                    PathlineContract.Permissions.READ_EXTENDED_HISTORY,
                )
                throw SecurityException(
                    "Caller must hold ${PathlineContract.Permissions.READ_EXTENDED_HISTORY}",
                )
            }
            start = explicitStart
        } else {
            // Windowless search clamps instead of throwing: all-time with extended history,
            // otherwise the last 30 days.
            start = if (holds(PathlineContract.Permissions.READ_EXTENDED_HISTORY)) 0
            else now - PathlineContract.EXTENDED_HISTORY_WINDOW_MS
        }

        val routeWithheld: Boolean? = if (code == CODE_TRIPS) !routeUnlocked() else null
        val cursor = runBlocking {
            if (code == CODE_VISITS) {
                buildVisitsCursor(searchVisits(q, fields, start, end))
            } else {
                buildTripsCursor(searchTrips(q, fields, start, end), includeRoute = routeWithheld == false)
            }
        }
        logAccess(dataType, start, end, cursor.count, now, groupId, routeWithheld, null)
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** The validated visit/trip-search field list — defaults to everything the caller may match. */
    private fun timelineSearchFields(uri: Uri, deny: (String) -> Nothing): List<String> {
        val fields = ApiSearch.parseFields(uri.getQueryParameter(PathlineContract.QueryParams.FIELDS))
            ?: return listOf(PathlineContract.SearchFields.PLACE_NAME) +
                if (holds(PathlineContract.Permissions.READ_ANNOTATIONS)) TIMELINE_ANNOTATION_FIELDS
                else emptyList()
        val valid = TIMELINE_ANNOTATION_FIELDS + PathlineContract.SearchFields.PLACE_NAME
        val unknown = fields.filter { it !in valid }
        require(unknown.isEmpty()) { "Unknown visit/trip search fields: $unknown" }
        if (fields.any { it in TIMELINE_ANNOTATION_FIELDS } &&
            !holds(PathlineContract.Permissions.READ_ANNOTATIONS)
        ) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        return fields
    }

    private suspend fun searchVisits(
        q: String,
        fields: List<String>,
        start: Long,
        end: Long,
    ): List<VisitEntity> {
        val byId = LinkedHashMap<Long, VisitEntity>()
        if (PathlineContract.SearchFields.PLACE_NAME in fields) {
            val placeIds = ftsPlaceIdsByName(q)
            if (placeIds.isNotEmpty()) {
                entryPoint.visitDao().forPlacesOverlapping(placeIds.toList(), start, end)
                    .forEach { byId[it.id] = it }
            }
        }
        if (PathlineContract.SearchFields.TAGS in fields) {
            val tagIds = ftsTagIds(q)
            if (tagIds.isNotEmpty()) {
                val ids = entryPoint.tagDao().targetIdsForTags(AnnotationTarget.VISIT, tagIds.toList())
                if (ids.isNotEmpty()) {
                    entryPoint.visitDao().byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
                }
            }
        }
        if (PathlineContract.SearchFields.NOTES in fields) {
            val ids = entryPoint.annotationDao().targetIdsWithContentLike(
                AnnotationTarget.VISIT, AnnotationKind.NOTE, ApiSearch.likePattern(q),
            )
            if (ids.isNotEmpty()) {
                entryPoint.visitDao().byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
            }
        }
        return byId.values.sortedBy { it.startMs }
    }

    private suspend fun searchTrips(
        q: String,
        fields: List<String>,
        start: Long,
        end: Long,
    ): List<TripEntity> {
        val byId = LinkedHashMap<Long, TripEntity>()
        if (PathlineContract.SearchFields.PLACE_NAME in fields) {
            val placeIds = ftsPlaceIdsByName(q)
            if (placeIds.isNotEmpty()) {
                entryPoint.tripDao().forEndpointPlacesOverlapping(placeIds.toList(), start, end)
                    .forEach { byId[it.id] = it }
            }
        }
        if (PathlineContract.SearchFields.TAGS in fields) {
            val tagIds = ftsTagIds(q)
            if (tagIds.isNotEmpty()) {
                val ids = entryPoint.tagDao().targetIdsForTags(AnnotationTarget.TRIP, tagIds.toList())
                if (ids.isNotEmpty()) {
                    entryPoint.tripDao().byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
                }
            }
        }
        if (PathlineContract.SearchFields.NOTES in fields) {
            val ids = entryPoint.annotationDao().targetIdsWithContentLike(
                AnnotationTarget.TRIP, AnnotationKind.NOTE, ApiSearch.likePattern(q),
            )
            if (ids.isNotEmpty()) {
                entryPoint.tripDao().byIdsOverlapping(ids, start, end).forEach { byId[it.id] = it }
            }
        }
        return byId.values.sortedBy { it.startMs }
    }

    /** Tag ids whose display name matches [q] (FTS5 prefix match). */
    private suspend fun ftsTagIds(q: String): Set<Long> =
        entryPoint.searchDao().matchRowIds(
            SimpleSQLiteQuery(
                "SELECT rowid AS id FROM tags_fts WHERE tags_fts MATCH ?",
                arrayOf(ApiSearch.ftsQuery(q, listOf("displayName"))),
            ),
        ).mapTo(HashSet()) { it.id }

    /** Place ids whose **name** matches [q] — the place-name leg of visit/trip search. */
    private suspend fun ftsPlaceIdsByName(q: String): Set<Long> =
        entryPoint.searchDao().matchRowIds(
            SimpleSQLiteQuery(
                "SELECT rowid AS id FROM places_fts WHERE places_fts MATCH ?",
                arrayOf(ApiSearch.ftsQuery(q, listOf(PathlineContract.SearchFields.NAME))),
            ),
        ).mapTo(HashSet()) { it.id }

    // ---- Status & shared helpers ----------------------------------------------------------------

    /**
     * One-row [PathlineContract.Status] cursor: whether the access switch is on. Not gated by the switch
     * and not logged (it returns no personal data), so a consumer can always check whether to read or to
     * prompt the user to turn access on.
     */
    private fun statusCursor(): Cursor {
        val cursor = MatrixCursor(PathlineContract.Status.COLUMNS, 1)
        cursor.addRow(arrayOf<Any?>(if (apiAccessEnabled()) 1 else 0))
        return cursor
    }

    /** The current access-switch state, read off the settings store (cached in memory after first read). */
    private fun apiAccessEnabled(): Boolean =
        runBlocking { entryPoint.settingsRepository().apiAccessEnabled() }

    /** The audit-log `dataType` token for a collection [code] (place-history is distinct from its path). */
    private fun dataTypeFor(code: Int): String = when (code) {
        CODE_VISITS -> PathlineContract.Visits.PATH
        CODE_TRIPS -> PathlineContract.Trips.PATH
        CODE_SAMPLES -> PathlineContract.Samples.PATH
        CODE_PLACES -> PathlineContract.Places.PATH
        CODE_PLACE_VISITS -> PathlineContract.Places.VISITS_DATA_TYPE
        CODE_TAGS, in TAGS_CODES, in TAG_NAME_CODES -> PathlineContract.Tags.PATH
        in NOTES_CODES -> DATA_TYPE_NOTES
        in MEMORIES_CODES, in MEMORY_KEY_CODES -> DATA_TYPE_MEMORIES
        else -> "?"
    }

    /** The [AnnotationTarget] an annotation route [code] addresses. */
    private fun targetFor(code: Int): AnnotationTarget = when (code) {
        CODE_PLACE_TAGS, CODE_PLACE_TAG_NAME, CODE_PLACE_NOTES,
        CODE_PLACE_MEMORIES, CODE_PLACE_MEMORY_KEY -> AnnotationTarget.PLACE
        CODE_VISIT_TAGS, CODE_VISIT_TAG_NAME, CODE_VISIT_NOTES,
        CODE_VISIT_MEMORIES, CODE_VISIT_MEMORY_KEY -> AnnotationTarget.VISIT
        else -> AnnotationTarget.TRIP
    }

    /** The `<id>` path segment of an annotation URI (`…/{places|visits|trips}/<id>/…`). */
    private fun targetIdFrom(uri: Uri): Long =
        uri.pathSegments.getOrNull(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid target id in URI: $uri")

    /** The optional batch-correlation key, honoured only when within the grouping window of [now]. */
    private fun parseGroup(uri: Uri, now: Long): Long? {
        val raw = uri.getQueryParameter(PathlineContract.QueryParams.GROUP)?.toLongOrNull()
        return raw?.takeIf {
            it in (now - PathlineContract.GROUP_WINDOW_MS)..(now + GROUP_FUTURE_TOLERANCE_MS)
        }
    }

    /** True when the IPC caller (or our own process) holds [permission]. */
    private fun holds(permission: String): Boolean {
        val ctx: Context = context ?: throw SecurityException("Provider not attached")
        return ctx.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Whether the caller may see trip routes (the polyline column). */
    private fun routeUnlocked(): Boolean =
        holds(PathlineContract.Permissions.READ_TIMELINE_ROUTES) ||
            holds(PathlineContract.Permissions.READ_LOCATION_HISTORY)

    // ---- Annotation writes -----------------------------------------------------------------------

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val now = System.currentTimeMillis()
        return when (val code = matcher.match(uri)) {
            in TAGS_CODES -> insertTag(uri, targetFor(code), values, now)
            in NOTES_CODES -> {
                upsertNote(uri, targetFor(code), values, now)
                uri
            }
            in MEMORIES_CODES -> upsertMemory(uri, targetFor(code), values, now)
            else -> throw UnsupportedOperationException(READ_ONLY_MESSAGE)
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val now = System.currentTimeMillis()
        return when (val code = matcher.match(uri)) {
            // The note and a memory key are upserts: update == insert. (A tag link has no mutable
            // state — re-insert it to refresh the spelling.)
            in NOTES_CODES -> {
                upsertNote(uri, targetFor(code), values, now)
                1
            }
            in MEMORIES_CODES -> {
                upsertMemory(uri, targetFor(code), values, now)
                1
            }
            else -> throw UnsupportedOperationException(READ_ONLY_MESSAGE)
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val now = System.currentTimeMillis()
        return when (val code = matcher.match(uri)) {
            in TAG_NAME_CODES -> deleteTag(uri, targetFor(code), now)
            in NOTES_CODES -> clearNote(uri, targetFor(code), now)
            in MEMORY_KEY_CODES -> deleteMemory(uri, targetFor(code), now)
            in MEMORIES_CODES -> clearMemories(uri, targetFor(code), now)
            else -> throw UnsupportedOperationException(READ_ONLY_MESSAGE)
        }
    }

    /**
     * Common gate for an annotation write: the access switch, the write permission for [dataType]'s
     * tier, the read tier the target type lives under, and the target's visibility (see
     * [targetVisible] — an unconfirmed visit/trip is never writable; an invisible target rejects
     * indistinguishably from a nonexistent one). Returns the request's group id; throws after
     * logging the denial otherwise.
     */
    private fun checkWrite(
        uri: Uri,
        target: AnnotationTarget,
        id: Long,
        permission: String,
        dataType: String,
        now: Long,
    ): Long? {
        val groupId = parseGroup(uri, now)
        if (!apiAccessEnabled()) {
            logAccess(
                dataType, now, now, 0, now, groupId, null,
                deniedPermission = PathlineContract.Permissions.API, notify = false, isWrite = true,
            )
            throw SecurityException("Third-party access to Pathline data is turned off")
        }
        fun deny(p: String): Nothing {
            logAccess(dataType, now, now, 0, now, groupId, null, p, isWrite = true)
            throw SecurityException("Caller must hold $p")
        }
        if (!holds(permission)) deny(permission)
        when (target) {
            AnnotationTarget.PLACE ->
                if (!holds(PathlineContract.Permissions.READ_ALL_PLACES) &&
                    !holds(PathlineContract.Permissions.READ_TIMELINE)
                ) {
                    deny(PathlineContract.Permissions.READ_TIMELINE)
                }
            AnnotationTarget.VISIT, AnnotationTarget.TRIP ->
                if (!holds(PathlineContract.Permissions.READ_TIMELINE)) {
                    deny(PathlineContract.Permissions.READ_TIMELINE)
                }
        }
        if (!runBlocking { targetVisible(target, id, now) }) {
            // A write aimed at nothing: logged (audit honesty) and rejected. The message must not
            // reveal whether the row exists.
            logAccess(dataType, now, now, 0, now, groupId, null, null, isWrite = true)
            throw IllegalArgumentException(
                "No writable ${target.name.lowercase()} with id $id (missing, unconfirmed, or not accessible)",
            )
        }
        return groupId
    }

    /** `insert` on `…/<id>/tags`: apply (or re-apply) the tag named in [PathlineContract.Tags.NAME]. */
    private fun insertTag(uri: Uri, target: AnnotationTarget, values: ContentValues?, now: Long): Uri {
        val id = targetIdFrom(uri)
        val name = values?.getAsString(PathlineContract.Tags.NAME)?.trim()
        require(!name.isNullOrEmpty()) { "Missing '${PathlineContract.Tags.NAME}' value" }
        val groupId = checkWrite(
            uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            PathlineContract.Tags.PATH, now,
        )
        runBlocking { entryPoint.annotationStore().applyTag(target, id, name) }
            ?: throw IllegalArgumentException("Tag name canonicalizes to nothing: '$name'")
        logAccess(PathlineContract.Tags.PATH, now, now, 1, now, groupId, null, null, isWrite = true)
        notifyChanged(uri)
        return uri.buildUpon().appendPath(name).build()
    }

    /** `insert`/`update` on `…/<id>/notes`: replace the whole note (blank text clears it). */
    private fun upsertNote(uri: Uri, target: AnnotationTarget, values: ContentValues?, now: Long) {
        val id = targetIdFrom(uri)
        val content = values?.getAsString(PathlineContract.Annotations.Notes.CONTENT)
            ?: throw IllegalArgumentException("Missing '${PathlineContract.Annotations.Notes.CONTENT}' value")
        val groupId = checkWrite(
            uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS_NOTES,
            DATA_TYPE_NOTES, now,
        )
        runBlocking { entryPoint.annotationStore().setNote(target, id, content) }
        logAccess(DATA_TYPE_NOTES, now, now, 1, now, groupId, null, null, isWrite = true)
        notifyChanged(uri)
    }

    /** `insert`/`update` on `…/<id>/memories`: put one key→value entry (value must be a string;
     *  confidence optional, a float in [0,1] defaulting to 1). */
    private fun upsertMemory(uri: Uri, target: AnnotationTarget, values: ContentValues?, now: Long): Uri {
        val id = targetIdFrom(uri)
        val key = values?.get(PathlineContract.Annotations.Memories.KEY) as? String
        require(!key.isNullOrBlank()) { "Missing '${PathlineContract.Annotations.Memories.KEY}' value" }
        // The memory contract: flat string -> string. A non-string value (int, bool, blob, null) is
        // rejected here rather than silently stringified.
        val value = values.get(PathlineContract.Annotations.Memories.VALUE)
        require(value is String) {
            "'${PathlineContract.Annotations.Memories.VALUE}' must be a string (no nesting, no other types)"
        }
        val confidence = when (val raw = values.get(PathlineContract.Annotations.Memories.CONFIDENCE)) {
            null -> 1f
            is Float -> raw
            is Double -> raw.toFloat()
            else -> throw IllegalArgumentException(
                "'${PathlineContract.Annotations.Memories.CONFIDENCE}' must be a float",
            )
        }
        require(confidence in 0f..1f) {
            "'${PathlineContract.Annotations.Memories.CONFIDENCE}' must be within [0, 1], got $confidence"
        }
        val groupId = checkWrite(
            uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            DATA_TYPE_MEMORIES, now,
        )
        runBlocking { entryPoint.annotationStore().putMemory(target, id, key, value, confidence) }
        logAccess(DATA_TYPE_MEMORIES, now, now, 1, now, groupId, null, null, isWrite = true)
        notifyChanged(uri)
        return uri.buildUpon().appendPath(key).build()
    }

    /** `delete` on `…/<id>/tags/<name>`: unlink the tag (any spelling); the tag row survives. */
    private fun deleteTag(uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val name = uri.lastPathSegment ?: ""
        val groupId = checkWrite(
            uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            PathlineContract.Tags.PATH, now,
        )
        val removed = runBlocking { entryPoint.annotationStore().removeTag(target, id, name) }
        logAccess(PathlineContract.Tags.PATH, now, now, removed, now, groupId, null, null, isWrite = true)
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** `delete` on `…/<id>/notes`: clear the note. */
    private fun clearNote(uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val groupId = checkWrite(
            uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS_NOTES,
            DATA_TYPE_NOTES, now,
        )
        val removed = runBlocking {
            val store = entryPoint.annotationStore()
            val existed = store.getNote(target, id) != null
            store.setNote(target, id, null)
            if (existed) 1 else 0
        }
        logAccess(DATA_TYPE_NOTES, now, now, removed, now, groupId, null, null, isWrite = true)
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** `delete` on `…/<id>/memories/<key>`: remove one entry. */
    private fun deleteMemory(uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val key = uri.lastPathSegment ?: ""
        val groupId = checkWrite(
            uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            DATA_TYPE_MEMORIES, now,
        )
        val removed = runBlocking {
            val store = entryPoint.annotationStore()
            val current = store.getMemories(target, id)
            if (key in current) {
                store.setMemories(target, id, current - key)
                1
            } else {
                0
            }
        }
        logAccess(DATA_TYPE_MEMORIES, now, now, removed, now, groupId, null, null, isWrite = true)
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** `delete` on `…/<id>/memories`: clear the whole map; returns the number of keys removed. */
    private fun clearMemories(uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val groupId = checkWrite(
            uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            DATA_TYPE_MEMORIES, now,
        )
        val removed = runBlocking {
            val store = entryPoint.annotationStore()
            val current = store.getMemories(target, id)
            if (current.isNotEmpty()) store.setMemories(target, id, emptyMap())
            current.size
        }
        logAccess(DATA_TYPE_MEMORIES, now, now, removed, now, groupId, null, null, isWrite = true)
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** Wake any consumer cursor watching this URI (reads register it via setNotificationUri). */
    private fun notifyChanged(uri: Uri) {
        runCatching { context?.contentResolver?.notifyChange(uri, null) }
    }

    private companion object {
        const val CODE_VISITS = 1
        const val CODE_TRIPS = 2
        const val CODE_SAMPLES = 3
        const val CODE_PLACES = 4
        const val CODE_PLACE_VISITS = 5
        const val CODE_STATUS = 6
        const val CODE_TAGS = 7

        const val CODE_PLACE_TAGS = 10
        const val CODE_VISIT_TAGS = 11
        const val CODE_TRIP_TAGS = 12
        const val CODE_PLACE_TAG_NAME = 13
        const val CODE_VISIT_TAG_NAME = 14
        const val CODE_TRIP_TAG_NAME = 15
        const val CODE_PLACE_NOTES = 16
        const val CODE_VISIT_NOTES = 17
        const val CODE_TRIP_NOTES = 18
        const val CODE_PLACE_MEMORIES = 19
        const val CODE_VISIT_MEMORIES = 20
        const val CODE_TRIP_MEMORIES = 21
        const val CODE_PLACE_MEMORY_KEY = 22
        const val CODE_VISIT_MEMORY_KEY = 23
        const val CODE_TRIP_MEMORY_KEY = 24

        val TAGS_CODES = setOf(CODE_PLACE_TAGS, CODE_VISIT_TAGS, CODE_TRIP_TAGS)
        val TAG_NAME_CODES = setOf(CODE_PLACE_TAG_NAME, CODE_VISIT_TAG_NAME, CODE_TRIP_TAG_NAME)
        val NOTES_CODES = setOf(CODE_PLACE_NOTES, CODE_VISIT_NOTES, CODE_TRIP_NOTES)
        val MEMORIES_CODES = setOf(CODE_PLACE_MEMORIES, CODE_VISIT_MEMORIES, CODE_TRIP_MEMORIES)
        val MEMORY_KEY_CODES = setOf(CODE_PLACE_MEMORY_KEY, CODE_VISIT_MEMORY_KEY, CODE_TRIP_MEMORY_KEY)

        /** Audit-log `dataType` tokens for the two annotation payload kinds (tags use [PathlineContract.Tags.PATH]). */
        const val DATA_TYPE_NOTES = PathlineContract.Annotations.NOTES_PATH
        const val DATA_TYPE_MEMORIES = PathlineContract.Annotations.MEMORIES_PATH

        /** MIME types of the single-item annotation URIs (delete-only; not part of the contract). */
        const val ITEM_TYPE_TAG = "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.tag"
        const val ITEM_TYPE_MEMORY = "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.memory"

        const val READ_ONLY_MESSAGE =
            "Pathline's data API is read-only except the annotation sub-collections (see PathlineContract.Annotations)"

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

        /** Small tolerance for a `group` value slightly ahead of our clock (granularity), no more. */
        const val GROUP_FUTURE_TOLERANCE_MS = 5_000L
    }
}
