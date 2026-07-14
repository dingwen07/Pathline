package net.extrawdw.apps.locationhistory.core.coordinates

import javax.inject.Inject
import javax.inject.Singleton

interface ChinaCoordinateTransform {
    fun wgs84ToGcj02(value: Wgs84Coordinate): TransformResult<Gcj02Coordinate>
    fun gcj02ToWgs84(value: Gcj02Coordinate): TransformResult<Wgs84Coordinate>
}

/**
 * The only production bridge between Pathline's WGS-84 domain and Google Android coordinates.
 * Identity is exact (the original doubles are copied) for WGS profiles and outside the mainland
 * mask. An unverified direction fails closed rather than guessing a value that could be persisted.
 */
@Singleton
class GoogleAndroidCoordinateAdapter @Inject constructor(
    private val transform: Gcj02CoordinateTransform,
) {
    val profile: GoogleAndroidCoordinateProfile =
        GoogleAndroidCoordinateProfiles.OBSERVED_MAINLAND_2026_07

    fun toMap(
        value: Wgs84Coordinate,
        operationProfile: GoogleAndroidCoordinateProfile = profile,
    ): TransformResult<GoogleMapCoordinate> = when (operationProfile.mapRenderInput) {
        GoogleBoundaryFrame.WGS84 -> value.asMapIdentity()
        GoogleBoundaryFrame.MAINLAND_GCJ02 -> transform.wgs84ToGcj02(value).map {
            GoogleMapCoordinate(it.latitude, it.longitude)
        }
        GoogleBoundaryFrame.UNVERIFIED -> TransformResult.Failure(
            TransformResult.Reason.UNVERIFIED_PROFILE
        )
    }

    fun fromMapInteraction(
        value: GoogleMapCoordinate,
        operationProfile: GoogleAndroidCoordinateProfile = profile,
    ): TransformResult<Wgs84Coordinate> = when (operationProfile.mapInteractionOutput) {
        GoogleBoundaryFrame.WGS84 -> validate(value.latitude, value.longitude) {
            Wgs84Coordinate(value.latitude, value.longitude)
        }
        GoogleBoundaryFrame.MAINLAND_GCJ02 -> transform.gcj02ToWgs84(
            Gcj02Coordinate(value.latitude, value.longitude)
        )
        GoogleBoundaryFrame.UNVERIFIED -> TransformResult.Failure(
            TransformResult.Reason.UNVERIFIED_PROFILE
        )
    }

    /**
     * Inverse used only to derive camera bounds/editor previews from the already-validated render
     * frame. It must never feed persistence; map-click writes use [fromMapInteraction] instead.
     */
    fun fromMapForPreview(
        value: GoogleMapCoordinate,
        operationProfile: GoogleAndroidCoordinateProfile = profile,
    ): TransformResult<Wgs84Coordinate> = when (operationProfile.mapRenderInput) {
        GoogleBoundaryFrame.WGS84 -> validate(value.latitude, value.longitude) {
            Wgs84Coordinate(value.latitude, value.longitude)
        }
        GoogleBoundaryFrame.MAINLAND_GCJ02 -> transform.gcj02ToWgs84(
            Gcj02Coordinate(value.latitude, value.longitude)
        )
        GoogleBoundaryFrame.UNVERIFIED -> TransformResult.Failure(
            TransformResult.Reason.UNVERIFIED_PROFILE
        )
    }

    fun toPlacesRequest(
        value: Wgs84Coordinate,
        operationProfile: GoogleAndroidCoordinateProfile = profile,
    ): TransformResult<GooglePlacesCoordinate> = when (operationProfile.placesRequestInput) {
        GoogleBoundaryFrame.WGS84 -> validate(value.latitude, value.longitude) {
            GooglePlacesCoordinate(value.latitude, value.longitude)
        }
        GoogleBoundaryFrame.MAINLAND_GCJ02 -> transform.wgs84ToGcj02(value).map {
            GooglePlacesCoordinate(it.latitude, it.longitude)
        }
        GoogleBoundaryFrame.UNVERIFIED -> TransformResult.Failure(
            TransformResult.Reason.UNVERIFIED_PROFILE
        )
    }

    fun fromPlacesResult(
        value: GooglePlacesCoordinate,
        operationProfile: GoogleAndroidCoordinateProfile = profile,
    ): TransformResult<Wgs84Coordinate> = when (operationProfile.placesResultOutput) {
        GoogleBoundaryFrame.WGS84 -> validate(value.latitude, value.longitude) {
            Wgs84Coordinate(value.latitude, value.longitude)
        }
        GoogleBoundaryFrame.MAINLAND_GCJ02 -> transform.gcj02ToWgs84(
            Gcj02Coordinate(value.latitude, value.longitude)
        )
        GoogleBoundaryFrame.UNVERIFIED -> TransformResult.Failure(
            TransformResult.Reason.UNVERIFIED_PROFILE
        )
    }

    /** True only when this WGS point will actually move under the active map profile. */
    fun requiresMainlandMapProjection(
        value: Wgs84Coordinate,
        operationProfile: GoogleAndroidCoordinateProfile = profile,
    ): Boolean {
        if (operationProfile.mapRenderInput != GoogleBoundaryFrame.MAINLAND_GCJ02) return false
        val projected = toMap(value, operationProfile).getOrNull() ?: return false
        return projected.latitude.toRawBits() != value.latitude.toRawBits() ||
                projected.longitude.toRawBits() != value.longitude.toRawBits()
    }

    private fun Wgs84Coordinate.asMapIdentity(): TransformResult<GoogleMapCoordinate> =
        validate(latitude, longitude) { GoogleMapCoordinate(latitude, longitude) }
}

private inline fun <A, B> TransformResult<A>.map(block: (A) -> B): TransformResult<B> = when (this) {
    is TransformResult.Success -> TransformResult.Success(block(coordinate))
    is TransformResult.Failure -> this
}

private inline fun <T> validate(latitude: Double, longitude: Double, value: () -> T): TransformResult<T> {
    if (!latitude.isFinite() || !longitude.isFinite()) {
        return TransformResult.Failure(TransformResult.Reason.INVALID_INPUT)
    }
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
        return TransformResult.Failure(TransformResult.Reason.OUTSIDE_SUPPORTED_RANGE)
    }
    return TransformResult.Success(value())
}
