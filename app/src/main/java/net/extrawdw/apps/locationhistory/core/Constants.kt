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

    /** Radius (m) used when matching a visit centroid against the local place database. */
    const val PLACE_MATCH_RADIUS_METERS = 80.0

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

    /** A "trip" whose net displacement is under this (m) is treated as GPS drift, not real movement. */
    const val DRIFT_DISPLACEMENT_METERS = 80.0

    /** Radius (m) of the geofence dropped when the device becomes stationary. */
    const val DWELL_GEOFENCE_RADIUS_METERS = 100f

    // --- Trip segmentation --------------------------------------------------------------------
    /** Sliding window (number of samples) used when classifying transport mode along a trip. */
    const val SEGMENT_WINDOW_SIZE = 6

    /** Minimum segment length (m); shorter same-mode runs are merged into a neighbour. */
    const val MIN_SEGMENT_DISTANCE_METERS = 100.0

    /** Max time gap (ms) between two same-type items for the auto-merger to combine them. */
    const val MERGE_GAP_MS = 90_000L

    // --- Training -----------------------------------------------------------------------------
    /** Accumulated user-confirmed examples that trigger a (charging-gated) retrain. */
    const val RETRAIN_EXAMPLE_THRESHOLD = 20

    // --- Files / assets -----------------------------------------------------------------------
    const val STATE_MODEL_ASSET = "models/state_model.tflite"
    const val TRANSPORT_MODEL_ASSET = "models/transport_model.tflite"
    const val STATE_CHECKPOINT_FILE = "state_model.ckpt"
    const val TRANSPORT_CHECKPOINT_FILE = "transport_model.ckpt"

    // --- Backup -------------------------------------------------------------------------------
    /** Subdirectory created inside the chosen SAF tree that holds the structured backup. */
    const val BACKUP_DIR = "pathline-backup"

    /** Bumped whenever the on-disk backup layout/format changes incompatibly. */
    const val BACKUP_FORMAT_VERSION = 1
}
