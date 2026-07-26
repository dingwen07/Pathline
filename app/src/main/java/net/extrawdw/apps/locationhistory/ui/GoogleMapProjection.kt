package net.extrawdw.apps.locationhistory.ui

import com.google.android.gms.maps.model.LatLng
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.core.coordinates.getOrNull
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import javax.inject.Inject
import javax.inject.Singleton

data class ProjectedMapCircle(
    val center: GoogleMapCoordinate,
    /** Projected corners of a WGS-derived box, used only for camera fitting. */
    val boundsPoints: List<GoogleMapCoordinate>,
    val radiusMeters: Double,
)

data class ProjectedPlaceCircle(
    val circle: ProjectedMapCircle,
    val isCanonical: Boolean,
    /** Canonical editor/domain center; legacy values are recovered from their provider baseline. */
    val canonicalCenter: Wgs84Coordinate,
)

/** Pure presentation projector. It never mutates or re-encodes a persisted coordinate. */
@Singleton
class GoogleMapProjector @Inject constructor(
    private val adapter: GoogleAndroidCoordinateAdapter,
) {
    val profileId: String get() = adapter.profile.id

    fun coordinate(value: Wgs84Coordinate): GoogleMapCoordinate? =
        adapter.toMap(value).getOrNull()

    fun coordinate(latitude: Double, longitude: Double): GoogleMapCoordinate? =
        coordinate(Wgs84Coordinate(latitude, longitude))

    fun fromMap(value: GoogleMapCoordinate): Wgs84Coordinate? =
        adapter.fromMapInteraction(value).getOrNull()

    fun fromMapForPreview(value: GoogleMapCoordinate): Wgs84Coordinate? =
        adapter.fromMapForPreview(value).getOrNull()

    fun requiresMainlandProjection(value: Wgs84Coordinate): Boolean =
        adapter.requiresMainlandMapProjection(value)

    fun circle(center: Wgs84Coordinate, radiusMeters: Double): ProjectedMapCircle? {
        val projectedCenter = coordinate(center) ?: return null
        return ProjectedMapCircle(
            center = projectedCenter,
            boundsPoints = projectedBounds(center, radiusMeters),
            radiusMeters = radiusMeters,
        )
    }

    /**
     * Canonical places use the normal forward projector. A pre-v3 Google row is previewed at its
     * unchanged provider baseline so its currently-correct yellow circle is not transformed twice;
     * it remains excluded from domain calculations until a deliberate edit/repair canonicalizes it.
     */
    fun placeCircle(place: PlaceEntity): ProjectedPlaceCircle? = when (place.coordinateState) {
        PlaceCoordinateState.WGS84_CANONICAL -> circle(
            Wgs84Coordinate(place.latitude, place.longitude),
            place.radiusMeters,
        )?.let {
            ProjectedPlaceCircle(
                it,
                isCanonical = true,
                canonicalCenter = Wgs84Coordinate(place.latitude, place.longitude),
            )
        }

        PlaceCoordinateState.LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE,
        PlaceCoordinateState.LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE,
        PlaceCoordinateState.UNKNOWN -> null
    }

    /**
     * Detail/editor-only preview for an unresolved old row with a complete stored provider
     * baseline. Source metadata is not trusted here because old null-ID Google promotions were
     * persisted as USER. The unknown/mixed center remains excluded from Timeline and all WGS math.
     */
    fun placePreviewCircle(place: PlaceEntity): ProjectedPlaceCircle? =
        placeCircle(place) ?: if (
            place.coordinateState != PlaceCoordinateState.WGS84_CANONICAL &&
            place.anchorLatitude != null && place.anchorLongitude != null
        ) {
            legacyPreview(place)
        } else {
            null
        }

    /**
     * Project each vertex once. Split at invalid input and whenever the projection changes between
     * identity and mainland GCJ so a polyline never bridges the coordinate discontinuity.
     */
    fun paths(points: List<Pair<Double, Double>>): List<List<GoogleMapCoordinate>> {
        val paths = ArrayList<List<GoogleMapCoordinate>>()
        var current = ArrayList<GoogleMapCoordinate>()
        var currentUsesMainlandProjection: Boolean? = null
        fun flush() {
            if (current.isNotEmpty()) paths += current
            current = ArrayList()
            currentUsesMainlandProjection = null
        }
        points.forEach { (latitude, longitude) ->
            val projected = coordinate(latitude, longitude)
            if (projected == null) {
                flush()
            } else {
                val usesMainlandProjection =
                    projected.latitude.toRawBits() != latitude.toRawBits() ||
                            projected.longitude.toRawBits() != longitude.toRawBits()
                if (currentUsesMainlandProjection != null &&
                    currentUsesMainlandProjection != usesMainlandProjection
                ) {
                    flush()
                }
                current += projected
                currentUsesMainlandProjection = usesMainlandProjection
            }
        }
        flush()
        return paths
    }

    private fun projectedBounds(
        center: Wgs84Coordinate,
        radiusMeters: Double,
    ): List<GoogleMapCoordinate> {
        val box = Geo.boundingBox(
            center.latitude,
            center.longitude,
            radiusMeters.coerceAtLeast(1.0),
        )
        return listOf(
            Wgs84Coordinate(box[0], box[1]),
            Wgs84Coordinate(box[0], box[3]),
            Wgs84Coordinate(box[2], box[1]),
            Wgs84Coordinate(box[2], box[3]),
        ).mapNotNull(::coordinate)
    }

    private fun legacyPreview(place: PlaceEntity): ProjectedPlaceCircle? {
        val provider = GoogleMapCoordinate(
            place.anchorLatitude ?: place.latitude,
            place.anchorLongitude ?: place.longitude,
        )
        val recovered = fromMapForPreview(provider) ?: return null
        return ProjectedPlaceCircle(
            ProjectedMapCircle(
                center = provider,
                boundsPoints = projectedBounds(recovered, place.radiusMeters),
                radiusMeters = place.radiusMeters,
            ),
            isCanonical = false,
            canonicalCenter = recovered,
        )
    }
}

internal fun GoogleMapCoordinate.toLatLng(): LatLng = LatLng(latitude, longitude)
