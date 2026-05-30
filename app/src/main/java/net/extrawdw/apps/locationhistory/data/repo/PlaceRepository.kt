package net.extrawdw.apps.locationhistory.data.repo

import kotlinx.coroutines.flow.Flow
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceVisitCount
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

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

    suspend fun deleteIfUnvisited(placeId: Long): Boolean =
        dao.deleteIfUnvisited(placeId) > 0

    /**
     * Recompute a place's center and radius as a **weighted mean of all its visits' centroids/radii**,
     * so it follows where the user recently goes instead of freezing. Each visit is weighted by:
     *  - **recency** — an exponential decay that halves the weight every
     *    [Constants.PLACE_VISIT_RECENCY_HALF_LIFE_DAYS], so old visits fade out; and
     *  - **confirmation** — confirmed (ground-truth) visits count [Constants.PLACE_CONFIRMED_VISIT_WEIGHT]×
     *    an unconfirmed one.
     *
     * Recomputing from scratch (rather than nudging incrementally) is what lets old visits lose
     * influence over time. Call this *after* the triggering visit is persisted/linked so it's
     * included. Fixed places are left untouched; the visit count is derived live from the visits
     * table, not stored.
     */
    suspend fun recordVisitToPlace(placeId: Long, nowMs: Long = System.currentTimeMillis()) {
        val place = dao.byId(placeId) ?: return
        if (place.fixed) return
        val visits = visitDao.listForPlace(placeId).ifEmpty { return }

        var sumW = 0.0
        var sumLat = 0.0
        var sumLon = 0.0
        var sumRadius = 0.0
        for (v in visits) {
            val ageDays = (nowMs - v.startMs).coerceAtLeast(0L) / 86_400_000.0
            val recency = 2.0.pow(-ageDays / Constants.PLACE_VISIT_RECENCY_HALF_LIFE_DAYS)
            val confirmation = if (v.confirmed) Constants.PLACE_CONFIRMED_VISIT_WEIGHT else 1.0
            val w = recency * confirmation
            sumW += w
            sumLat += w * v.centroidLatitude
            sumLon += w * v.centroidLongitude
            sumRadius += w * v.radiusMeters
        }
        if (sumW <= 0.0) return

        val newRadius = (sumRadius / sumW)
            .coerceIn(Constants.PLACE_MIN_RADIUS_METERS, Constants.PLACE_MAX_RADIUS_METERS)
        dao.updateCenterRadius(placeId, sumLat / sumW, sumLon / sumW, newRadius)
    }
}
