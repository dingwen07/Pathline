package net.extrawdw.apps.locationhistory.api

import android.net.Uri

/**
 * Public contract for Pathline's on-device data API.
 *
 * Other apps installed on the same device can read the user's timeline (visits + trips) and raw
 * recorded location samples through a [android.content.ContentProvider] exposed at [AUTHORITY].
 *
 * Reaching the provider at all requires the install-time [Permissions.API] permission: a consumer
 * declares `<uses-permission>` for it and the system auto-grants it (no runtime prompt). An app that
 * does not declare it cannot resolve or query the provider. Past that gate, access to data is gated
 * by four custom runtime permissions (see [Permissions]); a consumer must declare the ones it needs
 * with `<uses-permission>` AND request them at runtime — the user approves each in the system
 * permission dialog, exactly like the platform location permissions.
 *
 * This file is intentionally self-contained and dependency-free so it can be copied verbatim into a
 * consumer app. Nothing here is loaded by Pathline at runtime beyond the constant values; the
 * provider implementation lives in [PathlineProvider].
 *
 * ## Querying
 * Every collection is queried by passing the inclusive-start / exclusive-end of a time window as
 * query parameters on the content URI:
 *
 * ```
 * content://net.extrawdw.apps.locationhistory.provider/visits?start=<epochMs>&end=<epochMs>
 * ```
 *
 * - [QueryParams.START] is required (epoch milliseconds, UTC).
 * - [QueryParams.END] is optional and defaults to "now".
 *
 * Timeline items (visits, trips) use **overlap** semantics: any visit or trip whose span intersects
 * the requested window is returned, even if it started before `start` or ends after `end`. So a
 * request landing in the middle of a stay or a journey returns that whole item.
 *
 * Samples use **point-in-window** semantics: a sample is returned when
 * `start <= timestamp_ms < end`.
 *
 * ## Permission tiers
 * - `visits` / `trips` require [Permissions.READ_TIMELINE].
 * - A trip's [Trips.ENCODED_POLYLINE] (the precise route) is only populated when the caller also
 *   holds [Permissions.READ_TIMELINE_ROUTE] **or** [Permissions.READ_LOCATION_HISTORY]; otherwise
 *   that single column comes back `null` while the rest of the trip row is returned normally. A
 *   timeline-only caller therefore sees trips but not their tracks.
 * - `samples` require [Permissions.READ_LOCATION_HISTORY].
 * - Reading anything **older than 30 days** (a window whose `start` predates `now - 30 days`)
 *   additionally requires [Permissions.READ_EXTENDED_HISTORY]. Without it the query throws a
 *   [SecurityException]; the requested window is never silently narrowed.
 */
object PathlineContract {

    /** Authority of the Pathline data provider. Matches `applicationId` + `.provider`. */
    const val AUTHORITY: String = "net.extrawdw.apps.locationhistory.provider"

    private val BASE: Uri = Uri.parse("content://$AUTHORITY")

    /** Time window (in milliseconds) that is always readable with only the base permissions. A
     *  window reaching further back than this needs [Permissions.READ_EXTENDED_HISTORY]. */
    const val EXTENDED_HISTORY_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** A [QueryParams.GROUP] value is honoured only when it is within this window of the request's
     *  receipt time; older/invalid values are ignored and the read is logged ungrouped. */
    const val GROUP_WINDOW_MS: Long = 120_000

    /** Query-string parameters accepted on every collection URI. */
    object QueryParams {
        /** Inclusive start of the window, epoch milliseconds. Required. */
        const val START: String = "start"

        /** Exclusive end of the window, epoch milliseconds. Optional; defaults to the current time. */
        const val END: String = "end"

        /**
         * Optional batch-correlation key: a **wall-clock epoch-ms** value at/near "now" (within
         * [GROUP_WINDOW_MS] of when the request is received). Reads that carry the same value from the
         * same app — across endpoints — are shown as one expandable group in Pathline's access manager.
         * Invalid, stale, or far-future values are ignored (the read is still logged, just ungrouped).
         * It never affects what data is returned, only how the access is displayed.
         */
        const val GROUP: String = "group"
    }

    /** The custom permissions a consumer declares. [API] is the install-time gate for the whole
     *  provider; the rest are runtime permissions requested per data tier. */
    object Permissions {
        /**
         * Coarse, install-time gate for the entire provider (`protectionLevel="normal"`). The system
         * auto-grants it to any app that declares `<uses-permission>` for it — no runtime prompt. An
         * app that does not declare it cannot resolve or query the provider at all. It is necessary
         * but not sufficient: every read still requires the relevant runtime permission(s) below.
         */
        const val API: String =
            "net.extrawdw.apps.locationhistory.permission.API"

        /** Read timeline items: [Visits] and [Trips] (trip routes excluded — see below). */
        const val READ_TIMELINE: String =
            "net.extrawdw.apps.locationhistory.permission.READ_TIMELINE"

        /**
         * Unlocks the [Trips.ENCODED_POLYLINE] column (a trip's precise route). Requested in addition
         * to [READ_TIMELINE]. Grouped with [READ_LOCATION_HISTORY] (a precise-path sensitivity tier),
         * and [READ_LOCATION_HISTORY] alone also unlocks the route — a sample reader can reconstruct
         * it regardless.
         */
        const val READ_TIMELINE_ROUTE: String =
            "net.extrawdw.apps.locationhistory.permission.READ_TIMELINE_ROUTE"

        /** Read raw recorded [Samples]. Also unlocks [Trips.ENCODED_POLYLINE]. */
        const val READ_LOCATION_HISTORY: String =
            "net.extrawdw.apps.locationhistory.permission.READ_LOCATION_HISTORY"

        /** Required in addition to the above to read anything older than 30 days. */
        const val READ_EXTENDED_HISTORY: String =
            "net.extrawdw.apps.locationhistory.permission.READ_EXTENDED_HISTORY"
    }

    /**
     * Stays at a place. Returned for any visit overlapping the requested window.
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.visit`.
     */
    object Visits {
        const val PATH: String = "visits"
        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.visit"

        /** Stable visit id (the cursor's `_id`). */
        const val ID: String = "_id"

        /** Start of the stay, epoch milliseconds. */
        const val START_MS: String = "start_ms"

        /** End of the stay, epoch milliseconds. Equal to "now" for an ongoing visit. */
        const val END_MS: String = "end_ms"

        /** Id of the matched place, or null when the visit is unconfirmed / has only a candidate. */
        const val PLACE_ID: String = "place_id"

        /** Best available human name: the matched place's name, else the candidate name, else null. */
        const val PLACE_NAME: String = "place_name"

        /** Visit centroid latitude. */
        const val LATITUDE: String = "latitude"

        /** Visit centroid longitude. */
        const val LONGITUDE: String = "longitude"

        /** Approximate radius of the stay in meters. */
        const val RADIUS_METERS: String = "radius_meters"

        /** Place-attribution confidence in [0,1]. */
        const val CONFIDENCE: String = "confidence"

        /** 1 when the user has confirmed the place attribution, else 0. */
        const val CONFIRMED: String = "confirmed"

        /** 1 when this is the still-open current visit, else 0. */
        const val IS_ONGOING: String = "is_ongoing"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(
            ID, START_MS, END_MS, PLACE_ID, PLACE_NAME, LATITUDE, LONGITUDE,
            RADIUS_METERS, CONFIDENCE, CONFIRMED, IS_ONGOING,
        )
    }

    /**
     * Single-mode movements between stays. Returned for any trip overlapping the requested window.
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.trip`.
     */
    object Trips {
        const val PATH: String = "trips"
        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.trip"

        /** Stable trip id (the cursor's `_id`). */
        const val ID: String = "_id"

        /** Start of the movement, epoch milliseconds. */
        const val START_MS: String = "start_ms"

        /** End of the movement, epoch milliseconds. */
        const val END_MS: String = "end_ms"

        /**
         * Transport mode, one of: `WALKING`, `RUNNING`, `CYCLING`, `CAR`, `BUS`, `RAIL`, `FERRY`,
         * `FLIGHT`, `UNKNOWN`.
         */
        const val MODE: String = "mode"

        /** Confidence of the [MODE] classification in [0,1]. */
        const val MODE_CONFIDENCE: String = "mode_confidence"

        /** Total distance traveled in meters. */
        const val DISTANCE_METERS: String = "distance_meters"

        /**
         * The route as a Google-format encoded polyline (precision 5), or `null` when the caller
         * lacks both [Permissions.READ_TIMELINE_ROUTE] and [Permissions.READ_LOCATION_HISTORY].
         * Consumers must handle a null value (a timeline-only caller always sees null here).
         */
        const val ENCODED_POLYLINE: String = "encoded_polyline"

        /** 1 when the user has confirmed the trip's mode, else 0. */
        const val CONFIRMED: String = "confirmed"

        /** Visit id this movement departs from, or null. */
        const val FROM_VISIT_ID: String = "from_visit_id"

        /** Visit id this movement arrives at, or null. */
        const val TO_VISIT_ID: String = "to_visit_id"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(
            ID, START_MS, END_MS, MODE, MODE_CONFIDENCE, DISTANCE_METERS,
            ENCODED_POLYLINE, CONFIRMED, FROM_VISIT_ID, TO_VISIT_ID,
        )
    }

    /**
     * Raw recorded location fixes. A sample is returned when `start <= timestamp_ms < end`.
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.sample`.
     */
    object Samples {
        const val PATH: String = "samples"
        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.sample"

        /** Stable sample id (the cursor's `_id`). */
        const val ID: String = "_id"

        /** Time of the fix, epoch milliseconds. */
        const val TIMESTAMP_MS: String = "timestamp_ms"
        const val LATITUDE: String = "latitude"
        const val LONGITUDE: String = "longitude"

        /** Altitude in meters, or null if unavailable. */
        const val ALTITUDE: String = "altitude"

        /** Horizontal accuracy radius in meters, or null. */
        const val ACCURACY: String = "accuracy"

        /** Bearing in degrees, or null. */
        const val BEARING: String = "bearing"

        /** Ground speed in meters/second, or null. */
        const val SPEED: String = "speed"

        /** Location provider that produced the fix (e.g. `fused`, `gps`), or null. */
        const val PROVIDER: String = "provider"

        /** 1 if the fix was reported as a mock location, else 0. */
        const val IS_MOCK: String = "is_mock"

        /**
         * Classified physical state at the time of the fix, one of: `STATIONARY`, `WALKING`,
         * `RUNNING`, `CYCLING`, `IN_VEHICLE`, `UNKNOWN`.
         */
        const val DEVICE_STATE: String = "device_state"

        /** Raw Activity-Recognition activity string, or null. */
        const val AR_ACTIVITY: String = "ar_activity"

        /** Active network transport (`WIFI`, `CELLULAR`, ...), or null. */
        const val NETWORK_TRANSPORT: String = "network_transport"

        /** 1 when this fix is used in timeline computation, 0 when excluded (e.g. mock / drift). */
        const val INCLUDED_IN_COMPUTATION: String = "included_in_computation"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(
            ID,
            TIMESTAMP_MS,
            LATITUDE,
            LONGITUDE,
            ALTITUDE,
            ACCURACY,
            BEARING,
            SPEED,
            PROVIDER,
            IS_MOCK,
            DEVICE_STATE,
            AR_ACTIVITY,
            NETWORK_TRANSPORT,
            INCLUDED_IN_COMPUTATION,
        )
    }
}
