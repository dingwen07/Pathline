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

    /** Radius (m) used when matching a visit centroid against the local place database. */
    const val PLACE_MATCH_RADIUS_METERS = 80.0

    /** Bounds for an auto-computed place radius (derived from sample spread + GPS accuracy). */
    const val PLACE_MIN_RADIUS_METERS = 25.0
    const val PLACE_MAX_RADIUS_METERS = 150.0

    /** Samples worse than this accuracy (m) are ignored when computing a place's center/radius. */
    const val SAMPLE_ACCURACY_GATE_METERS = 60f

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
    const val EXPORT_DIR = "export"
}
