package net.extrawdw.apps.locationhistory.service

/**
 * The Activity Recognition activities the recorder tracks, decoupled from the GMS
 * `DetectedActivity` ints so [RecordingPolicy] stays JVM-testable (no Android types). The controller
 * maps `DetectedActivity` -> [ArActivity] before feeding transitions to the policy.
 */
enum class ArActivity {
    STILL,
    WALKING,
    RUNNING,
    CYCLING,
    IN_VEHICLE;

    companion object {
        /** The motion activities (everything except [STILL]). */
        val MOVING = listOf(WALKING, RUNNING, CYCLING, IN_VEHICLE)
    }
}

/**
 * A small "AR timeline": the last ENTER and last EXIT timestamp the Activity Recognition Transition
 * API reported for each [ArActivity].
 *
 * The Transition API is **edge-triggered** — it only fires on a *change* and is silent when a
 * session starts already in a state (an already-still cold start delivers no `ENTER STILL`). A single
 * sticky "last activity" therefore goes stale and can never be trusted to expire. Keeping the
 * per-activity transition times instead lets the policy reason about *recency* — "AR said STILL 30s
 * ago and nothing since" vs "AR has been silent since launch" — and ignore votes that have aged out.
 *
 * Pure: the caller passes the clock in. Not thread-safe; owned by [RecordingPolicy], which is only
 * touched under the controller's mutex.
 */
internal class ArActivityTimeline {
    private val enterMs = HashMap<ArActivity, Long>()
    private val exitMs = HashMap<ArActivity, Long>()

    fun recordEnter(activity: ArActivity, atMs: Long) {
        enterMs[activity] = atMs
    }

    fun recordExit(activity: ArActivity, atMs: Long) {
        exitMs[activity] = atMs
    }

    fun lastEnter(activity: ArActivity): Long? = enterMs[activity]

    /** True if [activity] was entered within [windowMs] of [nowMs] and has not exited since that enter. */
    fun activeWithin(activity: ArActivity, nowMs: Long, windowMs: Long): Boolean {
        val enter = enterMs[activity] ?: return false
        if (nowMs - enter > windowMs) return false
        val exit = exitMs[activity]
        return exit == null || exit < enter
    }

    /**
     * True if any moving activity is [activeWithin] the window — used to keep a still device from
     * self-demoting out of UNKNOWN while AR still believes it is moving.
     */
    fun anyMovingActiveWithin(nowMs: Long, windowMs: Long): Boolean =
        ArActivity.MOVING.any { activeWithin(it, nowMs, windowMs) }

    fun clear() {
        enterMs.clear()
        exitMs.clear()
    }
}
