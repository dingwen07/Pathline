package net.extrawdw.apps.locationhistory.service

/**
 * Latest active Activity Recognition evidence stamped on samples and fed to the per-fix classifier.
 *
 * The Transition API is edge-based: ENTER means the activity is active until its matching EXIT. Keeping
 * a bare "last ENTER" forever corrupts parked samples after an EXIT-only batch, so matching exits clear
 * the evidence instead of leaving a stale WALKING/IN_VEHICLE vote in place.
 */
internal class LatestArEvidence {
    private var activity: String? = null
    private var confidence: Int? = null

    fun current(): ArEvidence = ArEvidence(activity, confidence)

    fun enter(activity: String, confidence: Int) {
        this.activity = activity
        this.confidence = confidence
    }

    fun exit(activity: String) {
        if (this.activity == activity) clear()
    }

    fun isActivity(activity: String): Boolean = this.activity == activity

    private fun clear() {
        activity = null
        confidence = null
    }
}

internal data class ArEvidence(
    val activity: String?,
    val confidence: Int?,
)
