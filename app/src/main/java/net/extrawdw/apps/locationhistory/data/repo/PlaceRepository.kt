package net.extrawdw.apps.locationhistory.data.repo

import kotlinx.coroutines.flow.Flow
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceVisitCount
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.domain.VisitGeometry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceRepository @Inject constructor(
    private val dao: PlaceDao,
    private val visitDao: VisitDao,
) {
    fun observeAll(): Flow<List<PlaceEntity>> = dao.observeAll()

    /** Live placeId -> visit count (the authoritative count, derived from the visits table). */
    fun observeVisitCounts(): Flow<List<PlaceVisitCount>> = visitDao.observePlaceVisitCounts()

    suspend fun byId(id: Long): PlaceEntity? = dao.byId(id)

    fun observeById(id: Long): Flow<PlaceEntity?> = dao.observeById(id)

    fun observeVisitsForPlace(id: Long): Flow<List<net.extrawdw.apps.locationhistory.data.db.VisitEntity>> =
        visitDao.observeForPlace(id)

    /** Create (or return existing id of) a user-confirmed place — treated as ground truth. */
    suspend fun confirmPlace(
        name: String,
        latitude: Double,
        longitude: Double,
        googlePlaceId: String?,
        address: String?,
        category: String?,
        source: PlaceSource = PlaceSource.USER,
    ): Long = dao.insert(
        PlaceEntity(
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = Constants.PLACE_MATCH_RADIUS_METERS,
            category = category,
            source = source,
            googlePlaceId = googlePlaceId,
            address = address,
            confirmed = true,
            createdAtMs = System.currentTimeMillis(),
        ),
    )

    suspend fun update(place: PlaceEntity) = dao.update(place)

    /**
     * Recompute a place's center and radius from a newly-assigned visit's [samples] (weighted by
     * GPS accuracy via [VisitGeometry]). The center is a running mean across the place's visits, the
     * radius an EMA so it adapts and can shrink. Fixed places are left untouched. The visit count is
     * NOT stored here — it is derived live from the visits table to avoid drift.
     */
    suspend fun recordVisitToPlace(placeId: Long, samples: List<LocationSampleEntity>) {
        val place = dao.byId(placeId) ?: return
        if (place.fixed) return
        val geom = VisitGeometry.compute(samples, place.latitude, place.longitude)

        // Visits already assigned to this place (this new one isn't linked yet) + 1.
        val n = (visitDao.countForPlace(placeId) + 1).coerceAtLeast(1)
        val newLat = place.latitude + (geom.latitude - place.latitude) / n
        val newLon = place.longitude + (geom.longitude - place.longitude) / n
        val newRadius = (place.radiusMeters * 0.6 + geom.radiusMeters * 0.4)
            .coerceIn(Constants.PLACE_MIN_RADIUS_METERS, Constants.PLACE_MAX_RADIUS_METERS)

        dao.updateCenterRadius(placeId, newLat, newLon, newRadius)
    }
}
