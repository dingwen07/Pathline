package net.extrawdw.apps.locationhistory.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.data.db.ApiAccessDao
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantDao
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantEntity
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.work.WorkScheduler

/**
 * Read-only on-device API exposing the user's timeline and recorded samples to other apps.
 *
 * See [PathlineContract] for the public surface (URIs, columns, permissions, query semantics). The
 * provider is exported but every collection enforces a custom runtime permission on the calling app,
 * and any window reaching past [PathlineContract.EXTENDED_HISTORY_WINDOW_MS] additionally requires
 * the extended-history permission. The provider is strictly read-only: insert/update/delete throw.
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
        fun apiAccessDao(): ApiAccessDao
        fun apiPlaceGrantDao(): ApiPlaceGrantDao
        fun settingsRepository(): SettingsRepository
        fun workScheduler(): WorkScheduler
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(PathlineContract.AUTHORITY, PathlineContract.Visits.PATH, CODE_VISITS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Trips.PATH, CODE_TRIPS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Samples.PATH, CODE_SAMPLES)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Places.PATH, CODE_PLACES)
        addURI(
            PathlineContract.AUTHORITY,
            "${PathlineContract.Places.PATH}/#/${PathlineContract.Places.VISITS_PATH}",
            CODE_PLACE_VISITS,
        )
        addURI(PathlineContract.AUTHORITY, PathlineContract.Status.PATH, CODE_STATUS)
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

        // The place collections aren't time-windowed the same way; they branch off before the
        // required-`start` parsing below.
        when (code) {
            CODE_PLACES -> return placesQuery(uri, now)
            CODE_PLACE_VISITS -> return placeVisitsQuery(uri, now)
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
        val routeWithheld: Boolean? = if (code == CODE_TRIPS) {
            !(holds(PathlineContract.Permissions.READ_TIMELINE_ROUTE) ||
                    holds(PathlineContract.Permissions.READ_LOCATION_HISTORY))
        } else {
            null
        }

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
                    ),
                )
            }
        }
        // Alert the user about this access — a successful read OR a denied (unauthorized) attempt, on
        // separate per-app back-off lanes. Coalesced + rate-limited by WorkManager / the worker.
        // Skipped (logged-only) for denials while the whole API is switched off — see [query].
        if (!notify) return
        runCatching {
            entryPoint.workScheduler()
                .enqueueApiAccessNotification(pkg, denied = deniedPermission != null)
        }
    }

    private fun visitsCursor(start: Long, end: Long): Cursor = runBlocking {
        val visits = entryPoint.visitDao().overlapping(start, end)
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
        cursor
    }

    private fun tripsCursor(start: Long, end: Long, includeRoute: Boolean): Cursor = runBlocking {
        val trips = entryPoint.tripDao().overlapping(start, end)
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
        cursor
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

    /**
     * `places` collection: saved-place details (name, address) for places the caller has already seen.
     * Not time-windowed; gated by [PathlineContract.Permissions.READ_TIMELINE] and scoped to the
     * caller's place grants (built up by its [visitsCursor] reads). `start`/`end` are ignored, so the
     * audit row records a zero-width window at "now" — this read is not a time range.
     */
    private fun placesQuery(uri: Uri, now: Long): Cursor {
        val groupId = parseGroup(uri, now)
        if (!holds(PathlineContract.Permissions.READ_TIMELINE)) {
            logAccess(
                PathlineContract.Places.PATH, now, now, rowCount = 0, now, groupId,
                routeWithheld = null,
                deniedPermission = PathlineContract.Permissions.READ_TIMELINE,
            )
            throw SecurityException("Caller must hold ${PathlineContract.Permissions.READ_TIMELINE}")
        }
        val cursor = placesCursor(uri)
        logAccess(
            PathlineContract.Places.PATH, now, now, cursor.count, now, groupId,
            routeWithheld = null, deniedPermission = null,
        )
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    /**
     * `places/<id>/visits` collection: one place's visit history, as lean rows (no place name/address
     * — the caller resolves those once from the parent place). Gated by READ_TIMELINE, with
     * READ_EXTENDED_HISTORY required for any portion older than 30 days (an all-time history, the
     * default when no `start` is given, needs it). Scoped to the caller's place grants.
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

    private fun placesCursor(uri: Uri): Cursor = runBlocking {
        val pkg = callingPackage ?: "unknown"
        val grantDao = entryPoint.apiPlaceGrantDao()
        val requested = uri.getQueryParameter(PathlineContract.QueryParams.IDS)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.distinct()
        // Scope: only places this app has already encountered through an authorized `visits` read.
        // An unfiltered query returns all of them; a filter is intersected with the allowed set.
        val allowed = when {
            requested == null -> grantDao.grantedPlaceIds(pkg)
            requested.isEmpty() -> emptyList()
            else -> grantDao.grantedAmong(pkg, requested)
        }
        val places = if (allowed.isEmpty()) emptyList() else entryPoint.placeDao().byIds(allowed)
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
        cursor
    }

    private fun placeVisitsCursor(placeId: Long, start: Long, end: Long): Cursor = runBlocking {
        val pkg = callingPackage ?: "unknown"
        // Scope: a place's history is readable only if this app already saw the place in a `visits`
        // read. An unseen (or non-existent) place returns nothing — indistinguishable, so the caller
        // cannot probe for ids it was never shown.
        val visits = if (entryPoint.apiPlaceGrantDao().isGranted(pkg, placeId)) {
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
        else -> "?"
    }

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

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Pathline's data API is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Pathline's data API is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Pathline's data API is read-only")

    private companion object {
        const val CODE_VISITS = 1
        const val CODE_TRIPS = 2
        const val CODE_SAMPLES = 3
        const val CODE_PLACES = 4
        const val CODE_PLACE_VISITS = 5
        const val CODE_STATUS = 6

        /** Small tolerance for a `group` value slightly ahead of our clock (granularity), no more. */
        const val GROUP_FUTURE_TOLERANCE_MS = 5_000L
    }
}
