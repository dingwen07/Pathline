package net.extrawdw.apps.locationhistory.core

import androidx.annotation.StringRes
import net.extrawdw.apps.locationhistory.R

/**
 * The recorder's deterministic movement estimate at the moment a sample was recorded, fused from
 * Activity Recognition, motion sensors, speed and network signals. Stored on every [LocationSample]
 * as *evidence* (it explains the sampling cadence in effect), never as timeline truth — the
 * timeline rebuild re-derives stays/movement from the sample sequence.
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
    UNKNOWN(R.string.state_unknown),
}

/**
 * The mode of transportation inferred for a trip. A single journey between two places may be
 * split into multiple trips, each with its own mode (e.g. walk -> light rail -> walk).
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
        /** Modes the user can pick in the editor/confirm UI (UNKNOWN is classifier-internal). */
        val SELECTABLE: List<TransportMode> =
            listOf(WALKING, RUNNING, CYCLING, CAR, BUS, RAIL, FERRY, FLIGHT)
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

/**
 * The kind of entity a tag or annotation is attached to. Stored by name as the polymorphic
 * discriminator on `entity_tags` / `annotations` / `concept_members` (no FK — visits/trips are
 * rebuilt, see the data-API design doc). Annotations only attach to PLACE rows, *confirmed*
 * VISIT / TRIP rows (whose ids survive a rebuild), and CONCEPT rows. CONCEPT is also a valid
 * concept **member** type — concepts nest one level at a time (stored and listed, never
 * auto-expanded; cycles are rejected at write time in `ConceptStore`).
 */
enum class AnnotationTarget {
    PLACE,
    VISIT,
    TRIP,
    CONCEPT,
}

/** Discriminates the two `annotations` payloads: free-text NOTE vs flat string->string MEMORY map. */
enum class AnnotationKind {
    NOTE,
    MEMORY,
}
