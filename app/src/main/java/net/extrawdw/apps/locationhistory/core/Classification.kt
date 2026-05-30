package net.extrawdw.apps.locationhistory.core

/** Result of the device-state classifier for a single sample. */
data class StateClassification(
    val state: DevicePhysicalState,
    val confidence: Float,
) {
    /** Whether this result is trustworthy enough to be auto-applied without user confirmation. */
    val isConfident: Boolean get() = confidence >= Constants.CONFIRM_CONFIDENCE_THRESHOLD
}

/** Result of the transport-mode classifier for a window of samples. */
data class TransportClassification(
    val mode: TransportMode,
    val confidence: Float,
) {
    val isConfident: Boolean get() = confidence >= Constants.CONFIRM_CONFIDENCE_THRESHOLD
}
