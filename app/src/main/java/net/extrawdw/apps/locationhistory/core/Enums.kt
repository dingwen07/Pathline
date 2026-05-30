package net.extrawdw.apps.locationhistory.core

/**
 * The physical state of the device, fused from Activity Recognition, motion sensors, speed and
 * network signals by the device-state classifier. Stored on every [LocationSample].
 *
 * The ordinal order is the model's output label order — do not reorder without migrating models.
 */
enum class DevicePhysicalState(val label: String) {
    STATIONARY("Stationary"),
    WALKING("Walking"),
    RUNNING("Running"),
    CYCLING("Cycling"),
    IN_VEHICLE("In vehicle"),
    UNKNOWN("Unknown");

    companion object {
        /** Classes the model predicts over (UNKNOWN is reserved for low-confidence fallbacks). */
        val MODEL_CLASSES: List<DevicePhysicalState> =
            listOf(STATIONARY, WALKING, RUNNING, CYCLING, IN_VEHICLE)

        fun fromModelIndex(index: Int): DevicePhysicalState =
            MODEL_CLASSES.getOrElse(index) { UNKNOWN }
    }
}

/**
 * The mode of transportation inferred for a [TripSegment]. A single trip between two places may
 * be split into multiple segments, each with its own mode (e.g. walk → light rail → walk).
 *
 * The ordinal order is the transport model's output label order.
 */
enum class TransportMode(val label: String) {
    WALKING("Walking"),
    RUNNING("Running"),
    CYCLING("Cycling"),
    CAR("Car"),
    BUS("Bus"),
    RAIL("Rail"),          // train / subway / tram / light rail
    FERRY("Ferry"),
    FLIGHT("Flight"),
    UNKNOWN("Unknown");

    companion object {
        val MODEL_CLASSES: List<TransportMode> =
            listOf(WALKING, RUNNING, CYCLING, CAR, BUS, RAIL, FERRY, FLIGHT)

        fun fromModelIndex(index: Int): TransportMode =
            MODEL_CLASSES.getOrElse(index) { UNKNOWN }
    }
}

/** Where a place in the local place database came from. */
enum class PlaceSource {
    /** User explicitly created or confirmed the place — treated as ground truth. */
    USER,

    /** Pulled from Google Places API and persisted after a user confirmation. */
    MAPS,

    /** Inferred by the app from clustering visits; unconfirmed until the user accepts it. */
    INFERRED,
}

/** The active network transport when a sample was recorded. */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER,
    NONE,
}
