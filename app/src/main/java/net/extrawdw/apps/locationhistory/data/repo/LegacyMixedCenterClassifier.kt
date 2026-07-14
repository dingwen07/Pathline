package net.extrawdw.apps.locationhistory.data.repo

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/** Pure proof that a legacy Google center was produced by Pathline's historical weighted blend. */
internal object LegacyMixedCenterClassifier {
    fun isProvable(place: PlaceEntity, visits: List<VisitEntity>): Boolean {
        if (place.coordinateState != PlaceCoordinateState.UNKNOWN || place.fixed ||
            place.source != PlaceSource.MAPS || visits.isEmpty()
        ) return false
        val anchorLatitude = place.anchorLatitude ?: return false
        val anchorLongitude = place.anchorLongitude ?: return false
        val anchorRadius = place.anchorRadiusMeters ?: return false
        if (!validPoint(place.latitude, place.longitude) ||
            !validPoint(anchorLatitude, anchorLongitude) ||
            !place.radiusMeters.isFinite() || !anchorRadius.isFinite() ||
            rawEquals(place.latitude, anchorLatitude) && rawEquals(place.longitude, anchorLongitude)
        ) return false

        // At any recompute time after the newest visit, every visit's decay shares one common
        // multiplier. Their relative weighted centroid/radius is therefore time-invariant; only the
        // blend fraction between that aggregate and the fixed anchor changes as the history ages.
        val latestStartMs = visits.maxOf { it.startMs }
        var visitWeightAtLatest = 0.0
        var visitLatitude = 0.0
        var visitLongitude = 0.0
        var visitRadius = 0.0
        for (visit in visits) {
            if (!validPoint(visit.centroidLatitude, visit.centroidLongitude) ||
                !visit.radiusMeters.isFinite() || !visit.reliability.isFinite()
            ) return false
            val reliability = visit.reliability.toDouble()
            if (reliability <= 0.0) continue
            val ageDaysAtLatest = (latestStartMs - visit.startMs).coerceAtLeast(0L) / DAY_MS
            val recency = 2.0.pow(-ageDaysAtLatest / Constants.PLACE_VISIT_RECENCY_HALF_LIFE_DAYS)
            val confirmation = if (visit.confirmed) Constants.PLACE_CONFIRMED_VISIT_WEIGHT else 1.0
            val weight = recency * confirmation * reliability
            if (!weight.isFinite() || weight <= 0.0) continue
            visitWeightAtLatest += weight
            visitLatitude += weight * visit.centroidLatitude
            visitLongitude += weight * visit.centroidLongitude
            visitRadius += weight * visit.radiusMeters
        }
        if (!visitWeightAtLatest.isFinite() || visitWeightAtLatest <= 0.0) return false
        visitLatitude /= visitWeightAtLatest
        visitLongitude /= visitWeightAtLatest
        visitRadius /= visitWeightAtLatest

        val aggregateDistance = Geo.distanceMeters(
            anchorLatitude,
            anchorLongitude,
            visitLatitude,
            visitLongitude,
        )
        if (!aggregateDistance.isFinite() || aggregateDistance < MIN_PROVABLE_DISPLACEMENT_METERS) {
            return false
        }

        // Project longitude into the local latitude scale before finding the saved center's fraction
        // along anchor -> visit aggregate. A historical blend uses the same fraction on both axes.
        val longitudeScale = cos(Math.toRadians((anchorLatitude + visitLatitude) / 2.0))
        val aggregateX = (visitLongitude - anchorLongitude) * longitudeScale
        val aggregateY = visitLatitude - anchorLatitude
        val centerX = (place.longitude - anchorLongitude) * longitudeScale
        val centerY = place.latitude - anchorLatitude
        val denominator = aggregateX * aggregateX + aggregateY * aggregateY
        if (!denominator.isFinite() || denominator <= 0.0) return false
        val fraction = (centerX * aggregateX + centerY * aggregateY) / denominator

        val anchorWeight = if (place.googlePlaceId != null) {
            Constants.PLACE_GOOGLE_ANCHOR_WEIGHT
        } else {
            Constants.PLACE_LOCAL_ANCHOR_WEIGHT
        }
        val maximumFraction = visitWeightAtLatest / (anchorWeight + visitWeightAtLatest)
        if (!fraction.isFinite() || fraction <= MIN_BLEND_FRACTION ||
            fraction > maximumFraction + FRACTION_TOLERANCE
        ) return false

        val fittedLatitude = anchorLatitude + fraction * (visitLatitude - anchorLatitude)
        val fittedLongitude = anchorLongitude + fraction * (visitLongitude - anchorLongitude)
        val centerResidual = Geo.distanceMeters(
            place.latitude,
            place.longitude,
            fittedLatitude,
            fittedLongitude,
        )
        if (!centerResidual.isFinite() || centerResidual > CENTER_TOLERANCE_METERS) return false

        val fittedRadius = ((1.0 - fraction) * anchorRadius + fraction * visitRadius)
            .coerceIn(Constants.PLACE_MIN_RADIUS_METERS, Constants.PLACE_MAX_RADIUS_METERS)
        return abs(place.radiusMeters - fittedRadius) <= RADIUS_TOLERANCE_METERS
    }

    private fun validPoint(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun rawEquals(first: Double, second: Double): Boolean =
        first.toRawBits() == second.toRawBits()

    private const val DAY_MS = 86_400_000.0
    private const val MIN_PROVABLE_DISPLACEMENT_METERS = 1.0
    private const val MIN_BLEND_FRACTION = 1e-6
    private const val FRACTION_TOLERANCE = 1e-6
    private const val CENTER_TOLERANCE_METERS = 1.0
    private const val RADIUS_TOLERANCE_METERS = 0.1
}
