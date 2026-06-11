package net.extrawdw.apps.locationhistory.api

/**
 * The identity of one data-API request, captured **once** at the provider entry point
 * (`query`/`insert`/`update`/`delete`) and threaded explicitly through every layer below.
 *
 * `ContentProvider.getCallingPackage()` and the binder permission check are ambient state of the
 * calling binder thread: reading them deep inside a call chain is correct only while everything
 * stays synchronous on that thread, and one dispatcher hop would silently re-attribute the request
 * to Pathline itself. Capturing them here makes the attribution explicit and hop-proof.
 *
 * @param pkg the calling package, or null when the platform could not name one (attribution then
 *   logs as "unknown" and [byMe] reports "a different app" for any attributed writer).
 * @param holds whether the calling app holds a (runtime or install-time) permission. The lambda is
 *   bound to the entry point's binder identity; evaluate it freely.
 */
internal class Caller(val pkg: String?, val holds: (String) -> Boolean) {

    /** The package name as recorded in the audit log and the grant ledger. */
    val pkgOrUnknown: String get() = pkg ?: "unknown"

    /** The nullable-boolean `*_by_me` encoding: 1 = the calling app wrote it, 0 = a different app,
     *  null = Pathline itself (in-app user edit, maintenance fold, or pre-attribution data). */
    fun byMe(writer: String?): Int? = writer?.let { if (it == pkg) 1 else 0 }

    /** Whether the caller may see trip routes (the polyline column). */
    fun routeUnlocked(): Boolean =
        holds(PathlineContract.Permissions.READ_TIMELINE_ROUTES) ||
                holds(PathlineContract.Permissions.READ_LOCATION_HISTORY)
}

/**
 * One audit-log row, named-field form. [PathlineProvider] used to thread these ten values as
 * positional parameters through ~40 call sites with adjacent same-typed nullables — a swapped pair
 * logs wrong audit rows and nothing fails. Construction is named-argument-only by convention;
 * the defaults cover the common "denied nothing, plain read" case.
 */
internal data class AccessEvent(
    /** Which collection was touched — the audit log's `dataType` token. */
    val dataType: String,
    /** The time window the caller requested (zero-width at "now" for windowless routes). */
    val startMs: Long,
    val endMs: Long,
    /** Rows returned (reads) or rows/links/keys changed (writes); 0 for a denial. */
    val rowCount: Int,
    /** When the access happened (epoch ms). */
    val nowMs: Long,
    /** Validated batch-correlation key, or null (see [PathlineContract.QueryParams.GROUP]). */
    val groupId: Long? = null,
    /** Trips only: whether the route column was withheld. Null where not applicable. */
    val routeWithheld: Boolean? = null,
    /** The missing permission a valid-but-unauthorized request was denied for; null on success. */
    val deniedPermission: String? = null,
    /** False only for denials while the whole API switch is off (logged, never alerted). */
    val notify: Boolean = true,
    /** True when this event was an annotation write rather than a read. */
    val isWrite: Boolean = false,
)

/** Sink for [AccessEvent]s — the provider appends to the audit log and schedules the user-facing
 *  access notification; tests record. Must never throw into a caller's read. */
internal fun interface AccessLogger {
    fun log(caller: Caller, event: AccessEvent)
}
