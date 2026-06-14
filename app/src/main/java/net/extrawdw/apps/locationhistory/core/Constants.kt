package net.extrawdw.apps.locationhistory.core

/** Centralized tuning constants for the recording, detection and classification pipeline. */
object Constants {

    // --- Confidence ---------------------------------------------------------------------------
    /** Below this confidence a classification is surfaced as "unconfirmed" and never auto-trusted. */
    const val CONFIRM_CONFIDENCE_THRESHOLD = 0.75f

    // --- Visit / place detection --------------------------------------------------------------
    /** A stay shorter than this (ms) is treated as a pass-through, not a real visit. */
    const val MIN_VISIT_DURATION_MS = 3 * 60_000L

    /** Radius (m) within which successive samples are considered the same stationary cluster. */
    const val STATIONARY_RADIUS_METERS = 60.0

    /**
     * How far on each side of the maintained day to load samples when rebuilding the timeline. The
     * window must be wider than the day so the visit detector sees a stay's *full* extent rather than
     * clipping it at midnight — that clipping is what split an overnight stay into two rows. A stay
     * crossing the boundary becomes one spanning row (shown on both days via the time-overlap query);
     * margins this wide comfortably contain a normal overnight stay. */
    const val REBUILD_LOOKBACK_MS = 18 * 60 * 60_000L
    const val REBUILD_LOOKAHEAD_MS = 18 * 60 * 60_000L

    /**
     * Safety margin (ms) added when the rebuild widens its sample window to cover the full extent of
     * the unconfirmed rows it is about to delete. Invariant: the delete scope must never exceed the
     * re-detection scope — a stay longer than [REBUILD_LOOKBACK_MS] would otherwise be deleted whole
     * but re-detected only from the lookback boundary, eroding ~a day of coverage at every midnight
     * rebuild.
     */
    const val REBUILD_SCOPE_MARGIN_MS = 60 * 60_000L

    /**
     * Beyond this gap (ms) with no recorded samples, presence is no longer assumed. Applied in three
     * places: the visit detector closes a cluster across a longer gap (a 10:00 and an 18:00 fix at
     * the same spot are two stays, not one 8h visit), the merger refuses to bridge two same-place
     * visits over a longer no-trip gap, and an ongoing visit stops extending to `now` once the
     * latest sample is older than this. Prevents recording outages from fabricating presence.
     */
    const val MAX_EVIDENCE_GAP_MS = 45 * 60_000L

    /** Radius (m) used when matching a visit centroid against the local place database. */
    const val PLACE_MATCH_RADIUS_METERS = 80.0

    /** Initial radius (m) for a place created from a Google Maps suggestion — Google gives a point, so
     *  this is the displayed "yellow ring". Tighter than the match radius (matching still uses
     *  [PLACE_MATCH_RADIUS_METERS]); the ring then adapts as visits accumulate. */
    const val GOOGLE_PLACE_RADIUS_METERS = 50.0

    /** Bounds for an auto-computed place radius (derived from sample spread + GPS accuracy). */
    const val PLACE_MIN_RADIUS_METERS = 25.0
    const val PLACE_MAX_RADIUS_METERS = 150.0

    /** Samples worse than this accuracy (m) are ignored when computing a place's center/radius. */
    const val SAMPLE_ACCURACY_GATE_METERS = 60f

    /** A place's center/radius is a weighted mean of its visits. A visit's weight halves every this
     *  many days, so the place follows where you recently go and old visits fade out. */
    const val PLACE_VISIT_RECENCY_HALF_LIFE_DAYS = 30.0

    /** Confirmed visits are ground truth, so they count this many times an unconfirmed visit. */
    const val PLACE_CONFIRMED_VISIT_WEIGHT = 4.0

    /** Saturation references for the per-visit reliability score (see VisitGeometry.reliabilityOf):
     *  each sub-score is `ref / (ref + x)` (or `x / (x + ref)` for "more is better"), so the ref is
     *  the value at which that factor scores 0.5. Reliability is the geometric mean of all four. */
    const val VISIT_RELIABILITY_COUNT_REF = 8.0           // sample count
    const val VISIT_RELIABILITY_ACCURACY_REF_M = 20.0     // median GPS accuracy (m)
    const val VISIT_RELIABILITY_DISPERSION_REF_M = 30.0   // RMS spread of fixes (m)
    const val VISIT_RELIABILITY_DURATION_REF_MS = 300_000.0 // stay duration (5 min)

    /** Weight of a place's immutable origin anchor in the center/radius recompute. The anchor does
     *  NOT decay, so it keeps the place near its authoritative origin and stops the radius from
     *  collapsing to the per-visit floor. A Google (MAPS) anchor is authoritative; a local
     *  (user/inferred) anchor is only its founding visit, so it pulls more gently. Roughly: a Google
     *  anchor ~ this-many recent confirmed visits' worth of pull. */
    const val PLACE_GOOGLE_ANCHOR_WEIGHT = 10.0
    const val PLACE_LOCAL_ANCHOR_WEIGHT = 3.0

    /** A near-anchor fix under this displacement (m) is treated as GPS drift, not real movement.
     *  This is the FLOOR of the stationary drift radius, which widens up to [PLACE_MAX_RADIUS_METERS]
     *  to match the GPS noise actually observed at the current spot (see RecordingController). */
    const val DRIFT_DISPLACEMENT_METERS = 80.0

    /** GPS speed (m/s) at or above which a near-anchor fix counts as real movement, not drift. */
    const val DRIFT_MOVING_SPEED_MPS = 1.2f

    /** Cap (m) on the recorded path length of a trip classified as drift between two same-place
     *  visits. Net displacement alone is not enough: a real loop trip (a run or dog walk from home)
     *  also starts and ends at the same place with ~zero displacement, but records kilometers of
     *  path — only a jitter-sized path may be deleted as drift. */
    const val DRIFT_TRIP_MAX_PATH_METERS = 300.0

    /** Duration twin (ms) of [DRIFT_TRIP_MAX_PATH_METERS]: GPS jitter is brief, while a long
     *  out-and-back excursion is real movement even when displacement and path both read low. */
    const val DRIFT_TRIP_MAX_DURATION_MS = 10 * 60_000L

    /** Upper bound (ms) on a one-shot getCurrentLocation wait. A cold GPS fix indoors can take
     *  20-30s — longer than a broadcast receiver's goAsync window, and the controller mutex is held
     *  while waiting. Past this, the request is cancelled and callers fall back to no-fix paths. */
    const val CURRENT_FIX_TIMEOUT_MS = 8_000L

    /**
     * Accelerometer motion-energy (variance) at or above which the phone is physically moving, so a
     * near-anchor fix is a real walk rather than GPS drift (which happens while the device sits still).
     * Weighted heavily so a short walk — even at a noisy-GPS place with no GPS speed — is never
     * mistaken for drift. Tuning knob; verify on-device.
     */
    const val DRIFT_MOTION_VARIANCE_CEILING = 1.0f

    // --- Recorder state machine ---------------------------------------------------------------
    /**
     * How long the recorder may sit in the [net.extrawdw.apps.locationhistory.core.RecorderState.UNKNOWN]
     * bootstrap cadence with no sign of motion before it self-demotes to the low-power STATIONARY
     * cadence. UNKNOWN is meant to be transient (cold start / service restart); without this fuse a
     * session that came up already-parked — Activity Recognition is edge-triggered and stays silent
     * on an already-still start — burns the costly UNKNOWN cadence for hours when the fix-cluster
     * detector can't form a stay (noisy indoor GPS). A bit longer than [MIN_VISIT_DURATION_MS] so a
     * genuine brief departure still has time to classify as movement first.
     */
    const val UNKNOWN_IDLE_TIMEOUT_MS = 4 * 60_000L

    /**
     * How long [net.extrawdw.apps.locationhistory.core.RecorderState.VERIFYING_DEPARTURE] burst-samples
     * before giving up on an unconfirmed departure hint and reverting to STATIONARY. Replaces the old
     * "bet on one fresh fix while holding the mutex" check: a real departure shows movement in the
     * burst within this window; a transient (phone bumped, HVAC) shows none and is suppressed.
     */
    const val DEPARTURE_VERIFY_WINDOW_MS = 75_000L

    /** Floor (m) for the dwell geofence dropped when the device becomes stationary. The radius is
     *  widened per-stay to the GPS noise actually observed there (see [DWELL_GEOFENCE_MARGIN_METERS]);
     *  this is the minimum a clean, quiet place uses. */
    const val DWELL_GEOFENCE_RADIUS_METERS = 100f

    /** Margin (m) added above the observed GPS-noise radius when sizing the dwell geofence, so a
     *  normal stationary wobble sits comfortably inside the boundary instead of tripping a spurious
     *  EXIT. Indoors a place can scatter 100 m+ fixes; a fixed 100 m ring is then guaranteed to flap. */
    const val DWELL_GEOFENCE_MARGIN_METERS = 25f

    /** Cap (m) on the adaptive dwell-geofence radius — a pathologically noisy place must not open a
     *  dead zone so large that a real departure goes unnoticed. */
    const val DWELL_GEOFENCE_MAX_RADIUS_METERS = 180f

    /**
     * Best-effort EXIT responsiveness (ms) for the dwell geofence, passed to
     * `Geofence.Builder.setNotificationResponsiveness`. Per the GMS contract LARGER values save power
     * (the system batches boundary checks) and 0 means "notify as soon as possible"; in practice
     * modern Play Services clamps small values up to ~2 min regardless, so this is a hint, not a
     * guarantee. 30s leans prompt (a real walk to the bus is still caught) while letting the system
     * batch. NOTE: the real anti-drift lever is the adaptive radius above, not this value.
     */
    const val DWELL_GEOFENCE_RESPONSIVENESS_MS = 30_000

    // --- Trip segmentation --------------------------------------------------------------------
    /** Sliding window (number of samples) used when classifying transport mode along a trip. */
    const val SEGMENT_WINDOW_SIZE = 6

    /** Minimum segment length (m); shorter same-mode runs are merged into a neighbour. */
    const val MIN_SEGMENT_DISTANCE_METERS = 100.0

    /** Max time gap (ms) between two same-type items for the auto-merger to combine them. */
    const val MERGE_GAP_MS = 90_000L

    // --- Backup -------------------------------------------------------------------------------
    /** Subdirectory created inside the chosen SAF tree that holds the structured backup. */
    const val BACKUP_DIR = "pathline-backup"

    /** Bumped whenever the on-disk backup layout/format changes incompatibly. */
    const val BACKUP_FORMAT_VERSION = 1
}
