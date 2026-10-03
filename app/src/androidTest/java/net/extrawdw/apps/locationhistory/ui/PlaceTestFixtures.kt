package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity

internal fun fixturePlace() = PlaceEntity(
    id = 1L, name = "Adaptive test place", latitude = 1.3, longitude = 103.8, radiusMeters = 50.0,
    category = null, source = PlaceSource.USER, googlePlaceId = null, address = "Test address",
    confirmed = true, createdAtMs = 0L, coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
)

internal fun fixtureVisit() = VisitEntity(
    id = 1L, placeId = null, candidateName = null, candidateGooglePlaceId = null,
    candidateLatitude = null, candidateLongitude = null, startMs = 0L, endMs = 60_000L,
    dayEpoch = 0L, centroidLatitude = 1.3, centroidLongitude = 103.8, radiusMeters = 50.0,
    confirmed = false, confidence = 0f, isOngoing = false,
)

internal fun fixtureProjection(place: PlaceEntity) = ProjectedPlaceCircle(
    circle = ProjectedMapCircle(GoogleMapCoordinate(place.latitude, place.longitude), emptyList(), place.radiusMeters),
    isCanonical = true, canonicalCenter = Wgs84Coordinate(place.latitude, place.longitude),
)
