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
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
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
        fun workScheduler(): WorkScheduler
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(PathlineContract.AUTHORITY, PathlineContract.Visits.PATH, CODE_VISITS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Trips.PATH, CODE_TRIPS)
        addURI(PathlineContract.AUTHORITY, PathlineContract.Samples.PATH, CODE_SAMPLES)
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
        val rawGroup = uri.getQueryParameter(PathlineContract.QueryParams.GROUP)?.toLongOrNull()
        val groupId = rawGroup?.takeIf {
            it in (now - PathlineContract.GROUP_WINDOW_MS)..(now + GROUP_FUTURE_TOLERANCE_MS)
        }

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

        /** Small tolerance for a `group` value slightly ahead of our clock (granularity), no more. */
        const val GROUP_FUTURE_TOLERANCE_MS = 5_000L
    }
}
