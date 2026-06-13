package net.extrawdw.apps.locationhistory.core

/**
 * The recorder's *control* state — which power/sampling tier the foreground recorder runs at right
 * now. Deliberately decoupled from [DevicePhysicalState] (the per-sample movement *evidence*): the
 * recorder only needs to know how hard to sample, not the precise transport mode, which the timeline
 * layer re-derives from the sample sequence. Collapsing the four AR moving activities
 * (walking/running/cycling/in-vehicle) into one [MOVING] tier removes four redundant states whose
 * location requests were near-identical (see [net.extrawdw.apps.locationhistory.service.LocationProfiles]).
 *
 * The state is driven by [net.extrawdw.apps.locationhistory.service.RecordingPolicy] from incoming
 * events (AR transitions, geofence exits, significant motion, Doze, fix batches) and stored on the
 * recorder debug state, never on samples.
 */
enum class RecorderState {
    /** Parked: low-power cadence, dwell geofence + significant-motion sensor armed as departure triggers. */
    STATIONARY,

    /** Travelling: high-accuracy cadence. The transport mode lives in sample evidence, not here. */
    MOVING,

    /**
     * A departure was hinted (AR walking, significant motion, a Doze exit) but not yet confirmed:
     * burst-sample briefly and let the arriving fixes decide MOVING vs back to STATIONARY, instead of
     * betting on a single fresh fix.
     */
    VERIFYING_DEPARTURE,

    /**
     * Bootstrapping / undecided (cold start, service restart): a moderate cadence that should resolve
     * to STATIONARY or MOVING quickly. Self-demotes to STATIONARY if it lingers with no motion.
     */
    UNKNOWN,
}
