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

/** Whether a persisted place coordinate is safe to use as canonical WGS-84 domain geometry. */
enum class PlaceCoordinateState {
    /** New/local/repaired center and anchor are both canonical WGS-84. */
    WGS84_CANONICAL,

    /** Legacy Google row whose provider-frame anchor can be previewed, but not used in WGS math. */
    LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE,

    /** Legacy center was averaged across provider and WGS operands; only its baseline is previewable. */
    LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE,

    /** Provenance is insufficient; fail closed for geometry calculations and writes. */
    UNKNOWN,
}

/** Auditable decision that changed only a place's coordinate interpretation/repair state. */
enum class PlaceCoordinateRepairDecision {
    UNKNOWN,

    /** Both frozen historical transform directions were exact identity for every stored point. */
    AUTO_OUTSIDE_MAINLAND_IDENTITY,

    /** A local baseline exactly matches its linked WGS visit and was never moved. */
    AUTO_UNTOUCHED_LOCAL_IDENTITY,

    /** An untouched legacy Google center/baseline was classified for provider-frame preview only. */
    AUTO_CLASSIFIED_GOOGLE_PROVIDER_BASELINE,

    /** User explicitly confirmed that the saved center is already WGS-84. */
    SAVED_CENTER_AS_WGS84,

    /** User confirmed the saved center was a pure point selected on the historical Google map. */
    SAVED_CENTER_AS_HISTORICAL_MAP,

    /** User explicitly confirmed that the saved Google baseline is already WGS-84. */
    GOOGLE_BASELINE_AS_WGS84,

    /** User selected the frozen July-2026 Android Places provider-frame hypothesis. */
    GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER,
}

/** Frame of a visit's complete, temporary candidate snapshot. */
enum class CandidateCoordinateFrame {
    WGS84,
    UNKNOWN,
}

/** Business provenance of a visit's complete, temporary candidate snapshot. */
enum class CandidateOrigin {
    MAPS,
    UNKNOWN,
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
