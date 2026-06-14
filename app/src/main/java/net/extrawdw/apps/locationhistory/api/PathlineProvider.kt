package net.extrawdw.apps.locationhistory.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.ApiAccessDao
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantDao
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptDao
import net.extrawdw.apps.locationhistory.data.db.ConceptEntity
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.SearchDao
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TagEntity
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.routes.RoutesGateway
import net.extrawdw.apps.locationhistory.data.routes.TravelTimeRequest
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.ConceptStore
import net.extrawdw.apps.locationhistory.domain.MemoryMap
import net.extrawdw.apps.locationhistory.domain.NameCanonicalizer
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import java.io.IOException

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
 * Structurally this class is the **router**: URI matching, dispatch, the audit-log sink and the
 * write-path orchestration. The security kernel (permission gates, visibility scoping, window
 * clamping) lives in [ApiGate], row mapping in [ApiCursors], and the search legs in
 * [ApiSearchEngine] — all plain classes over DAO interfaces so they are unit-testable off-device.
 * Each entry point captures the calling identity ONCE as a [Caller] and threads it explicitly;
 * nothing below the entry points reads ambient binder state.
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
        fun conceptDao(): ConceptDao
        fun searchDao(): SearchDao
        fun annotationStore(): AnnotationStore
        fun conceptStore(): ConceptStore
        fun apiAccessDao(): ApiAccessDao
        fun apiPlaceGrantDao(): ApiPlaceGrantDao
        fun routesGateway(): RoutesGateway
        fun settingsRepository(): SettingsRepository
        fun workScheduler(): WorkScheduler
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        val visits = PathlineContract.Visits.PATH
        val trips = PathlineContract.Trips.PATH
        val places = PathlineContract.Places.PATH
        val concepts = PathlineContract.Concepts.PATH
        val members = PathlineContract.Concepts.MEMBERS_PATH
        val tags = PathlineContract.Annotations.TAGS_PATH
        val notes = PathlineContract.Annotations.NOTES_PATH
        val memories = PathlineContract.Annotations.MEMORIES_PATH

        addURI(PathlineContract.AUTHORITY, visits, CODE_VISITS)
        addURI(PathlineContract.AUTHORITY, trips, CODE_TRIPS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Samples.PATH, CODE_SAMPLES)
        addURI(PathlineContract.AUTHORITY, places, CODE_PLACES)
        addURI(PathlineContract.AUTHORITY, PathlineContract.TravelTimes.PATH, CODE_TRAVEL_TIMES)
        addURI(
            PathlineContract.AUTHORITY,
            "$places/#/${PathlineContract.Places.VISITS_PATH}",
            CODE_PLACE_VISITS
        )
        addURI(PathlineContract.AUTHORITY, PathlineContract.Status.PATH, CODE_STATUS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Tags.PATH, CODE_TAGS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.PlaceStats.PATH, CODE_PLACE_STATS)

        // Single timeline rows by id
        addURI(PathlineContract.AUTHORITY, "$visits/#", CODE_VISIT_ITEM)
        addURI(PathlineContract.AUTHORITY, "$trips/#", CODE_TRIP_ITEM)

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

        // Concepts: the collection, one row, its member edge, its annotation sub-collections, and
        // the reverse per-target listings.
        addURI(PathlineContract.AUTHORITY, concepts, CODE_CONCEPTS)
        addURI(PathlineContract.AUTHORITY, "$concepts/#", CODE_CONCEPT_ITEM)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$members", CODE_CONCEPT_MEMBERS)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$members/*/#", CODE_CONCEPT_MEMBER_ITEM)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$tags", CODE_CONCEPT_TAGS)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$tags/*", CODE_CONCEPT_TAG_NAME)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$notes", CODE_CONCEPT_NOTES)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$memories", CODE_CONCEPT_MEMORIES)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$memories/*", CODE_CONCEPT_MEMORY_KEY)
        addURI(PathlineContract.AUTHORITY, "$places/#/$concepts", CODE_PLACE_CONCEPTS)
        addURI(PathlineContract.AUTHORITY, "$visits/#/$concepts", CODE_VISIT_CONCEPTS)
        addURI(PathlineContract.AUTHORITY, "$trips/#/$concepts", CODE_TRIP_CONCEPTS)
        addURI(PathlineContract.AUTHORITY, "$concepts/#/$concepts", CODE_CONCEPT_CONCEPTS)
    }

    private val entryPoint: DaoEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context!!.applicationContext, DaoEntryPoint::class.java,
        )
    }

    /** The audit-log sink: append one row + schedule the user-facing access notification.
     *  Best-effort: a logging failure must never break a caller's read. */
    private val logger = AccessLogger { caller, event ->
        runCatching {
            runBlocking {
                entryPoint.apiAccessDao().insert(
                    ApiAccessEventEntity(
                        packageName = caller.pkgOrUnknown,
                        dataType = event.dataType,
                        startMs = event.startMs,
                        endMs = event.endMs,
                        rowCount = event.rowCount,
                        timestampMs = event.nowMs,
                        groupId = event.groupId,
                        routeWithheld = event.routeWithheld,
                        deniedPermission = event.deniedPermission,
                        isWrite = event.isWrite,
                    ),
                )
            }
        }
        // Alert the user about this access — a successful read/write OR a denied (unauthorized)
        // attempt, on separate per-app back-off lanes. Coalesced + rate-limited by WorkManager /
        // the worker. Skipped (logged-only) for denials while the whole API is switched off.
        if (!event.notify) return@AccessLogger
        runCatching {
            entryPoint.workScheduler()
                .enqueueApiAccessNotification(
                    caller.pkgOrUnknown,
                    denied = event.deniedPermission != null
                )
        }
    }

    private val gate: ApiGate by lazy {
        ApiGate(
            visitDao = entryPoint.visitDao(),
            tripDao = entryPoint.tripDao(),
            placeDao = entryPoint.placeDao(),
            conceptDao = entryPoint.conceptDao(),
            tagDao = entryPoint.tagDao(),
            grantDao = entryPoint.apiPlaceGrantDao(),
            accessEnabled = ::apiAccessEnabled,
            logger = logger,
        )
    }

    private val searchEngine: ApiSearchEngine by lazy {
        ApiSearchEngine(
            searchDao = entryPoint.searchDao(),
            tagDao = entryPoint.tagDao(),
            annotationDao = entryPoint.annotationDao(),
            visitDao = entryPoint.visitDao(),
            tripDao = entryPoint.tripDao(),
        )
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_VISITS -> PathlineContract.Visits.CONTENT_TYPE
        CODE_TRIPS -> PathlineContract.Trips.CONTENT_TYPE
        CODE_VISIT_ITEM -> PathlineContract.Visits.ITEM_CONTENT_TYPE
        CODE_TRIP_ITEM -> PathlineContract.Trips.ITEM_CONTENT_TYPE
        CODE_SAMPLES -> PathlineContract.Samples.CONTENT_TYPE
        CODE_PLACES -> PathlineContract.Places.CONTENT_TYPE
        CODE_TRAVEL_TIMES -> PathlineContract.TravelTimes.CONTENT_TYPE
        CODE_PLACE_VISITS -> PathlineContract.Places.VisitHistory.CONTENT_TYPE
        CODE_PLACE_STATS -> PathlineContract.PlaceStats.CONTENT_TYPE
        CODE_STATUS -> PathlineContract.Status.CONTENT_TYPE
        CODE_TAGS, in TAGS_CODES -> PathlineContract.Tags.CONTENT_TYPE
        in TAG_NAME_CODES -> ITEM_TYPE_TAG
        in NOTES_CODES -> PathlineContract.Annotations.Notes.CONTENT_TYPE
        in MEMORIES_CODES -> PathlineContract.Annotations.Memories.CONTENT_TYPE
        in MEMORY_KEY_CODES -> ITEM_TYPE_MEMORY
        CODE_CONCEPTS, in CONCEPTS_FOR_TARGET_CODES -> PathlineContract.Concepts.CONTENT_TYPE
        CODE_CONCEPT_ITEM -> PathlineContract.Concepts.ITEM_CONTENT_TYPE
        CODE_CONCEPT_MEMBERS -> PathlineContract.Concepts.Members.CONTENT_TYPE
        CODE_CONCEPT_MEMBER_ITEM -> ITEM_TYPE_CONCEPT_MEMBER
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
        if (code == CODE_STATUS) return ApiCursors.status(apiAccessEnabled())

        val caller = captureCaller()

        // The single access switch gates EVERYTHING below; see [ApiGate.requireApiEnabled].
        gate.requireApiEnabled(
            caller, dataTypeFor(code),
            startMs = now, endMs = now, nowMs = now, groupId = parseGroup(uri, now),
        )

        // The windowless collections (places, tags, per-target annotations) branch off before the
        // required-`start` parsing below; so do searches, where `start` becomes optional.
        when (code) {
            CODE_PLACES -> return placesQuery(caller, uri, now)
            CODE_TRAVEL_TIMES -> return travelTimesQuery(caller, uri, now)
            CODE_PLACE_VISITS -> return placeVisitsQuery(caller, uri, now)
            CODE_PLACE_STATS -> return placeStatsQuery(caller, uri, now)
            CODE_VISIT_ITEM, CODE_TRIP_ITEM -> return timelineItemQuery(caller, uri, code, now)
            CODE_TAGS -> return tagsQuery(caller, uri, now)
            CODE_CONCEPTS -> return conceptsQuery(caller, uri, now)
            CODE_CONCEPT_ITEM -> return conceptItemQuery(caller, uri, now)
            CODE_CONCEPT_MEMBERS -> return conceptMembersQuery(caller, uri, now)
            in CONCEPTS_FOR_TARGET_CODES -> return targetConceptsQuery(
                caller,
                uri,
                targetFor(code),
                now
            )

            in TAGS_CODES -> return targetTagsQuery(caller, uri, targetFor(code), now)
            in NOTES_CODES -> return targetNotesQuery(caller, uri, targetFor(code), now)
            in MEMORIES_CODES -> return targetMemoriesQuery(caller, uri, targetFor(code), now)
            // The single-item annotation/member URIs exist for delete only.
            in TAG_NAME_CODES, in MEMORY_KEY_CODES, CODE_CONCEPT_MEMBER_ITEM ->
                throw IllegalArgumentException("Not a queryable URI (delete-only): $uri")
        }
        if ((code == CODE_VISITS || code == CODE_TRIPS) &&
            uri.getQueryParameter(PathlineContract.QueryParams.Q) != null
        ) {
            return timelineSearchQuery(caller, uri, code, now)
        }
        if ((code == CODE_VISITS || code == CODE_TRIPS) &&
            uri.getQueryParameter(PathlineContract.QueryParams.IDS) != null
        ) {
            return timelineByIdsQuery(caller, uri, code, now)
        }

        val (start, end) = gate.requireWindow(
            uri.getQueryParameter(PathlineContract.QueryParams.START),
            uri.getQueryParameter(PathlineContract.QueryParams.END),
            now,
        )

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

        // Enforce each required permission; a well-formed but unauthorized request is recorded as a
        // denied event and rejected — the window is never silently narrowed.
        gate.requireWindowedRead(caller, basePermission, dataType, start, end, now, groupId)

        // A trip's encoded route is the raw movement path, so it sits behind the location-trail tier:
        // a timeline-only caller still gets trip rows but with the polyline column nulled. Either the
        // route permission or full location-history unlocks it (a sample reader can rebuild the path).
        val routeWithheld: Boolean? = if (code == CODE_TRIPS) !caller.routeUnlocked() else null

        val limit = parseLimit(uri)
        val cursor = when (code) {
            CODE_VISITS -> runBlocking {
                // Like samples, a limited read fetches newest-first with SQL LIMIT, then re-ascends.
                val visits =
                    if (limit == null) entryPoint.visitDao().overlapping(start, end)
                    else entryPoint.visitDao().overlappingNewest(start, end, limit).asReversed()
                visitsCursorFor(caller, visits)
            }

            CODE_TRIPS -> runBlocking {
                val trips =
                    if (limit == null) entryPoint.tripDao().overlapping(start, end)
                    else entryPoint.tripDao().overlappingNewest(start, end, limit).asReversed()
                ApiCursors.trips(trips, includeRoute = routeWithheld == false)
            }

            CODE_SAMPLES -> samplesCursor(start, end, limit)
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = start,
                endMs = end,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
                routeWithheld = routeWithheld,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    // ---- Timeline & samples ------------------------------------------------------------------

    /** [PathlineContract.Visits] rows for [visits]: resolve place names once per distinct place,
     *  build the cursor, and record the caller's place grants (best-effort, never breaks a read). */
    private suspend fun visitsCursorFor(caller: Caller, visits: List<VisitEntity>): Cursor {
        val placeDao = entryPoint.placeDao()
        val names = HashMap<Long, String?>()
        for (v in visits) {
            v.placeId?.let { id -> names.getOrPut(id) { placeDao.byId(id)?.name } }
        }
        val cursor = ApiCursors.visits(visits, names)
        // Record that this caller has now seen these saved places, so it may later resolve their
        // details and history via the `places` collection. Best-effort: never break the read.
        runCatching { recordPlaceGrants(caller, visits) }
        return cursor
    }

    /** The `limit=` rule for chronological lists: keep the NEWEST rows, order unchanged
     *  (ascending). Null = uncapped. */
    private fun <T> List<T>.takeNewest(limit: Int?): List<T> =
        if (limit == null || size <= limit) this else takeLast(limit)

    private fun samplesCursor(start: Long, end: Long, limit: Int? = null): Cursor = runBlocking {
        // Samples are the one collection big enough for the cap to matter at the DB layer: a
        // limited read fetches newest-first with SQL LIMIT, then re-ascends.
        val samples =
            if (limit == null) entryPoint.locationSampleDao().range(start, end)
            else entryPoint.locationSampleDao().rangeNewest(start, end, limit).asReversed()
        ApiCursors.samples(samples)
    }

    // ---- Travel estimates -----------------------------------------------------------------------

    /**
     * `travel_times`: Google Maps travel-time summaries (driving / walking / cycling / two-wheeler /
     * transit) between two saved Pathline places. It never widens place scope: the caller must hold
     * READ_ALL_PLACES, or already have grants for both requested place ids under READ_TIMELINE.
     * Returns no rows (and makes no billable Google call) when the user has disabled routing or when
     * a place is out of scope; network/Google errors are mapped to no rows rather than thrown.
     */
    private fun travelTimesQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val t = PathlineContract.TravelTimes
        val groupId = parseGroup(uri, now)
        fun deny(permission: String): Nothing =
            gate.deny(caller, permission, t.PATH, now, now, now, groupId)

        val originId = uri.getQueryParameter(t.ORIGIN_PLACE_ID)?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid '${t.ORIGIN_PLACE_ID}'")
        val destinationId = uri.getQueryParameter(t.DESTINATION_PLACE_ID)?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid '${t.DESTINATION_PLACE_ID}'")

        val allPlaces = caller.holds(PathlineContract.Permissions.READ_ALL_PLACES)
        if (!allPlaces && !caller.holds(PathlineContract.Permissions.READ_TIMELINE)) {
            deny(PathlineContract.Permissions.READ_TIMELINE)
        }

        val request = travelTimeRequest(uri, now)
        val cursor = runBlocking {
            // Per-feature kill switch: when routing is off, return no rows and make no (billable)
            // Google call. The master API switch is already enforced upstream in query().
            if (!entryPoint.settingsRepository().routeApiEnabled()) {
                return@runBlocking ApiCursors.travelTimes(emptyList())
            }
            val byId = placesInScope(caller, allPlaces, listOf(originId, destinationId))
            val origin = byId[originId]
            val destination = byId[destinationId]
            val rows = if (origin == null || destination == null) {
                emptyList()
            } else {
                // A network failure / non-2xx surfaces as IOException, which is NOT marshalable
                // across a ContentProvider binder; map it to no rows so a flaky network or Google
                // error never crashes the consumer (and honors the "no route -> no rows" contract).
                try {
                    entryPoint.routesGateway().travelTimes(origin, destination, request, now)
                } catch (e: IOException) {
                    AppLog.w(TAG, "travel_times lookup failed: ${e.message}")
                    emptyList()
                }
            }
            ApiCursors.travelTimes(rows)
        }
        // Audited on every path -- including disabled, out-of-scope, empty, and failed lookups.
        logger.log(
            caller,
            AccessEvent(
                dataType = t.PATH,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /**
     * Resolve [ids] to the [PlaceEntity]s the caller may see, keyed by id: the whole corpus under
     * READ_ALL_PLACES, otherwise only ids the caller already holds a grant for. Mirrors the place
     * scoping `places`/`place_stats` use, so an out-of-scope id is simply absent from the result
     * (never an error, never a leak).
     */
    private suspend fun placesInScope(
        caller: Caller,
        allPlaces: Boolean,
        ids: List<Long>,
    ): Map<Long, PlaceEntity> {
        val requested = ids.distinct()
        if (requested.isEmpty()) return emptyMap()
        val allowed = if (allPlaces) {
            requested
        } else {
            entryPoint.apiPlaceGrantDao().grantedAmong(caller.pkgOrUnknown, requested)
        }
        if (allowed.isEmpty()) return emptyMap()
        return entryPoint.placeDao().byIds(allowed).associateBy { it.id }
    }

    private fun travelTimeRequest(uri: Uri, now: Long): TravelTimeRequest {
        val t = PathlineContract.TravelTimes
        val mode = uri.getQueryParameter(t.TRAVEL_MODE)
            ?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "DRIVE"
        val travelModes = setOf("DRIVE", "WALK", "BICYCLE", "TWO_WHEELER", "TRANSIT")
        require(mode in travelModes) {
            "'${t.TRAVEL_MODE}' must be one of ${travelModes.joinToString(",")}"
        }

        val departure = uri.getQueryParameter(t.DEPARTURE_TIME_MS)?.toLongOrNull()
        val arrival = uri.getQueryParameter(t.ARRIVAL_TIME_MS)?.toLongOrNull()
        require(!(departure != null && arrival != null)) {
            "Specify at most one of '${t.DEPARTURE_TIME_MS}' or '${t.ARRIVAL_TIME_MS}'"
        }
        require(arrival == null || mode == "TRANSIT") {
            "'${t.ARRIVAL_TIME_MS}' is only supported for TRANSIT"
        }
        // Past times are rejected: Google rejects past transit/traffic-aware departures, and a past
        // request can never produce a useful estimate. A small skew window absorbs clock drift.
        val time = departure ?: arrival
        if (time != null) {
            require(time >= now - TRAVEL_PAST_SKEW_MS && time <= now + TRAVEL_FUTURE_WINDOW_MS) {
                "'${t.DEPARTURE_TIME_MS}'/'${t.ARRIVAL_TIME_MS}' must not be in the past or beyond 100 days out"
            }
        }

        val transitModes = uri.getQueryParameter(t.MODES)
            ?.split(',')
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        val transitSubModes = setOf("BUS", "SUBWAY", "TRAIN", "LIGHT_RAIL", "RAIL")
        require(transitModes.all { it in transitSubModes }) {
            "'${t.MODES}' may contain only ${transitSubModes.joinToString(",")}"
        }
        val transitPreference = uri.getQueryParameter(t.ROUTING_PREFERENCE)
            ?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(
            transitPreference == null ||
                    transitPreference == "LESS_WALKING" || transitPreference == "FEWER_TRANSFERS"
        ) {
            "'${t.ROUTING_PREFERENCE}' must be LESS_WALKING or FEWER_TRANSFERS"
        }
        val traffic = uri.getQueryParameter(t.TRAFFIC)
            ?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(
            traffic == null ||
                    traffic == "TRAFFIC_UNAWARE" || traffic == "TRAFFIC_AWARE" || traffic == "TRAFFIC_AWARE_OPTIMAL"
        ) {
            "'${t.TRAFFIC}' must be TRAFFIC_UNAWARE, TRAFFIC_AWARE, or TRAFFIC_AWARE_OPTIMAL"
        }

        fun flag(name: String): Boolean = uri.getQueryParameter(name)?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } == true

        return TravelTimeRequest(
            travelMode = mode,
            departureTimeMs = departure,
            arrivalTimeMs = arrival,
            transitModes = transitModes,
            transitRoutingPreference = transitPreference,
            drivingRoutingPreference = traffic,
            avoidTolls = flag(t.AVOID_TOLLS),
            avoidHighways = flag(t.AVOID_HIGHWAYS),
            avoidFerries = flag(t.AVOID_FERRIES),
            computeAlternatives = flag(t.ALTERNATIVES),
            languageCode = uri.getQueryParameter(t.LANGUAGE_CODE)?.trim()
                ?.takeIf { it.isNotEmpty() },
            regionCode = uri.getQueryParameter(t.REGION_CODE)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ---- Places --------------------------------------------------------------------------------

    /**
     * `places` collection: saved-place details for places the caller may see — its grant set (built
     * up by [visitsCursorFor] reads) under READ_TIMELINE, or the whole corpus under
     * READ_ALL_PLACES. Not time-windowed (`start`/`end` ignored; the audit row records a zero-width
     * window at "now"). With `q` it switches into place search (SEARCH_DATA), matching the fields of
     * [PathlineContract.SearchFields] — annotation fields only under READ_ANNOTATIONS.
     */
    private fun placesQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        val dataType = PathlineContract.Places.PATH
        fun deny(permission: String): Nothing =
            gate.deny(caller, permission, dataType, now, now, now, groupId)

        val allPlaces = caller.holds(PathlineContract.Permissions.READ_ALL_PLACES)
        if (!allPlaces && !caller.holds(PathlineContract.Permissions.READ_TIMELINE)) {
            deny(PathlineContract.Permissions.READ_TIMELINE)
        }
        val q = uri.getQueryParameter(PathlineContract.QueryParams.Q)
        if (q != null && !caller.holds(PathlineContract.Permissions.SEARCH_DATA)) {
            deny(PathlineContract.Permissions.SEARCH_DATA)
        }
        val near = uri.getQueryParameter(PathlineContract.QueryParams.NEAR)
            ?.let { GeoSearch.parseNear(it) }
        val limit = parseLimit(uri)

        val pkg = caller.pkgOrUnknown
        // In proximity mode the rows also carry their distance from the `near` point.
        var distances: Map<Long, Double>? = null
        val places: List<PlaceEntity> = runBlocking {
            val grantDao = entryPoint.apiPlaceGrantDao()
            val requested = uri.getQueryParameter(PathlineContract.QueryParams.IDS)
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.distinct()
            if (near != null) {
                // Proximity mode: bounding-box prefilter (indexed) -> exact Haversine -> nearest
                // first; then the same scope/q/ids filters as the other modes, order preserved.
                val radius = GeoSearch.parseRadius(
                    uri.getQueryParameter(PathlineContract.QueryParams.RADIUS_M),
                )
                val (lat, lng) = near
                val box = GeoSearch.boundingBox(lat, lng, radius)
                val candidates =
                    if (box.wrapsAntimeridian) entryPoint.placeDao().all()
                    else entryPoint.placeDao()
                        .inBoundingBox(box.latMin, box.lngMin, box.latMax, box.lngMax)
                var nearby = candidates
                    .map { it to GeoSearch.haversineMeters(lat, lng, it.latitude, it.longitude) }
                    .filter { (_, d) -> d <= radius }
                    .sortedBy { (_, d) -> d }
                if (!allPlaces) {
                    val granted = grantDao.grantedAmong(pkg, nearby.map { it.first.id }).toSet()
                    nearby = nearby.filter { (p, _) -> p.id in granted }
                }
                if (requested != null) nearby =
                    nearby.filter { (p, _) -> p.id in requested.toSet() }
                if (q != null) {
                    require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
                    val matched = searchEngine.matchedPlaceIds(
                        q,
                        searchEngine.placeSearchFields(
                            caller,
                            uri.getQueryParameter(PathlineContract.QueryParams.FIELDS),
                            ::deny,
                        ),
                    )
                    nearby = nearby.filter { (p, _) -> p.id in matched }
                }
                if (limit != null) nearby = nearby.take(limit)
                distances = nearby.associate { (p, d) -> p.id to d }
                nearby.map { (p, _) -> p }
            } else if (q == null) {
                // Plain listing. Scope: the whole corpus (READ_ALL_PLACES) or the caller's grant
                // set; an `ids` filter is intersected with that scope, never an error.
                val listed = when {
                    allPlaces && requested == null -> entryPoint.placeDao().all()
                    allPlaces -> if (requested!!.isEmpty()) emptyList() else entryPoint.placeDao()
                        .byIds(requested)

                    else -> {
                        val allowed = when {
                            requested == null -> grantDao.grantedPlaceIds(pkg)
                            requested.isEmpty() -> emptyList()
                            else -> grantDao.grantedAmong(pkg, requested)
                        }
                        if (allowed.isEmpty()) emptyList() else entryPoint.placeDao().byIds(allowed)
                    }
                }
                if (limit == null) listed else listed.take(limit)
            } else {
                require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
                val fields = searchEngine.placeSearchFields(
                    caller,
                    uri.getQueryParameter(PathlineContract.QueryParams.FIELDS),
                    ::deny,
                )
                // matchedPlaceIds is relevance-ordered (bm25 FTS hits first); scope/ids filtering
                // and the final fetch must all preserve that order — SQL `IN` doesn't, so reorder.
                val matched = searchEngine.matchedPlaceIds(q, fields)
                val allowed: Set<Long> =
                    if (allPlaces || matched.isEmpty()) matched
                    else grantDao.grantedAmong(pkg, matched.toList()).toSet()
                val filtered = matched.filter {
                    it in allowed && (requested == null || it in requested.toSet())
                }.let { if (limit == null) it else it.take(limit) }
                if (filtered.isEmpty()) emptyList()
                else sortByIdOrder(entryPoint.placeDao().byIds(filtered), filtered) { it.id }
            }
        }
        val cursor = ApiCursors.places(places, distances)
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /**
     * `places/<id>/visits` collection: one place's visit history, as lean rows (no place name/address
     * — the caller resolves those once from the parent place). Gated by READ_TIMELINE, with
     * READ_EXTENDED_HISTORY required for any portion older than 30 days (an all-time history, the
     * default when no `start` is given, needs it). Scoped to the caller's place grants, or the whole
     * corpus under READ_ALL_PLACES.
     */
    private fun placeVisitsQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val placeId = uri.pathSegments.getOrNull(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid place id in URI: $uri")
        // Windowed like the other collections: `start` is required (callers must be explicit about how
        // far back they read), `end` defaults to now. Reaching past 30 days still needs extended history.
        val (start, end) = gate.requireWindow(
            uri.getQueryParameter(PathlineContract.QueryParams.START),
            uri.getQueryParameter(PathlineContract.QueryParams.END),
            now,
        )
        val groupId = parseGroup(uri, now)
        val dataType = PathlineContract.Places.VISITS_DATA_TYPE

        gate.requireWindowedRead(
            caller, PathlineContract.Permissions.READ_TIMELINE, dataType, start, end, now, groupId,
        )

        val cursor = runBlocking {
            val pkg = caller.pkgOrUnknown
            // Scope: a place's history is readable only if this app already saw the place in a
            // `visits` read (or holds READ_ALL_PLACES). An unseen (or non-existent) place returns
            // nothing — indistinguishable, so the caller cannot probe for ids it was never shown.
            val granted = caller.holds(PathlineContract.Permissions.READ_ALL_PLACES) ||
                    entryPoint.apiPlaceGrantDao().isGranted(pkg, placeId)
            val visits = if (granted) {
                entryPoint.visitDao().forPlaceOverlapping(placeId, start, end)
                    .takeNewest(parseLimit(uri))
            } else {
                emptyList()
            }
            ApiCursors.placeVisits(visits)
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = start,
                endMs = end,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /**
     * `place_stats` collection: per-place aggregates over confirmed visits in the window, scoped to
     * the caller's place grants (whole corpus under READ_ALL_PLACES), most-visited first. Windowed
     * and extended-history-gated exactly like `visits`; one GROUP BY instead of the caller pulling
     * and counting visit rows.
     */
    private fun placeStatsQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val (start, end) = gate.requireWindow(
            uri.getQueryParameter(PathlineContract.QueryParams.START),
            uri.getQueryParameter(PathlineContract.QueryParams.END),
            now,
        )
        val groupId = parseGroup(uri, now)
        val dataType = PathlineContract.PlaceStats.PATH

        gate.requireWindowedRead(
            caller, PathlineContract.Permissions.READ_TIMELINE, dataType, start, end, now, groupId,
        )

        val requested = uri.getQueryParameter(PathlineContract.QueryParams.IDS)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
        val rows = runBlocking {
            val all = entryPoint.visitDao().placeStatsOverlapping(start, end)
            val scoped = if (caller.holds(PathlineContract.Permissions.READ_ALL_PLACES)) {
                all
            } else {
                val granted = entryPoint.apiPlaceGrantDao()
                    .grantedAmong(caller.pkgOrUnknown, all.map { it.placeId }).toSet()
                all.filter { it.placeId in granted }
            }
            val filtered =
                if (requested == null) scoped else scoped.filter { it.placeId in requested }
            parseLimit(uri)?.let { return@runBlocking filtered.take(it) }
            filtered
        }
        val cursor = ApiCursors.placeStats(rows)
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = start,
                endMs = end,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /**
     * Grant the calling app access to every saved place referenced by a **confirmed** visit it just
     * read. An unconfirmed visit — even one matched to a saved place — does not by itself grant access;
     * but once any confirmed visit grants the place, the place's full history (including its unconfirmed
     * visits) becomes readable, which is fine since the place is legitimately in scope.
     */
    private suspend fun recordPlaceGrants(caller: Caller, visits: List<VisitEntity>) {
        val pkg = caller.pkg ?: return
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
    private fun tagsQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        val dataType = PathlineContract.Tags.PATH
        fun deny(permission: String): Nothing =
            gate.deny(caller, permission, dataType, now, now, now, groupId)
        if (!caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        val q = uri.getQueryParameter(PathlineContract.QueryParams.Q)
        if (q != null && !caller.holds(PathlineContract.Permissions.SEARCH_DATA)) {
            deny(PathlineContract.Permissions.SEARCH_DATA)
        }

        val limit = parseLimit(uri)
        val tags: List<TagEntity> = runBlocking {
            val visible = gate.visibleTagIds(caller, now)
            if (q != null) {
                require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
                // Filter the bm25-ranked hits by visibility (not the other way round) so the
                // relevance order survives, then restore it after the unordered IN fetch.
                val ids = searchEngine.ftsTagIds(q).filter { it in visible }
                if (ids.isEmpty()) emptyList()
                else sortByIdOrder(entryPoint.tagDao().byIds(ids), ids) { it.id }
            } else {
                if (visible.isEmpty()) emptyList() else entryPoint.tagDao().byIds(visible.toList())
            }
        }.let { if (limit == null) it else it.take(limit) }
        val cursor = ApiCursors.tags(caller, tags)
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `…/<target>/<id>/tags`: the tags of one place/visit/trip/concept, as [PathlineContract.Tags]
     *  rows — here with the per-link [PathlineContract.Tags.ATTACHED_BY_ME] populated. */
    private fun targetTagsQuery(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        now: Long
    ): Cursor {
        val id = targetIdFrom(uri)
        val groupId = parseGroup(uri, now)
        gate.requireAnnotationRead(caller, target, PathlineContract.Tags.PATH, now, groupId)
        val (tags, attachedBy) = runBlocking {
            if (gate.targetVisible(caller, target, id, now)) {
                val dao = entryPoint.tagDao()
                dao.tagsFor(target, id) to
                        dao.linksFor(target, id).associate { it.tagId to it.createdBy }
            } else {
                emptyList<TagEntity>() to emptyMap()
            }
        }
        val cursor = ApiCursors.tags(caller, tags, attachedBy)
        logger.log(
            caller,
            AccessEvent(
                dataType = PathlineContract.Tags.PATH,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `…/<target>/<id>/notes`: the target's single note (0/1 rows). */
    private fun targetNotesQuery(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        now: Long
    ): Cursor {
        val id = targetIdFrom(uri)
        val groupId = parseGroup(uri, now)
        gate.requireAnnotationRead(caller, target, DATA_TYPE_NOTES, now, groupId)
        val note = runBlocking {
            if (gate.targetVisible(caller, target, id, now)) {
                entryPoint.annotationDao().byTarget(target, id, AnnotationKind.NOTE)
            } else {
                null
            }
        }
        val cursor =
            ApiCursors.note(caller, note?.id, note?.content, note?.updatedAtMs, note?.updatedBy)
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_NOTES,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `…/<target>/<id>/memories`: the target's memory map, one row per key. */
    private fun targetMemoriesQuery(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        now: Long
    ): Cursor {
        val id = targetIdFrom(uri)
        val groupId = parseGroup(uri, now)
        gate.requireAnnotationRead(caller, target, DATA_TYPE_MEMORIES, now, groupId)
        val row = runBlocking {
            if (gate.targetVisible(caller, target, id, now)) {
                entryPoint.annotationDao().byTarget(target, id, AnnotationKind.MEMORY)
            } else {
                null
            }
        }
        val cursor = ApiCursors.memories(caller, MemoryMap.decode(row?.content))
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_MEMORIES,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    // ---- Concepts (read) -------------------------------------------------------------------------

    /**
     * `concepts` collection: every concept (they're visible to any annotation reader — see the
     * contract). With `q` a name/kind/description search (SEARCH_DATA); with `kind` an exact
     * canonicalized filter; with `ids` an id filter. Windowless. Archived concepts are filtered
     * per the `archived` param (default: excluded) — after the other filters, before the limit.
     */
    private fun conceptsQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        fun deny(permission: String): Nothing =
            gate.deny(caller, permission, DATA_TYPE_CONCEPTS, now, now, now, groupId)
        if (!caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        val q = uri.getQueryParameter(PathlineContract.QueryParams.Q)
        if (q != null && !caller.holds(PathlineContract.Permissions.SEARCH_DATA)) {
            deny(PathlineContract.Permissions.SEARCH_DATA)
        }
        val kind = uri.getQueryParameter(PathlineContract.QueryParams.KIND)?.let {
            NameCanonicalizer.canonicalize(it).also { canonical ->
                require(canonical.isNotEmpty()) {
                    "'${PathlineContract.QueryParams.KIND}' canonicalizes to nothing: '$it'"
                }
            }
        }
        val requested = uri.getQueryParameter(PathlineContract.QueryParams.IDS)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.distinct()
        val archivedMode = parseArchivedMode(uri)

        val (concepts, memberCounts) = runBlocking {
            val dao = entryPoint.conceptDao()
            var list = when {
                q != null -> {
                    require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
                    // bm25-ranked FTS head + the concept's own tag/note/memory matches; byIds loses
                    // the order, so restore it.
                    val ids = searchEngine.matchedConceptIds(q)
                    if (ids.isEmpty()) emptyList()
                    else sortByIdOrder(dao.byIds(ids.toList()), ids) { it.id }
                }

                kind != null -> dao.byKind(kind)
                else -> dao.all()
            }
            if (q != null && kind != null) list = list.filter { it.kind == kind }
            if (requested != null) list = list.filter { it.id in requested.toSet() }
            list = list.filterArchived(archivedMode)
            parseLimit(uri)?.let { list = list.take(it) }
            list to memberCountsFor(list)
        }
        val cursor = ApiCursors.concepts(caller, concepts, memberCounts)
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPTS,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** The MEMBER_COUNT join for concept rows: one GROUP BY over the member edge, scoped in SQL
     *  to the rows being returned (no per-concept query). */
    private suspend fun memberCountsFor(concepts: List<ConceptEntity>): Map<Long, Int> {
        if (concepts.isEmpty()) return emptyMap()
        return entryPoint.conceptDao().memberCounts(concepts.map { it.id })
            .associate { it.conceptId to it.members }
    }

    /** `concepts/<id>`: one concept row (empty when the id doesn't exist). */
    private fun conceptItemQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        if (!caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)) {
            gate.deny(
                caller, PathlineContract.Permissions.READ_ANNOTATIONS,
                DATA_TYPE_CONCEPTS, now, now, now, groupId,
            )
        }
        val id = uri.lastPathSegment?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid concept id in URI: $uri")
        val (concept, memberCounts) = runBlocking {
            val row = entryPoint.conceptDao().byId(id)
            row to memberCountsFor(listOfNotNull(row))
        }
        val cursor = ApiCursors.concepts(caller, listOfNotNull(concept), memberCounts)
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPTS,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `concepts/<id>/members`: the membership edge, one row per member (empty for an unknown id). */
    private fun conceptMembersQuery(caller: Caller, uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        if (!caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)) {
            gate.deny(
                caller, PathlineContract.Permissions.READ_ANNOTATIONS,
                DATA_TYPE_CONCEPT_MEMBERS, now, now, now, groupId,
            )
        }
        val id = targetIdFrom(uri)
        val members = runBlocking { entryPoint.conceptDao().membersOf(id) }
        val cursor = ApiCursors.conceptMembers(caller, members)
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPT_MEMBERS,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /** `…/<target>/<id>/concepts`: the concepts a visible target belongs to, with ATTACHED_BY_ME.
     *  Serves places/visits/trips and — for nesting's "which concepts contain this one" — concepts.
     *  Archived containers are filtered per the `archived` param (default: excluded). */
    private fun targetConceptsQuery(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        now: Long
    ): Cursor {
        val id = targetIdFrom(uri)
        val groupId = parseGroup(uri, now)
        val archivedMode = parseArchivedMode(uri)
        gate.requireAnnotationRead(caller, target, DATA_TYPE_CONCEPTS, now, groupId)
        val (concepts, attachedBy, memberCounts) = runBlocking {
            if (gate.targetVisible(caller, target, id, now)) {
                val dao = entryPoint.conceptDao()
                val list = dao.conceptsFor(target, id).filterArchived(archivedMode)
                Triple(
                    list,
                    dao.membershipsFor(target, id).associate { it.conceptId to it.createdBy },
                    memberCountsFor(list),
                )
            } else {
                Triple(emptyList<ConceptEntity>(), emptyMap(), emptyMap())
            }
        }
        val cursor = ApiCursors.concepts(caller, concepts, memberCounts, attachedBy)
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPTS,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /**
     * `visits/<id>` / `trips/<id>`: ONE timeline row by its stable id — the resolver for stored id
     * references (e.g. a memory source like `visit:675 note`). Visibility mirrors the windowless
     * `visits`/`trips` **collection** read, not the annotation-target rule: gated by
     * [PathlineContract.Permissions.READ_TIMELINE], confirmed **and** unconfirmed rows alike (the
     * `confirmed` flag is just a column), inside the allowed horizon (last 30 days unless the caller
     * holds READ_EXTENDED_HISTORY). This keeps "if a collection/search read returned an id, the
     * single-item read resolves it" — otherwise an unconfirmed id surfaced by search would 404 here.
     * An out-of-horizon or nonexistent id returns an **empty** cursor — indistinguishable on
     * purpose, so ids can't be probed. A visit read records its place grant exactly like a windowed
     * read (still confirmed-only — see [recordPlaceGrants]); trips include the route column only for
     * route holders.
     */
    private fun timelineItemQuery(caller: Caller, uri: Uri, code: Int, now: Long): Cursor {
        val isVisit = code == CODE_VISIT_ITEM
        val dataType =
            if (isVisit) PathlineContract.Visits.PATH else PathlineContract.Trips.PATH
        val groupId = parseGroup(uri, now)
        if (!caller.holds(PathlineContract.Permissions.READ_TIMELINE)) {
            gate.deny(
                caller, PathlineContract.Permissions.READ_TIMELINE,
                dataType, now, now, now, groupId,
            )
        }
        val id = uri.lastPathSegment?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid id in URI: $uri")
        val routeWithheld: Boolean? = if (!isVisit) !caller.routeUnlocked() else null
        val cursor = runBlocking {
            if (isVisit) {
                val v = entryPoint.visitDao().byId(id)
                    ?.takeIf { it.endMs > gate.minVisibleEndMs(caller, now) }
                visitsCursorFor(caller, listOfNotNull(v))
            } else {
                val t = entryPoint.tripDao().byId(id)
                    ?.takeIf { it.endMs > gate.minVisibleEndMs(caller, now) }
                ApiCursors.trips(listOfNotNull(t), includeRoute = routeWithheld == false)
            }
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = now,
                endMs = now,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
                routeWithheld = routeWithheld,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /**
     * Batch id resolve on the `visits` / `trips` collections (`ids` present, no `q`): the multi-id
     * form of [timelineItemQuery], one read instead of N — mirroring the `places` `ids` filter.
     * Same gating as a plain read (READ_TIMELINE); the window is **optional** like search mode —
     * when omitted it clamps per READ_EXTENDED_HISTORY, an explicit window is enforced exactly like
     * a plain read. Ids outside the window/horizon or nonexistent are silently omitted, never an
     * error — indistinguishable on purpose, so ids can't be probed. Rows come back chronological
     * (ascending) like every timeline read; `limit` keeps the newest. Visits record place grants;
     * trips carry the route column only for route holders.
     */
    private fun timelineByIdsQuery(caller: Caller, uri: Uri, code: Int, now: Long): Cursor {
        val isVisits = code == CODE_VISITS
        val dataType = if (isVisits) PathlineContract.Visits.PATH else PathlineContract.Trips.PATH
        val groupId = parseGroup(uri, now)
        if (!caller.holds(PathlineContract.Permissions.READ_TIMELINE)) {
            gate.deny(
                caller, PathlineContract.Permissions.READ_TIMELINE,
                dataType, now, now, now, groupId,
            )
        }
        val requested = uri.getQueryParameter(PathlineContract.QueryParams.IDS)!!
            .split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()
        val (start, end) = gate.searchWindow(
            caller,
            uri.getQueryParameter(PathlineContract.QueryParams.START),
            uri.getQueryParameter(PathlineContract.QueryParams.END),
            dataType, now, groupId,
        )
        val routeWithheld: Boolean? = if (!isVisits) !caller.routeUnlocked() else null
        val limit = parseLimit(uri)
        val cursor = runBlocking {
            if (isVisits) {
                val visits =
                    if (requested.isEmpty()) emptyList()
                    else entryPoint.visitDao().byIdsOverlapping(requested, start, end)
                visitsCursorFor(caller, visits.takeNewest(limit))
            } else {
                val trips =
                    if (requested.isEmpty()) emptyList()
                    else entryPoint.tripDao().byIdsOverlapping(requested, start, end)
                ApiCursors.trips(trips.takeNewest(limit), includeRoute = routeWithheld == false)
            }
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = start,
                endMs = end,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
                routeWithheld = routeWithheld,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    // ---- Search over visits / trips ------------------------------------------------------------

    /**
     * Search mode on the `visits` / `trips` collections (`q` present): rows whose place name, tags
     * or note match, in the collection's normal shape. Needs READ_TIMELINE + SEARCH_DATA (annotation
     * fields additionally READ_ANNOTATIONS). `start` is optional here: when omitted the window
     * clamps per extended-history; an explicit window is enforced exactly like a plain read.
     */
    private fun timelineSearchQuery(caller: Caller, uri: Uri, code: Int, now: Long): Cursor {
        val dataType =
            if (code == CODE_VISITS) PathlineContract.Visits.PATH else PathlineContract.Trips.PATH
        val groupId = parseGroup(uri, now)
        fun deny(permission: String): Nothing =
            gate.deny(caller, permission, dataType, now, now, now, groupId)
        if (!caller.holds(PathlineContract.Permissions.READ_TIMELINE)) {
            deny(PathlineContract.Permissions.READ_TIMELINE)
        }
        if (!caller.holds(PathlineContract.Permissions.SEARCH_DATA)) {
            deny(PathlineContract.Permissions.SEARCH_DATA)
        }
        val q = uri.getQueryParameter(PathlineContract.QueryParams.Q)!!
        require(q.isNotBlank()) { "'${PathlineContract.QueryParams.Q}' must not be blank" }
        val fields = searchEngine.timelineSearchFields(
            caller,
            uri.getQueryParameter(PathlineContract.QueryParams.FIELDS),
            ::deny,
        )

        val (start, end) = gate.searchWindow(
            caller,
            uri.getQueryParameter(PathlineContract.QueryParams.START),
            uri.getQueryParameter(PathlineContract.QueryParams.END),
            dataType, now, groupId,
        )

        // An `ids` filter is intersected with the matches, like on `places` — never an error.
        val requested = uri.getQueryParameter(PathlineContract.QueryParams.IDS)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()

        fun <T> List<T>.onlyRequested(id: (T) -> Long): List<T> =
            if (requested == null) this else filter { id(it) in requested }

        val routeWithheld: Boolean? = if (code == CODE_TRIPS) !caller.routeUnlocked() else null
        val limit = parseLimit(uri)
        val cursor = runBlocking {
            if (code == CODE_VISITS) {
                visitsCursorFor(
                    caller,
                    searchEngine.searchVisits(q, fields, start, end)
                        .onlyRequested { it.id }.takeNewest(limit),
                )
            } else {
                ApiCursors.trips(
                    searchEngine.searchTrips(q, fields, start, end)
                        .onlyRequested { it.id }.takeNewest(limit),
                    includeRoute = routeWithheld == false,
                )
            }
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = start,
                endMs = end,
                rowCount = cursor.count,
                nowMs = now,
                groupId = groupId,
                routeWithheld = routeWithheld,
            ),
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    // ---- Status & shared helpers ----------------------------------------------------------------

    /** The current access-switch state, read off the settings store (cached in memory after first read). */
    private fun apiAccessEnabled(): Boolean =
        runBlocking { entryPoint.settingsRepository().apiAccessEnabled() }

    /** The request identity, captured ONCE per entry point and threaded explicitly — see [Caller]. */
    private fun captureCaller(): Caller {
        val ctx = context ?: throw SecurityException("Provider not attached")
        return Caller(callingPackage) { permission ->
            ctx.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** The audit-log `dataType` token for a collection [code] (place-history is distinct from its path). */
    private fun dataTypeFor(code: Int): String = when (code) {
        CODE_VISITS -> PathlineContract.Visits.PATH
        CODE_TRIPS -> PathlineContract.Trips.PATH
        CODE_SAMPLES -> PathlineContract.Samples.PATH
        CODE_PLACES -> PathlineContract.Places.PATH
        CODE_TRAVEL_TIMES -> PathlineContract.TravelTimes.PATH
        CODE_PLACE_VISITS -> PathlineContract.Places.VISITS_DATA_TYPE
        CODE_PLACE_STATS -> PathlineContract.PlaceStats.PATH
        CODE_TAGS, in TAGS_CODES, in TAG_NAME_CODES -> PathlineContract.Tags.PATH
        in NOTES_CODES -> DATA_TYPE_NOTES
        in MEMORIES_CODES, in MEMORY_KEY_CODES -> DATA_TYPE_MEMORIES
        CODE_CONCEPTS, CODE_CONCEPT_ITEM, in CONCEPTS_FOR_TARGET_CODES -> DATA_TYPE_CONCEPTS
        CODE_CONCEPT_MEMBERS, CODE_CONCEPT_MEMBER_ITEM -> DATA_TYPE_CONCEPT_MEMBERS
        else -> "?"
    }

    /** The [AnnotationTarget] an annotation route [code] addresses. */
    private fun targetFor(code: Int): AnnotationTarget = when (code) {
        CODE_PLACE_TAGS, CODE_PLACE_TAG_NAME, CODE_PLACE_NOTES,
        CODE_PLACE_MEMORIES, CODE_PLACE_MEMORY_KEY, CODE_PLACE_CONCEPTS -> AnnotationTarget.PLACE

        CODE_VISIT_TAGS, CODE_VISIT_TAG_NAME, CODE_VISIT_NOTES,
        CODE_VISIT_MEMORIES, CODE_VISIT_MEMORY_KEY, CODE_VISIT_CONCEPTS -> AnnotationTarget.VISIT

        CODE_CONCEPT_TAGS, CODE_CONCEPT_TAG_NAME, CODE_CONCEPT_NOTES,
        CODE_CONCEPT_MEMORIES, CODE_CONCEPT_MEMORY_KEY, CODE_CONCEPT_CONCEPTS -> AnnotationTarget.CONCEPT

        else -> AnnotationTarget.TRIP
    }

    /** The `<id>` path segment of an annotation URI (`…/{places|visits|trips}/<id>/…`). */
    private fun targetIdFrom(uri: Uri): Long =
        uri.pathSegments.getOrNull(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid target id in URI: $uri")

    /** See [ApiGate.parseLimit]. */
    private fun parseLimit(uri: Uri): Int? =
        gate.parseLimit(uri.getQueryParameter(PathlineContract.QueryParams.LIMIT))

    /** See [ApiGate.parseGroup]. */
    private fun parseGroup(uri: Uri, now: Long): Long? =
        gate.parseGroup(uri.getQueryParameter(PathlineContract.QueryParams.GROUP), now)

    // ---- Annotation writes -----------------------------------------------------------------------

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val now = System.currentTimeMillis()
        val caller = captureCaller()
        return when (val code = matcher.match(uri)) {
            in TAGS_CODES -> insertTag(caller, uri, targetFor(code), values, now)
            in NOTES_CODES -> {
                upsertNote(caller, uri, targetFor(code), values, now)
                uri
            }

            in MEMORIES_CODES -> upsertMemory(caller, uri, targetFor(code), values, now)
            CODE_CONCEPTS -> insertConcept(caller, uri, values, now)
            CODE_CONCEPT_MEMBERS -> insertConceptMember(caller, uri, values, now)
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
        val caller = captureCaller()
        return when (val code = matcher.match(uri)) {
            // The note and a memory key are upserts: update == insert. (A tag link has no mutable
            // state — re-insert it to refresh the spelling.)
            in NOTES_CODES -> {
                upsertNote(caller, uri, targetFor(code), values, now)
                1
            }

            in MEMORIES_CODES -> {
                upsertMemory(caller, uri, targetFor(code), values, now)
                1
            }

            CODE_CONCEPT_ITEM -> updateConcept(caller, uri, values, now)
            else -> throw UnsupportedOperationException(READ_ONLY_MESSAGE)
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val now = System.currentTimeMillis()
        val caller = captureCaller()
        return when (val code = matcher.match(uri)) {
            in TAG_NAME_CODES -> deleteTag(caller, uri, targetFor(code), now)
            in NOTES_CODES -> clearNote(caller, uri, targetFor(code), now)
            in MEMORY_KEY_CODES -> deleteMemory(caller, uri, targetFor(code), now)
            in MEMORIES_CODES -> clearMemories(caller, uri, targetFor(code), now)
            CODE_CONCEPT_ITEM -> deleteConcept(caller, uri, now)
            CODE_CONCEPT_MEMBER_ITEM -> deleteConceptMember(caller, uri, now)
            else -> throw UnsupportedOperationException(READ_ONLY_MESSAGE)
        }
    }

    /** [ApiGate.checkWrite] bridged onto the binder thread; returns the request's group id. */
    private fun checkWrite(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        id: Long,
        permission: String,
        dataType: String,
        now: Long,
    ): Long? {
        val groupId = parseGroup(uri, now)
        runBlocking { gate.checkWrite(caller, target, id, permission, dataType, now, groupId) }
        return groupId
    }

    /** `insert` on `…/<id>/tags`: apply (or re-apply) the tag named in [PathlineContract.Tags.NAME]. */
    private fun insertTag(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        values: ContentValues?,
        now: Long,
    ): Uri {
        val id = targetIdFrom(uri)
        val name = values?.getAsString(PathlineContract.Tags.NAME)?.trim()
        require(!name.isNullOrEmpty()) { "Missing '${PathlineContract.Tags.NAME}' value" }
        val groupId = checkWrite(
            caller, uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            PathlineContract.Tags.PATH, now,
        )
        runBlocking { entryPoint.annotationStore().applyTag(target, id, name, caller.pkg) }
            ?: throw IllegalArgumentException("Tag name canonicalizes to nothing: '$name'")
        logger.log(
            caller,
            AccessEvent(
                dataType = PathlineContract.Tags.PATH,
                startMs = now,
                endMs = now,
                rowCount = 1,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        notifyChanged(uri)
        return uri.buildUpon().appendPath(name).build()
    }

    /** `insert`/`update` on `…/<id>/notes`: replace the whole note (blank text clears it). */
    private fun upsertNote(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        values: ContentValues?,
        now: Long,
    ) {
        val id = targetIdFrom(uri)
        val content = values?.getAsString(PathlineContract.Annotations.Notes.CONTENT)
            ?: throw IllegalArgumentException("Missing '${PathlineContract.Annotations.Notes.CONTENT}' value")
        val groupId = checkWrite(
            caller, uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS_NOTES,
            DATA_TYPE_NOTES, now,
        )
        runBlocking { entryPoint.annotationStore().setNote(target, id, content, caller.pkg) }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_NOTES,
                startMs = now,
                endMs = now,
                rowCount = 1,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        notifyChanged(uri)
    }

    /** `insert`/`update` on `…/<id>/memories`: put one key→value entry (value must be a string;
     *  confidence optional, a float in [0,1] defaulting to 1). */
    private fun upsertMemory(
        caller: Caller,
        uri: Uri,
        target: AnnotationTarget,
        values: ContentValues?,
        now: Long,
    ): Uri {
        val id = targetIdFrom(uri)
        val key = values?.get(PathlineContract.Annotations.Memories.KEY) as? String
        require(!key.isNullOrBlank()) { "Missing '${PathlineContract.Annotations.Memories.KEY}' value" }
        // The memory contract: flat string -> string. A non-string value (int, bool, blob, null) is
        // rejected here rather than silently stringified.
        val value = values.get(PathlineContract.Annotations.Memories.VALUE)
        require(value is String) {
            "'${PathlineContract.Annotations.Memories.VALUE}' must be a string (no nesting, no other types)"
        }
        val confidence =
            when (val raw = values.get(PathlineContract.Annotations.Memories.CONFIDENCE)) {
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
        // Optional provenance note. Like the value it must be a plain string; bounded so a runaway
        // writer can't stuff documents into metadata.
        val source = when (val raw = values.get(PathlineContract.Annotations.Memories.SOURCE)) {
            null -> null
            is String -> raw.trim().ifEmpty { null }
            else -> throw IllegalArgumentException(
                "'${PathlineContract.Annotations.Memories.SOURCE}' must be a string",
            )
        }
        require(source == null || source.length <= MAX_MEMORY_SOURCE_LENGTH) {
            "'${PathlineContract.Annotations.Memories.SOURCE}' must be at most " +
                    "$MAX_MEMORY_SOURCE_LENGTH characters"
        }
        val groupId = checkWrite(
            caller, uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            DATA_TYPE_MEMORIES, now,
        )
        runBlocking {
            entryPoint.annotationStore()
                .putMemory(target, id, key, value, confidence, source, caller.pkg)
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_MEMORIES,
                startMs = now,
                endMs = now,
                rowCount = 1,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        notifyChanged(uri)
        return uri.buildUpon().appendPath(key).build()
    }

    /** `delete` on `…/<id>/tags/<name>`: unlink the tag (any spelling); the tag row survives. */
    private fun deleteTag(caller: Caller, uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val name = uri.lastPathSegment ?: ""
        val groupId = checkWrite(
            caller, uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            PathlineContract.Tags.PATH, now,
        )
        val removed = runBlocking { entryPoint.annotationStore().removeTag(target, id, name) }
        logger.log(
            caller,
            AccessEvent(
                dataType = PathlineContract.Tags.PATH,
                startMs = now,
                endMs = now,
                rowCount = removed,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** `delete` on `…/<id>/notes`: clear the note. */
    private fun clearNote(caller: Caller, uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val groupId = checkWrite(
            caller, uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS_NOTES,
            DATA_TYPE_NOTES, now,
        )
        val removed = runBlocking {
            val store = entryPoint.annotationStore()
            val existed = store.getNote(target, id) != null
            store.setNote(target, id, null)
            if (existed) 1 else 0
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_NOTES,
                startMs = now,
                endMs = now,
                rowCount = removed,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** `delete` on `…/<id>/memories/<key>`: remove one entry. */
    private fun deleteMemory(caller: Caller, uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val key = uri.lastPathSegment ?: ""
        val groupId = checkWrite(
            caller, uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            DATA_TYPE_MEMORIES, now,
        )
        // The read-modify-write lives in the store, under its memory-write mutex — doing it here
        // would race other binder threads.
        val removed = runBlocking {
            if (entryPoint.annotationStore().removeMemory(target, id, key, caller.pkg)) 1 else 0
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_MEMORIES,
                startMs = now,
                endMs = now,
                rowCount = removed,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** `delete` on `…/<id>/memories`: clear the whole map; returns the number of keys removed. */
    private fun clearMemories(caller: Caller, uri: Uri, target: AnnotationTarget, now: Long): Int {
        val id = targetIdFrom(uri)
        val groupId = checkWrite(
            caller, uri, target, id, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            DATA_TYPE_MEMORIES, now,
        )
        val removed = runBlocking {
            entryPoint.annotationStore().clearMemories(target, id, caller.pkg)
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_MEMORIES,
                startMs = now,
                endMs = now,
                rowCount = removed,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    // ---- Concept writes ----------------------------------------------------------------------------

    /** `insert` on `concepts`: create one (NAME required; KIND/DESCRIPTION optional). A canonical
     *  name collision is an error naming the existing id — never a silent reuse. */
    private fun insertConcept(caller: Caller, uri: Uri, values: ContentValues?, now: Long): Uri {
        val name = values?.getAsString(PathlineContract.Concepts.NAME)?.trim()
        require(!name.isNullOrEmpty()) { "Missing '${PathlineContract.Concepts.NAME}' value" }
        val kind = values.getAsString(PathlineContract.Concepts.KIND)
        val description = values.getAsString(PathlineContract.Concepts.DESCRIPTION)
        val groupId = parseGroup(uri, now)
        gate.checkConceptCollectionWrite(caller, DATA_TYPE_CONCEPTS, now, groupId)
        val concept = runBlocking {
            entryPoint.conceptStore().create(name, kind, description, caller.pkg)
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPTS,
                startMs = now,
                endMs = now,
                rowCount = 1,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        notifyChanged(uri)
        return PathlineContract.Concepts.itemUri(concept.id)
    }

    /** `update` on `concepts/<id>`: partial intrinsic edit — only the keys present change; a key
     *  present with a null value clears that field (NAME cannot be cleared). The ARCHIVED key
     *  (boolean) archives/unarchives, combinable with intrinsic edits in the same call. */
    private fun updateConcept(caller: Caller, uri: Uri, values: ContentValues?, now: Long): Int {
        val id = targetIdFrom(uri)
        val groupId = checkWrite(
            caller, uri, AnnotationTarget.CONCEPT, id,
            PathlineContract.Permissions.WRITE_ANNOTATIONS, DATA_TYPE_CONCEPTS, now,
        )
        val name = if (values?.containsKey(PathlineContract.Concepts.NAME) == true) {
            values.getAsString(PathlineContract.Concepts.NAME).also {
                require(!it.isNullOrBlank()) { "'${PathlineContract.Concepts.NAME}' cannot be cleared" }
            }
        } else {
            null
        }
        val setKind = values?.containsKey(PathlineContract.Concepts.KIND) == true
        val setDescription = values?.containsKey(PathlineContract.Concepts.DESCRIPTION) == true
        val archived = if (values?.containsKey(PathlineContract.Concepts.ARCHIVED) == true) {
            values.getAsBoolean(PathlineContract.Concepts.ARCHIVED)
                ?: throw IllegalArgumentException(
                    "'${PathlineContract.Concepts.ARCHIVED}' must be a boolean",
                )
        } else {
            null
        }
        val updated = runBlocking {
            val store = entryPoint.conceptStore()
            val intrinsic = store.update(
                id,
                displayName = name,
                kind = values?.getAsString(PathlineContract.Concepts.KIND), setKind = setKind,
                description = values?.getAsString(PathlineContract.Concepts.DESCRIPTION),
                setDescription = setDescription,
                writer = caller.pkg,
            )
            if (archived != null && intrinsic != null) {
                store.setArchived(id, archived, caller.pkg)
            } else {
                intrinsic
            }
        }
        val count = if (updated != null) 1 else 0
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPTS,
                startMs = now,
                endMs = now,
                rowCount = count,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        if (count > 0) notifyChanged(uri)
        return count
    }

    /** `delete` on `concepts/<id>`: the concept, its memberships and its own annotations. */
    private fun deleteConcept(caller: Caller, uri: Uri, now: Long): Int {
        val id = targetIdFrom(uri)
        val groupId = checkWrite(
            caller, uri, AnnotationTarget.CONCEPT, id,
            PathlineContract.Permissions.WRITE_ANNOTATIONS, DATA_TYPE_CONCEPTS, now,
        )
        val removed = runBlocking { if (entryPoint.conceptStore().delete(id)) 1 else 0 }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPTS,
                startMs = now,
                endMs = now,
                rowCount = removed,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** `insert` on `concepts/<id>/members`: attach one place/visit/trip/concept. The member must
     *  be visible/writable to the caller (its read tier + [ApiGate.targetVisible]) so ephemeral
     *  unconfirmed ids never enter a concept; a `concept` member that would close a membership
     *  cycle is rejected in the store. */
    private fun insertConceptMember(
        caller: Caller,
        uri: Uri,
        values: ContentValues?,
        now: Long
    ): Uri {
        val conceptId = targetIdFrom(uri)
        val target = parseMemberType(
            values?.getAsString(PathlineContract.Concepts.Members.TARGET_TYPE),
        )
        val targetId = values?.getAsLong(PathlineContract.Concepts.Members.TARGET_ID)
            ?: throw IllegalArgumentException(
                "Missing '${PathlineContract.Concepts.Members.TARGET_ID}' value",
            )
        // Gate on the MEMBER target (its read tier + visibility); concept existence is checked in
        // the store. This is the same writability rule as annotating the target directly.
        val groupId = checkWrite(
            caller, uri, target, targetId, PathlineContract.Permissions.WRITE_ANNOTATIONS,
            DATA_TYPE_CONCEPT_MEMBERS, now,
        )
        val added = runBlocking {
            entryPoint.conceptStore().addMember(conceptId, target, targetId, caller.pkg)
        }
        if (!added) throw IllegalArgumentException("No concept with id $conceptId")
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPT_MEMBERS,
                startMs = now,
                endMs = now,
                rowCount = 1,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        notifyChanged(uri)
        return PathlineContract.Concepts.memberUri(conceptId, target.name.lowercase(), targetId)
    }

    /** `delete` on `concepts/<id>/members/<type>/<targetId>`: detach one member. Gated on the
     *  concept (not the member) so stale references stay removable. */
    private fun deleteConceptMember(caller: Caller, uri: Uri, now: Long): Int {
        val conceptId = targetIdFrom(uri)
        val segments = uri.pathSegments
        val target = parseMemberType(segments.getOrNull(3))
        val targetId = segments.getOrNull(4)?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing or invalid member id in URI: $uri")
        val groupId = checkWrite(
            caller, uri, AnnotationTarget.CONCEPT, conceptId,
            PathlineContract.Permissions.WRITE_ANNOTATIONS, DATA_TYPE_CONCEPT_MEMBERS, now,
        )
        val removed = runBlocking {
            entryPoint.conceptStore().removeMember(conceptId, target, targetId)
        }
        logger.log(
            caller,
            AccessEvent(
                dataType = DATA_TYPE_CONCEPT_MEMBERS,
                startMs = now,
                endMs = now,
                rowCount = removed,
                nowMs = now,
                groupId = groupId,
                isWrite = true,
            ),
        )
        if (removed > 0) notifyChanged(uri)
        return removed
    }

    /** Parse a [PathlineContract.Concepts.Members.TARGET_TYPE] value; `concept` nests one concept
     *  under another (cycles rejected in the store), anything unknown is an error. */
    private fun parseMemberType(raw: String?): AnnotationTarget = when (raw?.trim()?.lowercase()) {
        "place" -> AnnotationTarget.PLACE
        "visit" -> AnnotationTarget.VISIT
        "trip" -> AnnotationTarget.TRIP
        "concept" -> AnnotationTarget.CONCEPT
        else -> throw IllegalArgumentException(
            "'${PathlineContract.Concepts.Members.TARGET_TYPE}' must be place, visit, trip or concept (got '$raw')",
        )
    }

    /** Parse [PathlineContract.QueryParams.ARCHIVED] (absent -> EXCLUDE); unknown values error. */
    private fun parseArchivedMode(uri: Uri): ArchivedMode =
        when (val raw = uri.getQueryParameter(PathlineContract.QueryParams.ARCHIVED)) {
            null, PathlineContract.QueryParams.ARCHIVED_EXCLUDE -> ArchivedMode.EXCLUDE
            PathlineContract.QueryParams.ARCHIVED_INCLUDE -> ArchivedMode.INCLUDE
            PathlineContract.QueryParams.ARCHIVED_ONLY -> ArchivedMode.ONLY
            else -> throw IllegalArgumentException(
                "'${PathlineContract.QueryParams.ARCHIVED}' must be " +
                        "${PathlineContract.QueryParams.ARCHIVED_EXCLUDE}, " +
                        "${PathlineContract.QueryParams.ARCHIVED_INCLUDE} or " +
                        "${PathlineContract.QueryParams.ARCHIVED_ONLY} (got '$raw')",
            )
        }

    private enum class ArchivedMode { EXCLUDE, INCLUDE, ONLY }

    private fun List<ConceptEntity>.filterArchived(mode: ArchivedMode): List<ConceptEntity> =
        when (mode) {
            ArchivedMode.EXCLUDE -> filter { it.archivedAtMs == null }
            ArchivedMode.INCLUDE -> this
            ArchivedMode.ONLY -> filter { it.archivedAtMs != null }
        }

    /** Wake any consumer cursor watching this URI (reads register it via setNotificationUri). */
    private fun notifyChanged(uri: Uri) {
        runCatching { context?.contentResolver?.notifyChange(uri, null) }
    }

    private companion object {
        const val TAG = "PathlineProvider"

        const val CODE_VISITS = 1
        const val CODE_TRIPS = 2
        const val CODE_SAMPLES = 3
        const val CODE_PLACES = 4
        const val CODE_PLACE_VISITS = 5
        const val CODE_STATUS = 6
        const val CODE_TAGS = 7
        const val CODE_TRAVEL_TIMES = 8

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
        const val CODE_VISIT_ITEM = 25
        const val CODE_TRIP_ITEM = 26
        const val CODE_PLACE_STATS = 39

        const val CODE_CONCEPTS = 27
        const val CODE_CONCEPT_ITEM = 28
        const val CODE_CONCEPT_MEMBERS = 29
        const val CODE_CONCEPT_MEMBER_ITEM = 30
        const val CODE_CONCEPT_TAGS = 31
        const val CODE_CONCEPT_TAG_NAME = 32
        const val CODE_CONCEPT_NOTES = 33
        const val CODE_CONCEPT_MEMORIES = 34
        const val CODE_CONCEPT_MEMORY_KEY = 35
        const val CODE_PLACE_CONCEPTS = 36
        const val CODE_VISIT_CONCEPTS = 37
        const val CODE_TRIP_CONCEPTS = 38
        const val CODE_CONCEPT_CONCEPTS = 40

        // Concepts are full annotation targets: their tags/notes/memories routes ride the same
        // dispatch sets as places/visits/trips.
        val TAGS_CODES = setOf(CODE_PLACE_TAGS, CODE_VISIT_TAGS, CODE_TRIP_TAGS, CODE_CONCEPT_TAGS)
        val TAG_NAME_CODES =
            setOf(
                CODE_PLACE_TAG_NAME,
                CODE_VISIT_TAG_NAME,
                CODE_TRIP_TAG_NAME,
                CODE_CONCEPT_TAG_NAME
            )
        val NOTES_CODES =
            setOf(CODE_PLACE_NOTES, CODE_VISIT_NOTES, CODE_TRIP_NOTES, CODE_CONCEPT_NOTES)
        val MEMORIES_CODES =
            setOf(
                CODE_PLACE_MEMORIES,
                CODE_VISIT_MEMORIES,
                CODE_TRIP_MEMORIES,
                CODE_CONCEPT_MEMORIES
            )
        val MEMORY_KEY_CODES = setOf(
            CODE_PLACE_MEMORY_KEY, CODE_VISIT_MEMORY_KEY, CODE_TRIP_MEMORY_KEY,
            CODE_CONCEPT_MEMORY_KEY,
        )
        val CONCEPTS_FOR_TARGET_CODES =
            setOf(
                CODE_PLACE_CONCEPTS,
                CODE_VISIT_CONCEPTS,
                CODE_TRIP_CONCEPTS,
                CODE_CONCEPT_CONCEPTS
            )

        /** Audit-log `dataType` tokens for the two annotation payload kinds (tags use [PathlineContract.Tags.PATH]). */
        const val DATA_TYPE_NOTES = PathlineContract.Annotations.NOTES_PATH
        const val DATA_TYPE_MEMORIES = PathlineContract.Annotations.MEMORIES_PATH
        const val DATA_TYPE_CONCEPTS = PathlineContract.Concepts.PATH
        const val DATA_TYPE_CONCEPT_MEMBERS = "concept_members"

        /** MIME types of the single-item annotation URIs (delete-only; not part of the contract). */
        const val ITEM_TYPE_TAG =
            "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.tag"
        const val ITEM_TYPE_MEMORY =
            "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.memory"
        const val ITEM_TYPE_CONCEPT_MEMBER =
            "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.concept_member"

        const val READ_ONLY_MESSAGE =
            "Pathline's data API is read-only except the annotation sub-collections (see PathlineContract.Annotations)"

        /** Upper bound for a memory entry's `source` provenance note (a pointer, not a document). */
        const val MAX_MEMORY_SOURCE_LENGTH = 500

        /** Small grace for clock skew; departure/arrival times must otherwise not be in the past. */
        const val TRAVEL_PAST_SKEW_MS = 5L * 60 * 1000
        const val TRAVEL_FUTURE_WINDOW_MS = 100L * 24 * 60 * 60 * 1000
    }
}
