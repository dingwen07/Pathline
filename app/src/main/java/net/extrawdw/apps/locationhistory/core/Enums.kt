package net.extrawdw.apps.locationhistory.core

import androidx.annotation.StringRes
import net.extrawdw.apps.locationhistory.R

/**
 * The physical state of the device, fused from Activity Recognition, motion sensors, speed and
 * network signals by the device-state classifier. Stored on every [LocationSample].
 *
 * The ordinal order is the model's output label order — do not reorder without migrating models.
 *
 * [labelRes] resolves to a localized display name; pass a [android.content.Context] (e.g.
 * `context.getString(state.labelRes)`) or use `stringResource(state.labelRes)` in Compose.
 */
enum class DevicePhysicalState(@param:StringRes val labelRes: Int) {
    STATIONARY(R.string.state_stationary),
    WALKING(R.string.state_walking),
    RUNNING(R.string.state_running),
    CYCLING(R.string.state_cycling),
    IN_VEHICLE(R.string.state_in_vehicle),
    UNKNOWN(R.string.state_unknown);

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
enum class TransportMode(@param:StringRes val labelRes: Int) {
    WALKING(R.string.transport_walking),
    RUNNING(R.string.transport_running),
    CYCLING(R.string.transport_cycling),
    CAR(R.string.transport_car),
    BUS(R.string.transport_bus),
    RAIL(R.string.transport_rail),          // train / subway / tram / light rail
    FERRY(R.string.transport_ferry),
    FLIGHT(R.string.transport_flight),
    UNKNOWN(R.string.transport_unknown);

    companion object {
        val MODEL_CLASSES: List<TransportMode> =
            listOf(WALKING, RUNNING, CYCLING, CAR, BUS, RAIL, FERRY, FLIGHT)

        fun fromModelIndex(index: Int): TransportMode =
            MODEL_CLASSES.getOrElse(index) { UNKNOWN }
    }
}

/** Where a place in the local place database came from. */
enum class PlaceSource(@param:StringRes val labelRes: Int) {
    /** User explicitly created or confirmed the place — treated as ground truth. */
    USER(R.string.place_source_user),

    /** Pulled from Google Places API and persisted after a user confirmation. */
    MAPS(R.string.place_source_maps),

    /** Inferred by the app from clustering visits; unconfirmed until the user accepts it. */
    INFERRED(R.string.place_source_inferred),
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
