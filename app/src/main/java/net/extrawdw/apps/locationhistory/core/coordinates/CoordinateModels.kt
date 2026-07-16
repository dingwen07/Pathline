package net.extrawdw.apps.locationhistory.core.coordinates

/** Canonical coordinate used by recording, persistence, domain geometry, Routes, API, and GPX. */
data class Wgs84Coordinate(
    val latitude: Double,
    val longitude: Double,
)

/** Coordinate accepted by the effective Google Maps Android rendering surface. */
data class GoogleMapCoordinate(
    val latitude: Double,
    val longitude: Double,
)

/** Coordinate accepted/returned by the effective Google Places Android surface. */
data class GooglePlacesCoordinate(
    val latitude: Double,
    val longitude: Double,
)

/** GCJ-02 compatibility coordinate. It is never a persistence or domain type. */
data class Gcj02Coordinate(
    val latitude: Double,
    val longitude: Double,
)

enum class GoogleBoundaryFrame {
    WGS84,
    MAINLAND_GCJ02,
    UNVERIFIED,
}

enum class BuiltInLocationLayerBehavior {
    ALIGNED,
    MISALIGNED,
    UNVERIFIED,
}

/**
 * A versioned description of the observed Android Google boundary behavior. Each direction is
 * separate on purpose: a rendering rollback must never silently reinterpret Places or map-click
 * ingress.
 */
data class GoogleAndroidCoordinateProfile(
    val id: String,
    val mapRenderInput: GoogleBoundaryFrame,
    val mapInteractionOutput: GoogleBoundaryFrame,
    val placesRequestInput: GoogleBoundaryFrame,
    val placesResultOutput: GoogleBoundaryFrame,
    val builtInLocationLayer: BuiltInLocationLayerBehavior,
)

sealed interface TransformResult<out T> {
    data class Success<T>(val coordinate: T) : TransformResult<T>

    data class Failure(val reason: Reason) : TransformResult<Nothing>

    enum class Reason {
        INVALID_INPUT,
        OUTSIDE_SUPPORTED_RANGE,
        NON_CONVERGENT,
        UNVERIFIED_PROFILE,
    }
}

fun <T> TransformResult<T>.getOrNull(): T? =
    (this as? TransformResult.Success<T>)?.coordinate

/**
 * Working profile for the July 2026 Samsung/Pixel mainland observations and supplied export. Map
 * rendering/results are directly evidenced and the malformed nearby candidates support the request
 * projection. Map-click output was not characterized, so geometry writes remain disabled.
 */
object GoogleAndroidCoordinateProfiles {
    /** Frozen hypothesis used only for explicit legacy repairs/classification and their journal. */
    val HISTORICAL_PLACES_ANDROID_5_2_MAINLAND_2026_07 = GoogleAndroidCoordinateProfile(
        id = "historical-google-android-places-5.2.0-mainland-2026-07",
        mapRenderInput = GoogleBoundaryFrame.UNVERIFIED,
        mapInteractionOutput = GoogleBoundaryFrame.UNVERIFIED,
        placesRequestInput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        placesResultOutput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        builtInLocationLayer = BuiltInLocationLayerBehavior.UNVERIFIED,
    )

    /** Explicit user-selected hypothesis for a pure point chosen on the affected historical map. */
    val HISTORICAL_MAP_ANDROID_MAINLAND_2026_07_USER_CONFIRMED = GoogleAndroidCoordinateProfile(
        id = "historical-google-android-map-mainland-2026-07-user-confirmed",
        mapRenderInput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        mapInteractionOutput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        placesRequestInput = GoogleBoundaryFrame.UNVERIFIED,
        placesResultOutput = GoogleBoundaryFrame.UNVERIFIED,
        builtInLocationLayer = BuiltInLocationLayerBehavior.UNVERIFIED,
    )

    val OBSERVED_MAINLAND_2026_07 = GoogleAndroidCoordinateProfile(
        id = "google-android-mainland-2026-07-maps-compose-8.3.0-places-5.2.0",
        mapRenderInput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        mapInteractionOutput = GoogleBoundaryFrame.UNVERIFIED,
        placesRequestInput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        placesResultOutput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        // The SDK-owned blue dot bypasses Pathline's adapter and remains a device-test contract.
        builtInLocationLayer = BuiltInLocationLayerBehavior.UNVERIFIED,
    )
}
