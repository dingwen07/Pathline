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

    /** Duration twin (ms) of [DRIFT_TRIP_MAX_PATH_METERS], used ONLY as the fallback when a drift
     *  trip has no stored fixes to test place membership: GPS jitter is brief, while a long
     *  out-and-back excursion is real movement even when displacement and path both read low. When
     *  fixes ARE available, place membership ([DRIFT_TRIP_INPLACE_FRACTION]) decides instead, so a
     *  long stationary spell (an hour of indoor jitter) is no longer wrongly spared by this cap. */
    const val DRIFT_TRIP_MAX_DURATION_MS = 10 * 60_000L

    /** A trip is in-place GPS jitter (not real movement) when at least this fraction of its
     *  computation-eligible fixes sit within the bounding place's radius. A real out-and-back loop
     *  leaves the radius and stays well under this; indoor jitter sits at ~0.85-1.0. */
    const val DRIFT_TRIP_INPLACE_FRACTION = 0.75

    /** Doppler safety gate for the in-place drift test: collapse only when FEWER than this fraction of
     *  the span's fixes carry real GPS speed (>= [DRIFT_MOVING_SPEED_MPS]). A genuine near-home loop
     *  walk has ~0.2-0.33 of fixes moving; true jitter ~0-0.02 — so this keeps a real walk from being
     *  merged even when most of its fixes happen to sit inside the home radius. */
    const val DRIFT_TRIP_MOVING_FIX_FRACTION = 0.1

    // --- Speed-aware stay detection (carve a near-home walk out of a stay) ---------------------
    // See docs/doppler-timeline-findings.md. GPS Doppler speed is the only clean signal that splits a
    // tight near-home walk from indoor drift when geometry can't (the walk never leaves the radius).
    // Tuned across the June 2026 dumps: real walks carry valid speed >= [WALK_SPEED_FLOOR_MPS] on
    // 51-69% of fixes, drift/stillness on 0-3% — a wide, null-safe margin (a null speed is never
    // "moving", so it just doesn't count; never fold null -> 0).

    /** Per-fix valid-Doppler floor (m/s) above which a fix shows real motion when judging whether a
     *  *window* is moving. Deliberately BELOW the stricter per-fix [DRIFT_MOVING_SPEED_MPS] "definitely
     *  moving" line: these near-home walks are slow (~1.0 m/s median), so 1.2 clears only ~24% of their
     *  fixes (near drift), while 0.8 clears 51-69%. Matches the recorder's `meanSpeed > 0.6` cluster cut. */
    const val WALK_SPEED_FLOOR_MPS = 0.8f

    /** A window counts as a moving run when at least this fraction of its fixes carry a valid speed
     *  >= [WALK_SPEED_FLOOR_MPS]. Walks land at 0.51-0.69, drift/stillness at 0.00-0.03 — 0.15 sits in
     *  the gap with a wide margin either way. Sibling of [DRIFT_TRIP_MOVING_FIX_FRACTION]. */
    const val WALK_MOVING_FIX_FRACTION = 0.15

    /** Sliding-window width (ms) over which [WALK_MOVING_FIX_FRACTION] is measured per fix. ~one
     *  [MIN_VISIT_DURATION_MS], long enough to ride out the 0-speed fixes between steps. */
    const val MOVING_RUN_WINDOW_MS = 3 * 60_000L

    /** Minimum fixes in the window before its moving fraction is trusted, so a sparsely-sampled stay
     *  (low-power cadence: a fix every few minutes) is never declared "moving" off one or two fixes.
     *  The dumps' ambiguous case had 4 fixes; require more. */
    const val MIN_MOVING_WINDOW_FIXES = 5

    /**
     * Accuracy gate (m) for trusting a fix's Doppler speed / displacement as REAL movement evidence in
     * the recorder's live decisions (the MOVING sustained-Doppler keep-alive and the CONFIRMING_DEPARTURE
     * confirm). Tighter than [SAMPLE_ACCURACY_GATE_METERS] (60 m): the June 2026 dump showed coarse
     * fixes (32-69 m) are exactly the drift that fakes a moving fraction at a stationary place -- with a
     * <=30 m gate the stationary-vs-walking moving fractions separate cleanly (stationary tops ~0.19,
     * walking floors ~0.25). Position progress still uses the 60 m gate; this is only for the speed/
     * Doppler-trust decisions where coarse fixes lie.
     */
    const val DOPPLER_ACCURACY_GATE_M = 30f

    /**
     * MOVING sustained-Doppler keep-alive (slow-trip self-demote fix). [recentlyMoving]'s half-centroid
     * net-displacement test has an effective ~1.8 m/s floor (above walking pace), so a slow real trip
     * (a creeping shuttle, traffic, an AR-untagged vehicle) with no step signal would self-demote
     * mid-trip after [MOVING_IDLE_TIMEOUT_MS]. The keep-alive holds MOVING when, over a
     * [MOVING_RUN_WINDOW_MS] tail of accuracy-gated ([DOPPLER_ACCURACY_GATE_M]) fixes, the moving
     * fraction (speed >= [WALK_SPEED_FLOOR_MPS]) clears [MOVING_KEEPALIVE_FIX_FRACTION] AND at least
     * [MOVING_KEEPALIVE_MIN_MOVING_FIXES] fixes are moving -- the absolute floor stops a lone phantom
     * Doppler spike from pinning the costly cadence (the 06-13 drain class). Dump-calibrated.
     */
    const val MOVING_KEEPALIVE_FIX_FRACTION = 0.20f
    const val MOVING_KEEPALIVE_MIN_MOVING_FIXES = 3

    /** Max gap (ms) from a drift trip to each bounding visit for them to be fused. Looser than
     *  [MERGE_GAP_MS] because the recorder leaves a ~2-min dead zone around the visit split that
     *  creates these trips; still tight enough not to weld a same-place pair across a real outage. */
    const val DRIFT_TRIP_MERGE_GAP_MS = 5 * 60_000L

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
     * Budget for how long the high-power MOVING cadence may persist without being justified by real
     * movement (a fix that shows motion, AR-moving, or net displacement) before the recorder
     * self-demotes to the stationary cadence. The architectural backstop behind AR-as-authority: a
     * departure hint (geofence / significant-motion) or a phantom-speed / AR-jitter spell can ramp the
     * cadence for responsiveness, but NONE may hold it indefinitely -- only continuous movement does.
     * A real motion fix restarts the clock, so genuine trips (even with brief stops) are unaffected;
     * a still phone whose GPS is lying reverts within this budget instead of draining for hours
     * (the 06-13 multi-hour drains, where AR reported STILL the entire time).
     */
    const val MOVING_IDLE_TIMEOUT_MS = 5 * 60_000L

    /**
     * How long the [net.extrawdw.apps.locationhistory.core.RecorderState.SENSING_DEPARTURE] first look
     * samples (HIGH_ACCURACY, ~30s cadence -> ~4 fixes) before giving up on an unescalated departure
     * hint and reverting to STATIONARY. A real departure shows displacement within this window and
     * escalates to CONFIRMING; an in-place walk / transient shows none and reverts (re-arming
     * significant motion with backoff). Wide enough that a walk-away clears the stay radius before the
     * deadline even at a slow pace.
     */
    const val SENSING_VERIFY_WINDOW_MS = 120_000L

    /**
     * How long [net.extrawdw.apps.locationhistory.core.RecorderState.CONFIRMING_DEPARTURE] gathers
     * HIGH_ACCURACY fixes before giving up on an unconfirmed departure and reverting to STATIONARY. Long
     * enough for a multi-fix movement signature (sustained Doppler or a coherent displacement trace) to
     * form; the verify deadline is rescheduled to this on escalation from SENSING.
     */
    const val CONFIRMING_VERIFY_WINDOW_MS = 150_000L

    /** Departure confirmation (CONFIRMING_DEPARTURE), calibrated from the June 2026 dump. Sustained
     *  Doppler: at least this many accuracy-gated verify-window fixes carry valid speed
     *  >= [WALK_SPEED_FLOOR_MPS]. One spike is not enough (the dump showed false Doppler while still). */
    const val CONFIRM_MIN_DOPPLER_FIXES = 3

    /** Coherent-displacement fallback (no/low Doppler, e.g. network fixes): needs at least this many
     *  accuracy-gated verify-window fixes to judge a trend. */
    const val CONFIRM_MIN_DISPLACEMENT_FIXES = 4

    /** Coherent-displacement fallback: the max displacement from the frozen stay anchor must exceed this
     *  multiple of [DRIFT_DISPLACEMENT_METERS]-floored stationary noise radius (so a real walk-out
     *  clears it well, but in-place drift around one of the broad stay circles does not). */
    const val CONFIRM_DISPLACEMENT_RADIUS_MULTIPLE = 2.0

    /** Coherent-displacement fallback kinematic ceiling (m/s): a fix-to-fix step implying a speed above
     *  this with NO corroborating Doppler is a multipath teleport, not a walk-out -- reject it. Real
     *  displacement-only departures are slow walks; a genuine fast departure carries Doppler instead. */
    const val CONFIRM_MAX_STEP_SPEED_MPS = 4.0

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
