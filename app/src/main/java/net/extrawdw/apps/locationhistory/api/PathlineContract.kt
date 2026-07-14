package net.extrawdw.apps.locationhistory.api

import android.content.Intent
import android.net.Uri

/**
 * Public contract for Pathline's on-device data API.
 *
 * Other apps installed on the same device can read the user's timeline (visits + trips), raw
 * recorded location samples, saved places and user annotations (tags, notes, memories), search
 * them, and — with the write permissions — maintain annotations on the user's behalf, through a
 * [android.content.ContentProvider] exposed at [AUTHORITY].
 *
 * Reaching the provider at all requires the install-time [Permissions.API] permission: a consumer
 * declares `<uses-permission>` for it and the system auto-grants it (no runtime prompt). An app that
 * does not declare it cannot resolve or query the provider. Past that gate, access to data is gated
 * by custom runtime permissions (see [Permissions]); a consumer must declare the ones it needs
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
 *   [Permissions.READ_ALL_PLACES] bypasses that scoping and exposes the whole saved-place corpus.
 *   A place's history older than 30 days additionally requires [Permissions.READ_EXTENDED_HISTORY], as below.
 * - A trip's [Trips.ENCODED_POLYLINE] (the precise route) is only populated when the caller also
 *   holds [Permissions.READ_TIMELINE_ROUTES] **or** [Permissions.READ_LOCATION_HISTORY]; otherwise
 *   that single column comes back `null` while the rest of the trip row is returned normally. A
 *   timeline-only caller therefore sees trips but not their tracks.
 * - `samples` require [Permissions.READ_LOCATION_HISTORY].
 * - Annotations — [Tags] and the per-target `…/tags`, `…/notes`, `…/memories` sub-collections (see
 *   [Annotations]) — require [Permissions.READ_ANNOTATIONS] on top of the target's read tier.
 * - Search (`q=` on `places` / `visits` / `trips` / `tags`, see [QueryParams.Q]) requires
 *   [Permissions.SEARCH_DATA]; what it can match and return is still scoped by the read
 *   permissions held.
 * - Annotation **writes** (`insert` / `update` / `delete` on the annotation URIs — every other URI
 *   stays read-only) require [Permissions.WRITE_ANNOTATIONS] for tags and memories, or
 *   [Permissions.WRITE_ANNOTATIONS_NOTES] for notes. See [Annotations].
 * - [Concepts] (semantic groups) read with [Permissions.READ_ANNOTATIONS] **alone** — they are
 *   curation, not recorded data; member references are id pointers resolved through the
 *   normally-gated collections. Concept writes (create/edit/delete, add/remove members) require
 *   [Permissions.WRITE_ANNOTATIONS]; **adding** a member is additionally gated on the member
 *   target's own read tier + visibility (an unconfirmed visit/trip is rejected), while removing
 *   one is gated on the concept only, so stale references stay removable.
 * - The `near=` proximity mode on `places` ([QueryParams.NEAR]) adds no permission of its own and
 *   never widens scope — it filters and orders what the caller's place scope already allows.
 *   Likewise `limit=` ([QueryParams.LIMIT]) only caps row counts, on any collection that
 *   documents it.
 * - `travel_times` computes Google Maps travel-time estimates (driving, walking, bicycling,
 *   two-wheeler, or public transit) between two saved places. It requires the caller to be able to
 *   read both places: [Permissions.READ_ALL_PLACES], or [Permissions.READ_TIMELINE] plus existing
 *   per-place grants for both ids. It does not expose raw route geometry, stops, or the user's
 *   recorded movement, and the user can disable it entirely in the access manager.
 * - Reading anything **older than 30 days** (a window whose `start` predates `now - 30 days`)
 *   additionally requires [Permissions.READ_EXTENDED_HISTORY]. For a call with an **explicit**
 *   `start` that far back, the query throws a [SecurityException] without it — an explicitly
 *   requested window is never silently narrowed. The windowless reads (places, tags, annotations,
 *   and search without `start`) instead **clamp**: they return what the last 30 days permit, and the
 *   whole history with the permission.
 */
object PathlineContract {

    /** Pathline's application id — the target package for [Actions] intents. */
    const val PACKAGE: String = "net.extrawdw.apps.locationhistory"

    /** Authority of the Pathline data provider. Matches `applicationId` + `.provider`. */
    const val AUTHORITY: String = "net.extrawdw.apps.locationhistory.provider"

    /**
     * Version of this contract, reported live by the provider as [Status.API_VERSION]. A consumer
     * compares it against the constant its bundled contract copy was built from: a **lower** value
     * from the provider means the installed Pathline predates columns/URIs this copy documents
     * (degrade gracefully — don't query what it can't know); a **higher** value is fine (the API
     * only grows; existing columns keep their meaning once a version ships). History: 1 = timeline
     * + samples + places; 2 = places types / visit-history / search / annotations; 3 = memory
     * entry metadata (per-entry stamps + `*_by_me` attribution) + concepts (incl. archive —
     * [Concepts.ARCHIVED_AT_MS] / [Concepts.ARCHIVED_BY_ME] + [QueryParams.ARCHIVED] — and
     * nesting: `concept` as a [Concepts.Members.TARGET_TYPE] + the `concepts/<id>/concepts`
     * reverse listing) + [PlaceStats] + relevance-ordered search (see [QueryParams.Q]) +
     * proximity mode ([QueryParams.NEAR]) + [QueryParams.LIMIT] + [Concepts.MEMBER_COUNT] on
     * concept rows. Nothing past version 2 has shipped in a release yet, so additions keep
     * folding into 3 until one does.
     */
    const val API_VERSION: Int = 3

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
         * Optional comma-separated list of row ids (e.g. `ids=12,40,7`). On [Places] it selects
         * specific saved places (when omitted, [Places] returns every place the caller is allowed
         * to see). On [Visits] and [Trips] it switches the collection into **batch id resolve** —
         * the multi-id form of [Visits.itemUri] / [Trips.itemUri], one read instead of N — and
         * [START] / [END] become optional exactly like search mode (omitted -> the window clamps
         * per [Permissions.READ_EXTENDED_HISTORY]; explicit -> enforced like a plain read), with
         * rows returned chronological as always. Everywhere the result is intersected with what
         * the caller may see — ids out of scope, outside the window, or nonexistent are silently
         * omitted, never an error. Composable with [Q] (the search result is narrowed to the ids).
         * Ignored by the remaining collections.
         */
        const val IDS: String = "ids"

        /**
         * Search query text. Its presence switches the `places`, `visits`, `trips`, `tags` and
         * `concepts` collections into **search mode** (requires [Permissions.SEARCH_DATA]): instead of a plain
         * listing, only rows matching the text — in the fields selected by [FIELDS] — are returned,
         * in the collection's normal row shape. Matching is case-insensitive; FTS-backed fields
         * (place name/address/category/types, tag names) match on word prefixes, annotation text
         * matches on substrings. A query of **several words** (whitespace/comma-separated) matches
         * rows containing **any** of them — OR semantics, each word as its own prefix/substring;
         * common connector words (`and`, `or`, `the`, ...) are ignored when other words remain, so
         * `bakery and cafe` means bakery|cafe. Wrap the whole query in double quotes
         * (`"south lions"`) to require the exact phrase instead. In search mode [START]/[END]
         * become optional on `visits`/`trips`:
         * when omitted the window clamps per [Permissions.READ_EXTENDED_HISTORY]; an explicit window
         * is enforced exactly like a plain read. See [SearchFields] for the matchable fields.
         *
         * **Ordering:** `places` / `tags` / `concepts` search results are returned
         * most-relevant-first (FTS bm25; rows matched only through annotation fields follow the
         * ranked rows). `visits` / `trips` search results stay **chronological** — they are
         * timeline data and callers display them in time order.
         */
        const val Q: String = "q"

        /**
         * Optional comma-separated list of fields to match in search mode (e.g.
         * `places?q=coffee&fields=name,category`); see [SearchFields] for the valid names per
         * collection. When omitted, every field the caller's permissions allow is matched. Naming an
         * unknown field is an error; explicitly naming an annotation field ([SearchFields.TAGS],
         * [SearchFields.NOTES], [SearchFields.MEMORIES]) without [Permissions.READ_ANNOTATIONS]
         * throws a [SecurityException] (when [FIELDS] is omitted those fields are simply skipped).
         */
        const val FIELDS: String = "fields"

        /**
         * Exact-match filter on the [Concepts] collection by [Concepts.KIND] (e.g. `kind=trip`).
         * The value is canonicalized like the stored kinds, so `Trip`/`TRIP`/`trip` are the same
         * filter. Composable with [Q] (the search result is then narrowed to the kind). Ignored by
         * every other collection.
         */
        const val KIND: String = "kind"

        /**
         * Archived-concept visibility on the [Concepts] collection and the per-target
         * `…/<id>/concepts` reverse listings: one of [ARCHIVED_EXCLUDE] (the default — archived
         * concepts are omitted), [ARCHIVED_INCLUDE] (everything; tell them apart via
         * [Concepts.ARCHIVED_AT_MS]) or [ARCHIVED_ONLY] (archived concepts only — the review /
         * unarchive listing). Any other value is an error. Composable with [Q] / [KIND] / [IDS]
         * (the filter applies to their result). The single-item `concepts/<id>` read is **not**
         * affected — a direct get always returns the row, archived or not (the columns say which).
         * Ignored by every other collection. Part of the version-3 batch; older providers ignore
         * it (listings then include archived rows — none can exist on a pre-archive provider).
         */
        const val ARCHIVED: String = "archived"

        /** [ARCHIVED] value: omit archived concepts (the default when the param is absent). */
        const val ARCHIVED_EXCLUDE: String = "exclude"

        /** [ARCHIVED] value: include archived concepts alongside active ones. */
        const val ARCHIVED_INCLUDE: String = "include"

        /** [ARCHIVED] value: archived concepts only. */
        const val ARCHIVED_ONLY: String = "only"

        /**
         * Proximity filter on the [Places] collection: a canonical **WGS-84** `lat,lng` point
         * (decimal degrees, e.g. `near=40.7235,-74.0354`; never a Google map/provider-frame
         * coordinate). Only places within [RADIUS_M] meters are returned, **ordered
         * nearest-first** with [Places.DISTANCE_M] populated. Composable with [Q] (matches are
         * intersected; nearest-first ordering wins) and [IDS]. Scope is unchanged — the caller's
         * granted places, or the corpus under [Permissions.READ_ALL_PLACES]; proximity never
         * widens visibility. Malformed coordinates are an error. Ignored by every other
         * collection.
         */
        const val NEAR: String = "near"

        /**
         * Radius for [NEAR], meters. Optional — defaults to 500; values above 50 km clamp (never
         * an error); non-positive or non-numeric values are an error. Ignored without [NEAR].
         */
        const val RADIUS_M: String = "radius_m"

        /**
         * Optional row cap, a positive integer (values above 5000 clamp; non-positive or
         * non-numeric values are an error). Applies after scoping, search matching and ordering:
         * chronological reads — [Visits], [Trips], [Samples], a place's visit history, and
         * visit/trip search — keep the **most recent** rows (the returned order itself is
         * unchanged, still oldest-first); ranked or named listings — [Places] (incl. search and
         * [NEAR] mode), [Tags], [Concepts], [PlaceStats] — keep the **first** rows of their
         * documented order. Ignored by the per-target annotation sub-collections, concept members
         * and [Status].
         */
        const val LIMIT: String = "limit"
    }

    /**
     * The field names accepted in [QueryParams.FIELDS], per collection:
     *
     * - `places`: [NAME], [ADDRESS], [CATEGORY], [TYPES] (place details), plus [TAGS], [NOTES],
     *   [MEMORIES] (annotation fields, gated by [Permissions.READ_ANNOTATIONS]). A memories match
     *   considers both keys and values.
     * - `visits` / `trips`: [PLACE_NAME] (the saved place or candidate name attributed to the
     *   visit, or to the trip's endpoint visits), plus [TAGS] and [NOTES] of the visit/trip itself.
     * - `tags`: matches tag names only; [QueryParams.FIELDS] is ignored.
     * - `concepts`: matches concept names, kinds and descriptions, plus the concept's own [TAGS],
     *   [NOTES] and [MEMORIES] (like `places` — every concept read holds [Permissions.READ_ANNOTATIONS],
     *   so these are always matched); [QueryParams.FIELDS] is ignored.
     */
    object SearchFields {
        const val NAME: String = "name"
        const val ADDRESS: String = "address"
        const val CATEGORY: String = "category"
        const val TYPES: String = "types"
        const val PLACE_NAME: String = "place_name"
        const val TAGS: String = "tags"
        const val NOTES: String = "notes"
        const val MEMORIES: String = "memories"
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
        const val READ_TIMELINE_ROUTES: String =
            "net.extrawdw.apps.locationhistory.permission.READ_TIMELINE_ROUTES"

        /** Read raw recorded [Samples]. Also unlocks [Trips.ENCODED_POLYLINE]. */
        const val READ_LOCATION_HISTORY: String =
            "net.extrawdw.apps.locationhistory.permission.READ_LOCATION_HISTORY"

        /**
         * Required in addition to the above to read anything older than 30 days. A windowless read
         * (places, tags, annotations, search without `start`) never requires it — it clamps instead;
         * see the class doc.
         */
        const val READ_EXTENDED_HISTORY: String =
            "net.extrawdw.apps.locationhistory.permission.READ_EXTENDED_HISTORY"

        /**
         * Read the **entire** saved-place corpus through [Places] (and search it), bypassing the
         * per-place grant scoping that [READ_TIMELINE] readers build up visit by visit. Place
         * **visit history** is still timeline data and still needs [READ_TIMELINE].
         */
        const val READ_ALL_PLACES: String =
            "net.extrawdw.apps.locationhistory.permission.READ_ALL_PLACES"

        /**
         * Read [Annotations] — tags, notes and memories — of any target the caller can otherwise
         * see (a granted place, or a confirmed visit/trip under [READ_TIMELINE]).
         */
        const val READ_ANNOTATIONS: String =
            "net.extrawdw.apps.locationhistory.permission.READ_ANNOTATIONS"

        /**
         * Create/update/delete **tags and memories** on visible targets (see [Annotations]). The
         * structured, agent-friendly write tier. Notes are deliberately carved out into
         * [WRITE_ANNOTATIONS_NOTES] — they are human prose. The two write permissions share one
         * permission **group** (a single "edit my annotations" sensitivity tier): once the user
         * grants either, the other — if declared — is granted without a second prompt, and they
         * are revoked together. The tags+memories vs notes split is enforced by **declaration**:
         * an app that declares only this permission never gains note writes.
         */
        const val WRITE_ANNOTATIONS: String =
            "net.extrawdw.apps.locationhistory.permission.WRITE_ANNOTATIONS"

        /** Create/update/delete **notes** on visible targets (see [Annotations]). Grouped with
         *  [WRITE_ANNOTATIONS] — granted/revoked together once either is approved; see there. */
        const val WRITE_ANNOTATIONS_NOTES: String =
            "net.extrawdw.apps.locationhistory.permission.WRITE_ANNOTATIONS_NOTES"

        /**
         * Use the search endpoints ([QueryParams.Q]). Gates the mechanism only: the **scope** of
         * what a search can match and return is governed by the read permissions held —
         * [READ_TIMELINE] for visit/trip hits, [READ_ALL_PLACES] for place coverage beyond grants,
         * [READ_ANNOTATIONS] for matching/returning annotation fields.
         */
        const val SEARCH_DATA: String =
            "net.extrawdw.apps.locationhistory.permission.SEARCH_DATA"
    }

    /**
     * Stays at a place. Returned for any visit overlapping the requested window. With
     * [QueryParams.IDS] the collection batch-resolves specific visits by id instead (window
     * optional — see there).
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.visit`.
     */
    object Visits {
        const val PATH: String = "visits"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.visit"
        const val ITEM_CONTENT_TYPE: String =
            "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.visit"

        /**
         * `content://…/visits/<id>` — ONE visit by its stable id (0 or 1 row, same columns), the
         * resolver for stored id references such as a memory source `visit:675 note`. Requires
         * [Permissions.READ_TIMELINE]; visible within the last 30 days (any age with
         * [Permissions.READ_EXTENDED_HISTORY]), confirmed and unconfirmed alike — if a collection
         * or search read returned the id, this resolves it. An invisible or nonexistent id
         * returns no rows, indistinguishable on purpose.
         */
        @JvmStatic
        fun itemUri(id: Long): Uri = CONTENT_URI.buildUpon().appendPath(id.toString()).build()

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
     * With [QueryParams.IDS] the collection batch-resolves specific trips by id instead (window
     * optional — see there).
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.trip`.
     */
    object Trips {
        const val PATH: String = "trips"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.trip"
        const val ITEM_CONTENT_TYPE: String =
            "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.trip"

        /**
         * `content://…/trips/<id>` — ONE trip by its stable id (0 or 1 row, same columns; route
         * column per the caller's route permissions). Same gating and visibility rules as
         * [Visits.itemUri].
         */
        @JvmStatic
        fun itemUri(id: Long): Uri = CONTENT_URI.buildUpon().appendPath(id.toString()).build()

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
         * lacks both [Permissions.READ_TIMELINE_ROUTES] and [Permissions.READ_LOCATION_HISTORY].
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
     * A caller holding [Permissions.READ_ALL_PLACES] bypasses that scoping entirely and reads the
     * whole saved-place corpus.
     *
     * Not time-windowed: [QueryParams.START] / [QueryParams.END] are ignored. Pass [QueryParams.IDS]
     * to fetch specific places, or omit it for all allowed places. With [QueryParams.Q] it becomes a
     * place search (see [QueryParams.Q]). Requires [Permissions.READ_TIMELINE] (grant-scoped) or
     * [Permissions.READ_ALL_PLACES] (full corpus).
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

        /** Primary free-form category (the place's main Google type), or null. */
        const val CATEGORY: String = "category"

        /** Comma-separated full list of the place's Google types, or null. [CATEGORY] is the first. */
        const val TYPES: String = "types"

        /** How the place was created: `USER`, `MAPS`, or `INFERRED`. */
        const val SOURCE: String = "source"

        /** Google Places id this place is linked to, or null. */
        const val GOOGLE_PLACE_ID: String = "google_place_id"

        /** Canonical WGS-84 place-center latitude. */
        const val LATITUDE: String = "latitude"

        /** Canonical WGS-84 place-center longitude. */
        const val LONGITUDE: String = "longitude"

        /** Coordinate provenance. Returned rows are `WGS84_CANONICAL`; unresolved rows are omitted. */
        const val COORDINATE_STATE: String = "coordinate_state"

        /** Approximate radius of the place in meters. */
        const val RADIUS_METERS: String = "radius_meters"

        /**
         * Great-circle distance from the [QueryParams.NEAR] point, meters. **Null except in
         * proximity mode** — populated only when the query carried `near=`, where rows are also
         * ordered by it ascending.
         */
        const val DISTANCE_M: String = "distance_m"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(
            ID, NAME, ADDRESS, CATEGORY, TYPES, SOURCE, GOOGLE_PLACE_ID,
            LATITUDE, LONGITUDE, COORDINATE_STATE, RADIUS_METERS, DISTANCE_M,
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
     * Google Maps travel-time estimates between two Pathline saved places visible to the caller.
     *
     * This endpoint is intentionally place-id-only: callers cannot submit arbitrary coordinates or
     * addresses, so Pathline does not become a general routing proxy. If the caller lacks the base
     * place-read tier ([Permissions.READ_TIMELINE] or [Permissions.READ_ALL_PLACES]) the query throws
     * [SecurityException]. If either requested place is outside the caller's place scope, missing, the
     * user has disabled routing in the access manager, or Google cannot find a route, the query
     * returns no rows.
     *
     * The returned rows are route summaries, not navigation instructions: travel mode, duration
     * (and a traffic-free [STATIC_DURATION_SECONDS] for driving), distance, approximate route-level
     * departure/arrival times, transit modes actually used, optional localized fare/distance/duration
     * text, and required provider attribution.
     *
     * Query parameters:
     * - [ORIGIN_PLACE_ID] and [DESTINATION_PLACE_ID] are required Pathline saved-place ids.
     * - [TRAVEL_MODE] is optional: `DRIVE` (default), `WALK`, `BICYCLE`, `TWO_WHEELER`, or `TRANSIT`.
     * - [DEPARTURE_TIME_MS] or [ARRIVAL_TIME_MS] may be supplied, but not both, and must not be in
     *   the past. When neither is supplied Google uses "now". [ARRIVAL_TIME_MS] is only honored for
     *   `TRANSIT`.
     * - [MODES] (TRANSIT only) is an optional comma-separated list of preferred transit sub-modes:
     *   `BUS,SUBWAY,TRAIN,LIGHT_RAIL,RAIL`. These are preferences, not hard guarantees; the
     *   [TRANSIT_MODES] result column reports what Google actually used.
     * - [ROUTING_PREFERENCE] (TRANSIT only) is optional: `LESS_WALKING` or `FEWER_TRANSFERS`.
     * - [TRAFFIC] (DRIVE / TWO_WHEELER only) is optional: `TRAFFIC_UNAWARE`, `TRAFFIC_AWARE`, or
     *   `TRAFFIC_AWARE_OPTIMAL`.
     * - [AVOID_TOLLS], [AVOID_HIGHWAYS], [AVOID_FERRIES] (DRIVE / TWO_WHEELER only) may be `1`/`true`.
     * - [ALTERNATIVES] may be `1`/`true` to request alternate routes.
     */
    object TravelTimes {
        const val PATH: String = "travel_times"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.travel_time"

        const val ORIGIN_PLACE_ID: String = "origin_place_id"
        const val DESTINATION_PLACE_ID: String = "destination_place_id"
        const val TRAVEL_MODE: String = "travel_mode"
        const val DEPARTURE_TIME_MS: String = "departure_time_ms"
        const val ARRIVAL_TIME_MS: String = "arrival_time_ms"
        const val MODES: String = "modes"
        const val ROUTING_PREFERENCE: String = "routing_preference"
        const val TRAFFIC: String = "traffic"
        const val AVOID_TOLLS: String = "avoid_tolls"
        const val AVOID_HIGHWAYS: String = "avoid_highways"
        const val AVOID_FERRIES: String = "avoid_ferries"
        const val ALTERNATIVES: String = "alternatives"
        const val LANGUAGE_CODE: String = "language_code"
        const val REGION_CODE: String = "region_code"

        const val ID: String = "_id"
        const val ROUTE_INDEX: String = "route_index"
        const val DURATION_SECONDS: String = "duration_seconds"
        const val STATIC_DURATION_SECONDS: String = "static_duration_seconds"
        const val DISTANCE_METERS: String = "distance_meters"
        const val ROUTE_DEPARTURE_TIME_MS: String = "route_departure_time_ms"
        const val ROUTE_ARRIVAL_TIME_MS: String = "route_arrival_time_ms"
        const val FIRST_TRANSIT_DEPARTURE_TIME_MS: String = "first_transit_departure_time_ms"
        const val LAST_TRANSIT_ARRIVAL_TIME_MS: String = "last_transit_arrival_time_ms"
        const val TRANSIT_MODES: String = "transit_modes"
        const val STEP_TRAVEL_MODES: String = "step_travel_modes"
        const val LOCALIZED_DURATION: String = "localized_duration"
        const val LOCALIZED_DISTANCE: String = "localized_distance"
        const val LOCALIZED_FARE: String = "localized_fare"
        const val PROVIDER_ATTRIBUTION: String = "provider_attribution"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(
            ID,
            ROUTE_INDEX,
            ORIGIN_PLACE_ID,
            DESTINATION_PLACE_ID,
            TRAVEL_MODE,
            DURATION_SECONDS,
            STATIC_DURATION_SECONDS,
            DISTANCE_METERS,
            ROUTE_DEPARTURE_TIME_MS,
            ROUTE_ARRIVAL_TIME_MS,
            FIRST_TRANSIT_DEPARTURE_TIME_MS,
            LAST_TRANSIT_ARRIVAL_TIME_MS,
            TRANSIT_MODES,
            STEP_TRAVEL_MODES,
            LOCALIZED_DURATION,
            LOCALIZED_DISTANCE,
            LOCALIZED_FARE,
            PROVIDER_ATTRIBUTION,
        )
    }

    /**
     * The user's **tags** — short labels attachable to places, visits and trips. Two spellings that
     * differ only in case or word separators are the **same** tag: `My Home`, `my_home` and
     * `My HoME` all canonicalize to `my-home` (lowercase, every run of whitespace/`-`/`_` collapsed
     * to a single `-`), while `myhome` — no separator — stays distinct. [NAME] carries the most
     * recent human spelling, [CANONICAL_NAME] the stable key.
     *
     * Queried at `content://…/tags`, this lists every tag attached to at least one target the
     * caller can see: places it has been granted (or all of them under
     * [Permissions.READ_ALL_PLACES]), and confirmed visits/trips — from the last 30 days only,
     * unless the caller holds [Permissions.READ_EXTENDED_HISTORY]. Not time-windowed
     * ([QueryParams.START]/[QueryParams.END] are ignored). Requires [Permissions.READ_ANNOTATIONS];
     * the visit/trip- and place-attached parts of the scope additionally follow the caller's
     * [Permissions.READ_TIMELINE] / place coverage. With [QueryParams.Q] it becomes a tag-name
     * search (see [QueryParams.Q]; requires [Permissions.SEARCH_DATA]).
     *
     * The same row shape is returned by the per-target `…/tags` sub-collections — see [Annotations].
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.tag`.
     */
    object Tags {
        const val PATH: String = "tags"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.tag"

        /** Stable tag id (the cursor's `_id`). One id per canonical name. */
        const val ID: String = "_id"

        /** The tag's display name — the most recent human spelling written. Also the
         *  [android.content.ContentValues] key for applying a tag (see [Annotations]). */
        const val NAME: String = "name"

        /** The normalized identity key two spellings of the same tag share (see class doc). */
        const val CANONICAL_NAME: String = "canonical_name"

        /** When the tag was first created, epoch milliseconds. */
        const val CREATED_AT_MS: String = "created_at_ms"

        /**
         * Whether the **calling app** created this tag, as a nullable boolean (`1` = the caller,
         * `0` = a different app, null = Pathline itself — the user created it in-app, or it predates
         * attribution). Pathline records the writing package internally; only this three-state flag
         * is exposed. Creation attribution never changes — a re-apply by another app refreshes the
         * spelling but not the creator.
         */
        const val CREATED_BY_ME: String = "created_by_me"

        /**
         * Whether the calling app **attached** this tag to the parent target, same nullable-boolean
         * encoding as [CREATED_BY_ME]. Only meaningful on the per-target `…/tags` sub-collections;
         * always null on the global `tags` listing (a tag can be attached to many targets by many
         * writers).
         */
        const val ATTACHED_BY_ME: String = "attached_by_me"

        @JvmField
        val COLUMNS: Array<String> =
            arrayOf(ID, NAME, CANONICAL_NAME, CREATED_AT_MS, CREATED_BY_ME, ATTACHED_BY_ME)
    }

    /**
     * Per-target **annotations**: the tags, the single free-text note, and the single key→value
     * **memory map** of one place, visit, trip or concept, addressed as sub-collections of the
     * parent row —
     *
     * ```
     * content://…/{places|visits|trips|concepts}/<id>/tags
     * content://…/{places|visits|trips|concepts}/<id>/notes
     * content://…/{places|visits|trips|concepts}/<id>/memories
     * ```
     *
     * (build the URIs with [tagsUri] / [notesUri] / [memoriesUri]). Memories are an agent's
     * structured scratchpad: a **flat** string→string map — values must be plain strings, never
     * nested JSON — where each entry also carries the writer's [Memories.CONFIDENCE] in it.
     * Pathline's own UI shows them to the user read-only.
     *
     * ## Reading
     * Requires [Permissions.READ_ANNOTATIONS], plus the target itself being visible to the caller:
     * a place it has been granted (or [Permissions.READ_ALL_PLACES]), or — under
     * [Permissions.READ_TIMELINE] — a **confirmed** visit/trip no older than 30 days (any age with
     * [Permissions.READ_EXTENDED_HISTORY]), or any existing concept ([Permissions.READ_ANNOTATIONS]
     * alone — see [Concepts]). A target outside that scope returns no rows, indistinguishable from
     * one that doesn't exist. `…/tags` returns [Tags] rows; `…/notes` returns 0 or 1 [Notes] row;
     * `…/memories` returns one [Memories] row per key.
     *
     * ## Writing
     * The annotation sub-collections are the **only** writable URIs in the API; everything else
     * remains read-only. Writes require [Permissions.WRITE_ANNOTATIONS] (tags, memories) or
     * [Permissions.WRITE_ANNOTATIONS_NOTES] (notes), the user's data-access switch being on, and
     * the same target visibility as reads — in particular an **unconfirmed** visit/trip is never
     * writable (its id is ephemeral; Pathline rebuilds unconfirmed timeline rows). Every write is
     * recorded in the user's access log and may notify, like reads.
     *
     * - **Apply a tag:** `insert` on `…/tags` with [Tags.NAME] = the display spelling. Re-applying
     *   an existing tag is a no-op that refreshes the spelling. Returns the link URI.
     * - **Remove a tag:** `delete` on `…/tags/<name>` ([tagUri]; any spelling of the name works).
     *   The tag itself survives for reuse; only the link to this target is removed.
     * - **Set the note:** `insert` (or `update`) on `…/notes` with [Notes.CONTENT] = the text —
     *   replaces the whole note. **Clear it:** `delete` on `…/notes`.
     * - **Put one memory:** `insert` (or `update`) on `…/memories` with [Memories.KEY] and
     *   [Memories.VALUE] (both strings), plus an optional [Memories.CONFIDENCE] (float in [0,1],
     *   default 1.0) — other keys are untouched. **Remove one:** `delete` on `…/memories/<key>`
     *   ([memoryUri]). **Clear the map:** `delete` on `…/memories`.
     *
     * `delete` returns the number of rows/links/keys actually removed (0 when there was nothing).
     *
     * ## Durability
     * Annotations ride Pathline's timeline maintenance: when adjacent confirmed visits/trips are
     * merged, the surviving row inherits the union of both rows' tags and a concatenation of
     * conflicting note/memory text — nothing a consumer wrote is lost. Deleting a place/visit/trip
     * deletes its annotations with it.
     */
    object Annotations {
        /** Sub-collection path segments under a place/visit/trip row. */
        const val TAGS_PATH: String = "tags"
        const val NOTES_PATH: String = "notes"
        const val MEMORIES_PATH: String = "memories"

        /** `content://…/<collection>/<id>/tags` — [collection] is one of [Places.CONTENT_URI],
         *  [Visits.CONTENT_URI], [Trips.CONTENT_URI], [Concepts.CONTENT_URI]. */
        @JvmStatic
        fun tagsUri(collection: Uri, id: Long): Uri =
            collection.buildUpon().appendPath(id.toString()).appendPath(TAGS_PATH).build()

        /** `content://…/<collection>/<id>/tags/<name>` — one tag link, for `delete`. */
        @JvmStatic
        fun tagUri(collection: Uri, id: Long, name: String): Uri =
            tagsUri(collection, id).buildUpon().appendPath(name).build()

        /** `content://…/<collection>/<id>/notes` — the target's single note. */
        @JvmStatic
        fun notesUri(collection: Uri, id: Long): Uri =
            collection.buildUpon().appendPath(id.toString()).appendPath(NOTES_PATH).build()

        /** `content://…/<collection>/<id>/memories` — the target's memory map. */
        @JvmStatic
        fun memoriesUri(collection: Uri, id: Long): Uri =
            collection.buildUpon().appendPath(id.toString()).appendPath(MEMORIES_PATH).build()

        /** `content://…/<collection>/<id>/memories/<key>` — one memory entry, for `delete`. */
        @JvmStatic
        fun memoryUri(collection: Uri, id: Long, key: String): Uri =
            memoriesUri(collection, id).buildUpon().appendPath(key).build()

        /**
         * The target's single free-text **note** (0 or 1 row). MIME type:
         * `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.note`.
         */
        object Notes {
            const val CONTENT_TYPE: String =
                "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.note"

            /** Stable annotation row id (the cursor's `_id`). */
            const val ID: String = "_id"

            /** The note text. Also the [android.content.ContentValues] key for writing it. */
            const val CONTENT: String = "content"

            /** Last modification time, epoch milliseconds. */
            const val UPDATED_AT_MS: String = "updated_at_ms"

            /**
             * Whether the **calling app** last wrote this note, as a nullable boolean (`1` = the
             * caller, `0` = a different app, null = Pathline itself — the user edited it in-app, a
             * timeline merge folded it, or it predates attribution). The writing package is
             * recorded internally; only this three-state flag is exposed.
             */
            const val UPDATED_BY_ME: String = "updated_by_me"

            @JvmField
            val COLUMNS: Array<String> = arrayOf(ID, CONTENT, UPDATED_AT_MS, UPDATED_BY_ME)
        }

        /**
         * The target's **memory map**, one row per key — a single query on `…/memories` returns the
         * whole map at once. Values are always plain strings — a write with anything else is
         * rejected. Each entry additionally carries the writer's [CONFIDENCE] in it. MIME type:
         * `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.memory`.
         */
        object Memories {
            const val CONTENT_TYPE: String =
                "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.memory"

            /** The entry's key. Also a [android.content.ContentValues] key for writing. */
            const val KEY: String = "key"

            /** The entry's string value. Also a [android.content.ContentValues] key for writing. */
            const val VALUE: String = "value"

            /**
             * The writer's confidence in this entry, a float in **[0, 1]**. Optional on writes
             * (defaults to 1.0 — stated as fact); rewriting a key replaces value, confidence and
             * source together. Always present on reads.
             */
            const val CONFIDENCE: String = "confidence"

            /**
             * The writer's free-form **provenance note** for this entry — where the fact came from
             * (e.g. `user statement`, `inferred from visit:675 note`). Optional on writes (omitted
             * = none; rewriting a key replaces it with the new write's source or clears it);
             * nullable on reads. This is the writer's self-description, **not** trusted
             * attribution — the audit log records which app actually wrote. When a timeline merge
             * folds two entries with differing values, their sources join `"a; b"` oldest-first;
             * equal values keep the stronger claim's source.
             */
            const val SOURCE: String = "source"

            /**
             * When **this entry** was last written, epoch milliseconds — the staleness signal
             * beside [CONFIDENCE] (a rewrite of the key refreshes it; writes to other keys don't).
             * When a timeline merge folds two entries, the combined entry keeps the later of the
             * two stamps. **Nullable**: null for entries stored before per-entry stamps existed
             * (no last-write time is known for them).
             */
            const val UPDATED_AT_MS: String = "updated_at_ms"

            /**
             * Whether the **calling app** last wrote this entry, as a nullable boolean (`1` = the
             * caller, `0` = a different app, null = Pathline itself — a timeline merge folded
             * differing values into a composite, or the entry predates attribution). The writing
             * package is recorded internally; only this three-state flag is exposed. When a merge
             * folds two **equal** values, the stronger claim's writer survives with it.
             */
            const val UPDATED_BY_ME: String = "updated_by_me"

            @JvmField
            val COLUMNS: Array<String> =
                arrayOf(KEY, VALUE, CONFIDENCE, SOURCE, UPDATED_AT_MS, UPDATED_BY_ME)
        }
    }

    /**
     * The user's **concepts** — first-class semantic groups ("Japan trip 2026", "my gyms") that
     * places, visits and trips join as **members**. Where a tag is a label (identity = its
     * canonicalized name, auto-created on first apply, carries no data), a concept is an object:
     * identity is its stable [ID], it is explicitly created and deleted, it can be renamed, and it
     * carries data of its own — the intrinsic [KIND] / [DESCRIPTION] columns here, plus its own
     * tags, note and memory map through the regular [Annotations] sub-collections
     * (`concepts/<id>/tags|notes|memories`). Concept **names** still dedup under the same
     * canonical folding as tags (two names differing only in case/separators are the same name —
     * creating a duplicate is an error, not a reuse); [KIND] is a free-form but canonicalized
     * discriminator ("trip", "project", "people") for exact filtering via [QueryParams.KIND].
     *
     * Concepts may **nest**: a concept can be a member of another concept ([Members.TARGET_TYPE]
     * `concept`). Nesting is structural only — membership is stored and listed
     * one level at a time, and **nothing auto-expands it** (a search matching a parent does not
     * match its members; a member listing returns the child's id, not its contents). A membership
     * that would make a concept contain itself, directly or transitively, is rejected with an
     * error. The reverse listing `concepts/<id>/concepts` ([forTargetUri]) answers "which concepts
     * contain this one".
     *
     * Concepts can be **archived** ([ARCHIVED]): a visibility flag, not a delete.
     * An archived concept keeps its members and annotations and stays fully readable by id, but
     * listings, search and the per-target reverse listings omit it unless the caller passes
     * [QueryParams.ARCHIVED]; [ARCHIVED_AT_MS] / [ARCHIVED_BY_ME] say when / who.
     *
     * ## Reading
     * Everything here requires only [Permissions.READ_ANNOTATIONS] — concepts are user/agent
     * curation, not recorded location data, and member references are id pointers the caller
     * resolves through the normally-gated collections (an id it cannot read stays an opaque
     * number).
     * - `concepts` — every concept; with [QueryParams.Q] a search over name/kind/description and
     *   the concept's own tags/notes/memories (requires [Permissions.SEARCH_DATA]); with
     *   [QueryParams.KIND] an exact kind filter;
     *   with [QueryParams.IDS] an id filter. Windowless. Archived concepts are omitted unless
     *   [QueryParams.ARCHIVED] says otherwise.
     * - `concepts/<id>` — one concept row, archived or not (a direct get never filters).
     * - `concepts/<id>/members` — the members, as [Members] rows (see [membersUri]), archived
     *   parent or not.
     * - `…/{places|visits|trips|concepts}/<id>/concepts` — the concepts a visible target belongs
     *   to, as rows of this shape (see [forTargetUri]; target visibility follows [Annotations]
     *   reads). Archived containers are omitted unless [QueryParams.ARCHIVED] says otherwise.
     *
     * ## Writing (requires [Permissions.WRITE_ANNOTATIONS])
     * - **Create:** `insert` on `concepts` with [NAME] (plus optional [KIND], [DESCRIPTION]).
     *   Returns `concepts/<id>`. A name colliding with an existing concept's canonical name is an
     *   **error** (the message names the existing id) — never a silent reuse.
     * - **Edit:** `update` on `concepts/<id>` with any subset of [NAME] / [KIND] / [DESCRIPTION]
     *   (a key present with a null value clears that field; absent keys are untouched). Rename
     *   collisions error like create.
     * - **Archive / unarchive:** `update` on `concepts/<id>` with [ARCHIVED] = `true` / `false`
     *   (combinable with intrinsic edits). Already-in-state is a no-op, not an error.
     * - **Delete:** `delete` on `concepts/<id>` — removes the concept, its memberships (both
     *   directions — also where it is a member of other concepts) and its own annotations; the
     *   members themselves are untouched. Prefer [ARCHIVED] when the user may want it back.
     * - **Add a member:** `insert` on `concepts/<id>/members` with [Members.TARGET_TYPE] +
     *   [Members.TARGET_ID]. The member must be visible/writable to the caller under the
     *   [Annotations] write rules (in particular an unconfirmed visit/trip is rejected — its id is
     *   ephemeral). Re-adding is a no-op keeping the original attached-at/by. A `concept` member
     *   that would close a membership cycle (including the concept itself) is an **error**.
     * - **Remove a member:** `delete` on `concepts/<id>/members/<type>/<targetId>` ([memberUri]).
     *
     * Intrinsic edits (name/kind/description) refresh [UPDATED_AT_MS]/[UPDATED_BY_ME]; membership,
     * annotation and archive changes do **not** — they carry their own attribution. Every write is
     * recorded in the user's access log and may notify, like all annotation writes.
     *
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.concept`.
     */
    object Concepts {
        const val PATH: String = "concepts"
        const val MEMBERS_PATH: String = "members"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.concept"
        const val ITEM_CONTENT_TYPE: String =
            "vnd.android.cursor.item/vnd.net.extrawdw.apps.locationhistory.concept"

        /** Stable concept id (the cursor's `_id`) — the identity; survives renames. */
        const val ID: String = "_id"

        /** Display name (most recent spelling). Also the [android.content.ContentValues] key for
         *  create/rename. Canonical-name uniqueness applies (see class doc). */
        const val NAME: String = "name"

        /** The normalized identity key of the name (same folding as [Tags.CANONICAL_NAME]). */
        const val CANONICAL_NAME: String = "canonical_name"

        /** Optional canonicalized discriminator ("trip", "project"); null = untyped. Also a
         *  [android.content.ContentValues] key (null clears). Filter with [QueryParams.KIND]. */
        const val KIND: String = "kind"

        /** Optional definitional prose — what this concept IS (the commentary belongs in the
         *  concept's note). Also a [android.content.ContentValues] key (null clears). */
        const val DESCRIPTION: String = "description"

        /** When the concept was created, epoch milliseconds. */
        const val CREATED_AT_MS: String = "created_at_ms"

        /** When name/kind/description last changed, epoch milliseconds (membership/annotation
         *  changes don't bump this). */
        const val UPDATED_AT_MS: String = "updated_at_ms"

        /** Whether the calling app created this concept — nullable boolean (`1` = the caller,
         *  `0` = a different app, null = Pathline itself). */
        const val CREATED_BY_ME: String = "created_by_me"

        /** Whether the calling app last edited name/kind/description — same encoding as
         *  [CREATED_BY_ME]. */
        const val UPDATED_BY_ME: String = "updated_by_me"

        /** Whether the calling app attached the parent target to this concept. Only meaningful on
         *  the per-target `…/<id>/concepts` listings; null elsewhere. */
        const val ATTACHED_BY_ME: String = "attached_by_me"

        /**
         * When the concept was archived, epoch milliseconds; null = active. Archived concepts are
         * omitted from listings/search/reverse listings by default — see [QueryParams.ARCHIVED];
         * a direct `concepts/<id>` read always returns the row with this column populated. Also
         * the [android.content.ContentValues] key (as boolean `true`/`false`) to archive /
         * unarchive via `update` — see the class doc. Part of the version-3 batch; absent on
         * older providers.
         */
        const val ARCHIVED_AT_MS: String = "archived_at_ms"

        /** Whether the calling app archived this concept — nullable boolean like [CREATED_BY_ME];
         *  null when active (or archived by Pathline itself). Version-3 batch. */
        const val ARCHIVED_BY_ME: String = "archived_by_me"

        /** The `update` ContentValues key for archive/unarchive: boolean `true` = archive,
         *  `false` = unarchive. Combinable with intrinsic-edit keys in one call. */
        const val ARCHIVED: String = "archived"

        /**
         * Number of members ([Members]) the concept currently has, on every concept row shape —
         * the listing, the single item, and the per-target `…/<id>/concepts` reverse listings —
         * so an agent can see group sizes without a `members` query per concept. Membership is
         * not permission-scoped (members are id pointers; see the class doc), so neither is the
         * count. Part of the version-3 batch; absent on older providers.
         */
        const val MEMBER_COUNT: String = "member_count"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(
            ID, NAME, CANONICAL_NAME, KIND, DESCRIPTION,
            CREATED_AT_MS, UPDATED_AT_MS, CREATED_BY_ME, UPDATED_BY_ME, ATTACHED_BY_ME,
            MEMBER_COUNT, ARCHIVED_AT_MS, ARCHIVED_BY_ME,
        )

        /** `concepts/<id>` — one concept (query / update / delete). */
        @JvmStatic
        fun itemUri(id: Long): Uri = CONTENT_URI.buildUpon().appendPath(id.toString()).build()

        /** `concepts/<id>/members` — the concept's members (query / insert). */
        @JvmStatic
        fun membersUri(id: Long): Uri =
            itemUri(id).buildUpon().appendPath(MEMBERS_PATH).build()

        /** `concepts/<id>/members/<type>/<targetId>` — one membership, for `delete`. [targetType]
         *  is a [Members.TARGET_TYPE] value (`place` / `visit` / `trip` / `concept`). */
        @JvmStatic
        fun memberUri(id: Long, targetType: String, targetId: Long): Uri =
            membersUri(id).buildUpon().appendPath(targetType).appendPath(targetId.toString())
                .build()

        /** `…/{places|visits|trips|concepts}/<id>/concepts` — the concepts [collection]'s row <id>
         *  belongs to. [collection] is one of [Places.CONTENT_URI], [Visits.CONTENT_URI],
         *  [Trips.CONTENT_URI] or — for "which concepts contain this concept" —
         *  [Concepts.CONTENT_URI]. Archived containers are omitted by default
         *  ([QueryParams.ARCHIVED] overrides). */
        @JvmStatic
        fun forTargetUri(collection: Uri, id: Long): Uri =
            collection.buildUpon().appendPath(id.toString()).appendPath(PATH).build()

        /**
         * One **membership** row of `concepts/<id>/members`. MIME type:
         * `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.concept_member`.
         */
        object Members {
            const val CONTENT_TYPE: String =
                "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.concept_member"

            /** The member's collection: `place`, `visit`, `trip` or `concept` (nesting — older
             *  providers reject `concept`). Also a [android.content.ContentValues] key for
             *  adding a member. */
            const val TARGET_TYPE: String = "target_type"

            /** The member's row id in its collection. Also a [android.content.ContentValues] key. */
            const val TARGET_ID: String = "target_id"

            /** When the member was attached, epoch milliseconds. */
            const val ATTACHED_AT_MS: String = "attached_at_ms"

            /** Whether the calling app attached this member — nullable boolean (`1` = the caller,
             *  `0` = a different app, null = Pathline itself). */
            const val ATTACHED_BY_ME: String = "attached_by_me"

            @JvmField
            val COLUMNS: Array<String> =
                arrayOf(TARGET_TYPE, TARGET_ID, ATTACHED_AT_MS, ATTACHED_BY_ME)
        }
    }

    /**
     * Per-place **aggregates** over confirmed visits in a window — the one-call answer to
     * "most visited / favorite place" questions that would otherwise require reading and counting
     * whole visit collections. One row per saved place with at least one confirmed visit
     * overlapping the window, **most-visited first** ([VISIT_COUNT] descending, then
     * [TOTAL_DURATION_MS] descending).
     *
     * Windowed like `visits`: [QueryParams.START] is required, [QueryParams.END] defaults to now,
     * and a window reaching past 30 days needs [Permissions.READ_EXTENDED_HISTORY]. Overlap
     * semantics also match `visits`: a visit crossing the window edge counts whole — durations are
     * full visit spans, not clipped. Requires [Permissions.READ_TIMELINE]; rows are scoped to the
     * caller's granted places (the whole corpus under [Permissions.READ_ALL_PLACES]) — resolve the
     * ids to names via [Places]. [QueryParams.IDS] filters to specific places. Unconfirmed visits
     * and visits at no saved place are never counted.
     * MIME type: `vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.place_stats`.
     */
    object PlaceStats {
        const val PATH: String = "place_stats"

        @JvmField
        val CONTENT_URI: Uri = BASE.buildUpon().appendPath(PATH).build()
        const val CONTENT_TYPE: String =
            "vnd.android.cursor.dir/vnd.net.extrawdw.apps.locationhistory.place_stats"

        /** The saved place these aggregates describe (a [Places] id). */
        const val PLACE_ID: String = "place_id"

        /** Number of confirmed visits overlapping the window. */
        const val VISIT_COUNT: String = "visit_count"

        /** Sum of those visits' full durations, milliseconds. */
        const val TOTAL_DURATION_MS: String = "total_duration_ms"

        /** Start of the earliest counted visit, epoch milliseconds. */
        const val FIRST_VISIT_MS: String = "first_visit_ms"

        /** End of the latest counted visit, epoch milliseconds. */
        const val LAST_VISIT_MS: String = "last_visit_ms"

        @JvmField
        val COLUMNS: Array<String> =
            arrayOf(PLACE_ID, VISIT_COUNT, TOTAL_DURATION_MS, FIRST_VISIT_MS, LAST_VISIT_MS)
    }

    /**
     * Read-only status of the data API, for a consumer to check before reading. Returns a single row
     * reporting whether the user's access switch is on ([ACCESS_ENABLED]) and which contract version
     * the installed Pathline speaks ([API_VERSION]).
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

        /**
         * The provider's live [PathlineContract.API_VERSION]. Read it defensively (the column is
         * absent on providers older than version 3) and compare against the constant in your
         * bundled contract copy — see [PathlineContract.API_VERSION] for the semantics.
         */
        const val API_VERSION: String = "api_version"

        @JvmField
        val COLUMNS: Array<String> = arrayOf(ACCESS_ENABLED, API_VERSION)
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
