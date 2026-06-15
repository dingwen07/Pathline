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
     * First look after a weak departure hint (significant motion, geofence exit, Wi-Fi disconnect, a
     * Doze exit with motion). HIGH_ACCURACY but at a sparse ~30s cadence: these triggers are rare,
     * event-like and motion-gated (they fire on starting to move, not on handling a phone), so a few
     * accurate fixes here are cheap, while coarse fixes can't resolve an 80-150m departure. Escalates to
     * [CONFIRMING_DEPARTURE] (denser cadence) only when a fix actually shows displacement from the stay;
     * otherwise the deadline reverts to STATIONARY. AR movement promotes straight to [MOVING] from here.
     */
    SENSING_DEPARTURE,

    /**
     * Escalated departure check: a HIGH_ACCURACY window that decides [MOVING] vs back to STATIONARY from
     * a *multi-fix* movement signature (sustained Doppler over several fixes, or a coherent,
     * kinematically-plausible displacement trace away from the frozen stay anchor) -- never from one
     * fix, one Doppler spike, or IMU energy alone.
     */
    CONFIRMING_DEPARTURE,

    /**
     * Bootstrapping / undecided (cold start, service restart): a moderate cadence that should resolve
     * to STATIONARY or MOVING quickly. Self-demotes to STATIONARY if it lingers with no motion.
     */
    UNKNOWN,
}
