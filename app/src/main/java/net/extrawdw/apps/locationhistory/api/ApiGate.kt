package net.extrawdw.apps.locationhistory.api

import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantDao
import net.extrawdw.apps.locationhistory.data.db.ConceptDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao

/**
 * The data API's security kernel: permission enforcement, target-visibility scoping, and
 * window/parameter validation, extracted from [PathlineProvider] so it is a plain class over DAO
 * interfaces — constructible with in-memory fakes and unit-testable on the JVM.
 *
 * Every method takes the request's [Caller] explicitly (captured once at the provider entry
 * point); nothing in here reads ambient binder state. Denials are logged through [logger] before
 * throwing, exactly like the provider always did: a well-formed request the caller isn't
 * authorized for is recorded as a denied event (rowCount 0) and then rejected — the window is
 * never silently narrowed.
 *
 * Free of Android types on purpose ([PathlineProvider] parses its `Uri`s and passes raw values),
 * with one exception: `SecurityException` / `IllegalArgumentException` are the contract's
 * documented failure modes and remain the throw types.
 */
internal class ApiGate(
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val placeDao: PlaceDao,
    private val conceptDao: ConceptDao,
    private val tagDao: TagDao,
    private val grantDao: ApiPlaceGrantDao,
    /** The user's single access switch (see [PathlineContract.Status.ACCESS_ENABLED]). */
    private val accessEnabled: () -> Boolean,
    private val logger: AccessLogger,
) {

    // ---- Denial & permission enforcement -----------------------------------------------------

    /** Log a denied event for [permission] and throw. The one path every denial goes through. */
    fun deny(
        caller: Caller,
        permission: String,
        dataType: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        groupId: Long?,
        isWrite: Boolean = false,
    ): Nothing {
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = startMs,
                endMs = endMs,
                rowCount = 0,
                nowMs = nowMs,
                groupId = groupId,
                deniedPermission = permission,
                isWrite = isWrite,
            ),
        )
        throw SecurityException("Caller must hold $permission")
    }

    /** Enforce one permission: no-op when held, logged denial + [SecurityException] otherwise. */
    fun requirePermission(
        caller: Caller,
        permission: String,
        dataType: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        groupId: Long?,
        isWrite: Boolean = false,
    ) {
        if (caller.holds(permission)) return
        deny(caller, permission, dataType, startMs, endMs, nowMs, groupId, isWrite)
    }

    /**
     * The single access switch gating every data route. When off, no per-app permission matters:
     * the denial is logged (so the audit trail is honest) but does NOT notify the user — an app
     * reading while the user has turned the whole API off is not a per-app breach worth alerting
     * on. Attributed to the install-time API gate.
     */
    fun requireApiEnabled(
        caller: Caller,
        dataType: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        groupId: Long?,
        isWrite: Boolean = false,
    ) {
        if (accessEnabled()) return
        logger.log(
            caller,
            AccessEvent(
                dataType = dataType,
                startMs = startMs,
                endMs = endMs,
                rowCount = 0,
                nowMs = nowMs,
                groupId = groupId,
                deniedPermission = PathlineContract.Permissions.API,
                notify = false,
                isWrite = isWrite,
            ),
        )
        throw SecurityException("Third-party access to Pathline data is turned off")
    }

    /** The gate for an explicitly-windowed read: the route's base permission, plus extended
     *  history when the window reaches past the 30-day horizon. Explicit windows throw rather
     *  than clamp. */
    fun requireWindowedRead(
        caller: Caller,
        basePermission: String,
        dataType: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        groupId: Long?,
    ) {
        requirePermission(caller, basePermission, dataType, startMs, endMs, nowMs, groupId)
        if (startMs < nowMs - PathlineContract.EXTENDED_HISTORY_WINDOW_MS) {
            requirePermission(
                caller, PathlineContract.Permissions.READ_EXTENDED_HISTORY,
                dataType, startMs, endMs, nowMs, groupId,
            )
        }
    }

    /** Permissions shared by all per-target annotation reads: READ_ANNOTATIONS plus the read tier
     *  the target type lives under. Logs + throws on the first one missing. */
    fun requireAnnotationRead(
        caller: Caller,
        target: AnnotationTarget,
        dataType: String,
        nowMs: Long,
        groupId: Long?,
    ) {
        fun deny(permission: String): Nothing =
            deny(caller, permission, dataType, nowMs, nowMs, nowMs, groupId)
        if (!caller.holds(PathlineContract.Permissions.READ_ANNOTATIONS)) {
            deny(PathlineContract.Permissions.READ_ANNOTATIONS)
        }
        requireTargetReadTier(caller, target, ::deny)
    }

    /** The read tier a target type lives under — one mapping shared by [requireAnnotationRead] and
     *  [checkWrite] so the two gates can't drift. Calls [deny] (which logs + throws) on a miss. */
    private fun requireTargetReadTier(
        caller: Caller,
        target: AnnotationTarget,
        deny: (String) -> Nothing,
    ) {
        when (target) {
            AnnotationTarget.PLACE ->
                if (!caller.holds(PathlineContract.Permissions.READ_ALL_PLACES) &&
                    !caller.holds(PathlineContract.Permissions.READ_TIMELINE)
                ) {
                    deny(PathlineContract.Permissions.READ_TIMELINE)
                }

            AnnotationTarget.VISIT, AnnotationTarget.TRIP ->
                if (!caller.holds(PathlineContract.Permissions.READ_TIMELINE)) {
                    deny(PathlineContract.Permissions.READ_TIMELINE)
                }

            // Concepts are curation, not recorded data: the annotation permission alone suffices.
            AnnotationTarget.CONCEPT -> Unit
        }
    }

    // ---- Target visibility ---------------------------------------------------------------------

    /**
     * Whether one annotation target is visible to (and writable by) the caller: a granted/existing
     * place (any place under READ_ALL_PLACES), or a **confirmed** visit/trip inside the allowed
     * window — the last 30 days unless the caller holds READ_EXTENDED_HISTORY. An invisible target
     * reads as empty and rejects writes, indistinguishable from a nonexistent one.
     */
    suspend fun targetVisible(
        caller: Caller,
        target: AnnotationTarget,
        id: Long,
        nowMs: Long
    ): Boolean =
        when (target) {
            AnnotationTarget.PLACE -> {
                val inScope = caller.holds(PathlineContract.Permissions.READ_ALL_PLACES) ||
                        grantDao.isGranted(caller.pkgOrUnknown, id)
                inScope && placeDao.byId(id) != null
            }

            AnnotationTarget.VISIT -> visitDao.byId(id)
                ?.let { it.confirmed && it.endMs > minVisibleEndMs(caller, nowMs) } == true

            AnnotationTarget.TRIP -> tripDao.byId(id)
                ?.let { it.confirmed && it.endMs > minVisibleEndMs(caller, nowMs) } == true

            // Any existing concept — visible to every annotation reader (see the contract).
            AnnotationTarget.CONCEPT -> conceptDao.byId(id) != null
        }

    /** The end-time floor a visit/trip must reach to be visible on a windowless read (clamp). */
    fun minVisibleEndMs(caller: Caller, nowMs: Long): Long =
        if (caller.holds(PathlineContract.Permissions.READ_EXTENDED_HISTORY)) Long.MIN_VALUE
        else nowMs - PathlineContract.EXTENDED_HISTORY_WINDOW_MS

    /**
     * Ids of every tag attached to at least one target visible to the caller. The tag links live in
     * the encrypted DB and the place grants in the audit DB, so the scoping joins in code: load the
     * (small, user-curated) link table whole, then keep links whose target the caller can see.
     */
    suspend fun visibleTagIds(caller: Caller, nowMs: Long): Set<Long> {
        val links = tagDao.allLinks()
        if (links.isEmpty()) return emptySet()
        val result = HashSet<Long>()
        val minEnd = minVisibleEndMs(caller, nowMs)

        val placeLinks = links.filter { it.targetType == AnnotationTarget.PLACE }
        if (placeLinks.isNotEmpty()) {
            val placeIds = placeLinks.map { it.targetId }.distinct()
            val visible: Set<Long> = when {
                caller.holds(PathlineContract.Permissions.READ_ALL_PLACES) -> placeIds.toSet()
                caller.holds(PathlineContract.Permissions.READ_TIMELINE) ->
                    grantDao.grantedAmong(caller.pkgOrUnknown, placeIds).toSet()

                else -> emptySet()
            }
            placeLinks.filter { it.targetId in visible }.mapTo(result) { it.tagId }
        }
        if (caller.holds(PathlineContract.Permissions.READ_TIMELINE)) {
            val visitLinks = links.filter { it.targetType == AnnotationTarget.VISIT }
            if (visitLinks.isNotEmpty()) {
                val visible = visitDao
                    .confirmedIdsAmong(visitLinks.map { it.targetId }.distinct(), minEnd).toSet()
                visitLinks.filter { it.targetId in visible }.mapTo(result) { it.tagId }
            }
            val tripLinks = links.filter { it.targetType == AnnotationTarget.TRIP }
            if (tripLinks.isNotEmpty()) {
                val visible = tripDao
                    .confirmedIdsAmong(tripLinks.map { it.targetId }.distinct(), minEnd).toSet()
                tripLinks.filter { it.targetId in visible }.mapTo(result) { it.tagId }
            }
        }
        // Concept-attached tags: every existing concept is visible to an annotation reader.
        val conceptLinks = links.filter { it.targetType == AnnotationTarget.CONCEPT }
        if (conceptLinks.isNotEmpty()) {
            val existing = conceptDao
                .byIds(conceptLinks.map { it.targetId }.distinct()).map { it.id }.toSet()
            conceptLinks.filter { it.targetId in existing }.mapTo(result) { it.tagId }
        }
        return result
    }

    // ---- Write gating ----------------------------------------------------------------------------

    /**
     * Common gate for an annotation write: the access switch, the write permission for [dataType]'s
     * tier, the read tier the target type lives under, and the target's visibility (see
     * [targetVisible] — an unconfirmed visit/trip is never writable; an invisible target rejects
     * indistinguishably from a nonexistent one). Throws after logging the denial.
     */
    suspend fun checkWrite(
        caller: Caller,
        target: AnnotationTarget,
        id: Long,
        permission: String,
        dataType: String,
        nowMs: Long,
        groupId: Long?,
    ) {
        requireApiEnabled(caller, dataType, nowMs, nowMs, nowMs, groupId, isWrite = true)
        fun deny(p: String): Nothing =
            deny(caller, p, dataType, nowMs, nowMs, nowMs, groupId, isWrite = true)
        if (!caller.holds(permission)) deny(permission)
        requireTargetReadTier(caller, target, ::deny)
        if (!targetVisible(caller, target, id, nowMs)) {
            // A write aimed at nothing: logged (audit honesty) and rejected. The message must not
            // reveal whether the row exists.
            logger.log(
                caller,
                AccessEvent(
                    dataType = dataType,
                    startMs = nowMs,
                    endMs = nowMs,
                    rowCount = 0,
                    nowMs = nowMs,
                    groupId = groupId,
                    isWrite = true,
                ),
            )
            throw IllegalArgumentException(
                "No writable ${target.name.lowercase()} with id $id (missing, unconfirmed, or not accessible)",
            )
        }
    }

    /** The access-switch + WRITE_ANNOTATIONS gate for concept writes that have no target row yet
     *  (create). Mirrors [checkWrite]'s logging. */
    fun checkConceptCollectionWrite(caller: Caller, dataType: String, nowMs: Long, groupId: Long?) {
        requireApiEnabled(caller, dataType, nowMs, nowMs, nowMs, groupId, isWrite = true)
        if (!caller.holds(PathlineContract.Permissions.WRITE_ANNOTATIONS)) {
            deny(
                caller, PathlineContract.Permissions.WRITE_ANNOTATIONS,
                dataType, nowMs, nowMs, nowMs, groupId, isWrite = true,
            )
        }
    }

    // ---- Window & parameter parsing --------------------------------------------------------------

    /**
     * The required explicit window of the chronological collections: [startRaw] must parse (epoch
     * ms), [endRaw] defaults to now, end must exceed start. Returns start to end as a [Pair].
     */
    fun requireWindow(startRaw: String?, endRaw: String?, nowMs: Long): Pair<Long, Long> {
        val start = startRaw?.toLongOrNull()
            ?: throw IllegalArgumentException(
                "Missing required '${PathlineContract.QueryParams.START}' query parameter (epoch ms)",
            )
        val end = endRaw?.toLongOrNull() ?: nowMs
        require(end > start) { "'${PathlineContract.QueryParams.END}' must be greater than '${PathlineContract.QueryParams.START}'" }
        return start to end
    }

    /**
     * The window of a visit/trip **search**, where `start` is optional: an explicit start is
     * enforced exactly like a plain read (end > start; extended history required past the horizon
     * — logged + thrown here); when omitted the window **clamps** to what the caller's permissions
     * allow (all time with extended history, else the last 30 days).
     */
    fun searchWindow(
        caller: Caller,
        startRaw: String?,
        endRaw: String?,
        dataType: String,
        nowMs: Long,
        groupId: Long?,
    ): Pair<Long, Long> {
        val end = endRaw?.toLongOrNull() ?: nowMs
        val explicitStart = startRaw?.toLongOrNull()
        if (explicitStart != null) {
            require(end > explicitStart) {
                "'${PathlineContract.QueryParams.END}' must be greater than '${PathlineContract.QueryParams.START}'"
            }
            if (explicitStart < nowMs - PathlineContract.EXTENDED_HISTORY_WINDOW_MS &&
                !caller.holds(PathlineContract.Permissions.READ_EXTENDED_HISTORY)
            ) {
                deny(
                    caller, PathlineContract.Permissions.READ_EXTENDED_HISTORY,
                    dataType, explicitStart, end, nowMs, groupId,
                )
            }
            return explicitStart to end
        }
        val start = if (caller.holds(PathlineContract.Permissions.READ_EXTENDED_HISTORY)) 0
        else nowMs - PathlineContract.EXTENDED_HISTORY_WINDOW_MS
        return start to end
    }

    /** The optional `limit=` row cap: null when absent, else a positive int clamped to
     *  [MAX_LIMIT]. Non-positive / non-numeric values are an error (never silently ignored). */
    fun parseLimit(raw: String?): Int? {
        if (raw == null) return null
        val n = raw.toIntOrNull()
        require(n != null && n > 0) {
            "'${PathlineContract.QueryParams.LIMIT}' must be a positive integer (got '$raw')"
        }
        return n.coerceAtMost(MAX_LIMIT)
    }

    /** The optional batch-correlation key, honoured only when within the grouping window of now. */
    fun parseGroup(raw: String?, nowMs: Long): Long? {
        val value = raw?.toLongOrNull()
        return value?.takeIf {
            it in (nowMs - PathlineContract.GROUP_WINDOW_MS)..(nowMs + GROUP_FUTURE_TOLERANCE_MS)
        }
    }

    companion object {
        /** Server-side ceiling for the `limit=` row cap — larger asks clamp, they don't error. */
        const val MAX_LIMIT = 5_000

        /** Small tolerance for a `group` value slightly ahead of our clock (granularity), no more. */
        const val GROUP_FUTURE_TOLERANCE_MS = 5_000L
    }
}
