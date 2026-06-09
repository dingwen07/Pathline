package net.extrawdw.apps.locationhistory.api

import android.content.Intent
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
 * - `places` (saved-place details: name, address) and a place's `…/visits` history also require
 *   [Permissions.READ_TIMELINE]. They are **access-scoped**: a caller only sees a place — its details
 *   or its history — once it has read a **confirmed** [Visits] row referencing that place (see [Places]).
 *   A place's history older than 30 days additionally requires [Permissions.READ_EXTENDED_HISTORY], as below.
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

    /** Pathline's application id — the target package for [Actions] intents. */
    const val PACKAGE: String = "net.extrawdw.apps.locationhistory"

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

        /**
         * Optional comma-separated list of place ids to fetch from the [Places] collection (e.g.
         * `ids=12,40,7`). When omitted, [Places] returns every place the caller is allowed to see.
         * Either way the result is intersected with the caller's allowed set — ids the caller has not
         * encountered through a [Visits] read are silently omitted, never an error. Ignored by the
         * other collections.
         */
        const val IDS: String = "ids"
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

    /**
     * Saved places — the named, addressed locations behind the user's visits.
     *
     * Unlike the time-windowed collections above, this one is **scoped to what the caller has already
     * seen**: it returns only places the caller previously encountered as a [Visits.PLACE_ID] on a
     * **confirmed** [Visits] row in an authorized read (an unconfirmed visit — even one matched to a
     * saved place — does not grant access on its own). A place the caller has not been granted is not
     * returned (an unfiltered query simply omits it; a [QueryParams.IDS] filter naming it omits that id) — so this
     * endpoint never widens what an app can learn, it only lets it resolve the **details** (name,
     * address) of places it has already touched, **once**, instead of re-sending them on every visit.
     *
     * Not time-windowed: [QueryParams.START] / [QueryParams.END] are ignored. Pass [QueryParams.IDS]
     * to fetch specific places, or omit it for all allowed places. Requires [Permissions.READ_TIMELINE].
     *
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.place`.
     */
    object Places {
        const val PATH: String = "places"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.place"

        /** Stable place id (the cursor's `_id`); matches [Visits.PLACE_ID]. */
        const val ID: String = "_id"

        /** Human name of the place. */
        const val NAME: String = "name"

        /** Postal / street address, or null when the place has none. */
        const val ADDRESS: String = "address"

        /** Free-form category, or null. */
        const val CATEGORY: String = "category"

        /** How the place was created: `USER`, `MAPS`, or `INFERRED`. */
        const val SOURCE: String = "source"

        /** Google Places id this place is linked to, or null. */
        const val GOOGLE_PLACE_ID: String = "google_place_id"

        /** Place center latitude. */
        const val LATITUDE: String = "latitude"

        /** Place center longitude. */
        const val LONGITUDE: String = "longitude"

        /** Approximate radius of the place in meters. */
        const val RADIUS_METERS: String = "radius_meters"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(
            ID, NAME, ADDRESS, CATEGORY, SOURCE, GOOGLE_PLACE_ID,
            LATITUDE, LONGITUDE, RADIUS_METERS,
        )

        /** Sub-collection path segment for a place's visit history (see [visitHistoryUri]). */
        const val VISITS_PATH: String = "visits"

        /** The `dataType` recorded in the access log for a [VisitHistory] read (distinct from [PATH]). */
        const val VISITS_DATA_TYPE: String = "place_visits"

        /** URI for the visit history of one place: `content://…/places/<placeId>/visits`. */
        @JvmStatic
        fun visitHistoryUri(placeId: Long): Uri =
            CONTENT_URI.buildUpon().appendPath(placeId.toString()).appendPath(VISITS_PATH).build()

        /**
         * A single place's **visit history**: every visit attributed to the place in the path, oldest
         * first, queried at `content://…/places/<placeId>/visits`.
         *
         * These rows are intentionally **lean** — they carry no place name or address, because the
         * place identity is the path's `<placeId>` and its details come once from the parent [Places]
         * row. This avoids re-transferring the same saved-place info on every history row.
         *
         * Windowed like [Visits]: [QueryParams.START] is **required** (epoch ms) and [QueryParams.END]
         * defaults to now, with the same overlap semantics. Requires [Permissions.READ_TIMELINE], and —
         * like any read reaching past [EXTENDED_HISTORY_WINDOW_MS] — [Permissions.READ_EXTENDED_HISTORY]
         * for a window starting more than 30 days ago (pass `start=0` for the whole history). A place the
         * caller is not allowed to see returns no rows.
         *
         * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.place_visit`.
         */
        object VisitHistory {
            const val CONTENT_TYPE: String =
                "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.place_visit"

            /** Stable visit id (the cursor's `_id`). */
            const val ID: String = "_id"

            /** Start of the stay, epoch milliseconds. */
            const val START_MS: String = "start_ms"

            /** End of the stay, epoch milliseconds. Equal to "now" for an ongoing visit. */
            const val END_MS: String = "end_ms"

            /** Id of the place (constant across the result — equals the path's `<placeId>`). */
            const val PLACE_ID: String = "place_id"

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
                ID, START_MS, END_MS, PLACE_ID, LATITUDE, LONGITUDE,
                RADIUS_METERS, CONFIDENCE, CONFIRMED, IS_ONGOING,
            )
        }
    }

    /**
     * Read-only status of the data API, for a consumer to check before reading. Returns a single row
     * reporting whether the user's access switch is on ([ACCESS_ENABLED]).
     *
     * Unlike the data collections it is **always answerable** — it needs no runtime permission, is not
     * gated by the access switch ([ACCESS_ENABLED] is exactly what it reports), and is not recorded in
     * the access log (it returns no personal data). Reaching it still requires the install-time
     * [Permissions.API] gate like the rest of the provider. A consumer uses it to decide whether to read
     * or to prompt the user to turn the API on (see [Actions.REQUEST_API_ACCESS]). MIME type:
     * `vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.status`.
     */
    object Status {
        const val PATH: String = "status"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.status"

        /** 1 when the user has turned third-party data access on, else 0 (all data reads are denied). */
        const val ACCESS_ENABLED: String = "access_enabled"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(ACCESS_ENABLED)
    }

    /** Intents a consumer can fire toward Pathline. */
    object Actions {
        /**
         * Ask the user to turn third-party data access on. Launch this **for a result**
         * (`startActivityForResult` / an `ActivityResultLauncher` with [requestApiAccessIntent]) to open
         * Pathline's full-screen onboarding — it shows your app's name and icon and explains the switch.
         * The user makes the choice; you are returned to **right where you were**.
         *
         * Result: `RESULT_OK` when access ends up **on**, `RESULT_CANCELED` otherwise; the result intent
         * also carries [EXTRA_ACCESS_ENABLED]. If access is already on, it returns `RESULT_OK`
         * immediately without prompting. (You can also just re-read [Status.ACCESS_ENABLED] on resume.)
         */
        const val REQUEST_API_ACCESS: String =
            "net.extrawdw.apps.locationhistory.action.REQUEST_API_ACCESS"

        /** Boolean result extra on a [REQUEST_API_ACCESS] result: the access-switch state afterward. */
        const val EXTRA_ACCESS_ENABLED: String = "access_enabled"

        /** [REQUEST_API_ACCESS] as an explicit intent targeted at Pathline. */
        @JvmStatic
        fun requestApiAccessIntent(): Intent =
            Intent(REQUEST_API_ACCESS).setPackage(PACKAGE)
    }
}
