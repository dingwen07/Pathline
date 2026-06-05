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
            // Capture the creation center/radius as the immutable anchor (Google's coordinates for a
            // MAPS place, else the founding visit centroid). Folded into every later recompute.
            anchorLatitude = latitude,
            anchorLongitude = longitude,
            anchorRadiusMeters = Constants.PLACE_MATCH_RADIUS_METERS,
        ),
    )

    suspend fun update(place: PlaceEntity) = dao.update(place)

    suspend fun deleteIfUnvisited(placeId: Long): Boolean =
        dao.deleteIfUnvisited(placeId) > 0

    /**
     * Recompute a place's center and radius as a **weighted mean of its origin anchor + all its
     * visits' centroids/radii**, so it follows where the user recently goes without drifting from —
     * or shrinking below — its authoritative origin. Contributions:
     *  - **origin anchor** — the immutable creation center/radius (Google's for a MAPS place),
     *    weighted by a fixed [Constants.PLACE_GOOGLE_ANCHOR_WEIGHT]/[Constants.PLACE_LOCAL_ANCHOR_WEIGHT]
     *    that does NOT decay; this keeps the radius from collapsing to the per-visit floor.
     *  - each **visit**, weighted by **recency** (halves every
     *    [Constants.PLACE_VISIT_RECENCY_HALF_LIFE_DAYS]) x **confirmation**
     *    ([Constants.PLACE_CONFIRMED_VISIT_WEIGHT]x for confirmed) x the visit's precomputed
     *    geometric **[net.extrawdw.apps.locationhistory.data.db.VisitEntity.reliability]** in [0,1]
     *    (count, accuracy, dispersion, duration), so a tight, sample-rich, long stay pulls harder
     *    than a brief noisy drive-by.
     *
     * Recomputing from scratch is what lets old visits fade. Call *after* the triggering visit is
     * persisted/linked so it's included. Fixed places are left untouched; the visit count is derived
     * live from the visits table, not stored.
     */
    suspend fun recordVisitToPlace(placeId: Long, nowMs: Long = System.currentTimeMillis()) {
        val place = dao.byId(placeId) ?: return
        if (place.fixed) return
        val visits = visitDao.listForPlace(placeId)

        var sumW = 0.0
        var sumLat = 0.0
        var sumLon = 0.0
        var sumRadius = 0.0

        // Immutable origin anchor (Google coordinates carry more authority than a local centroid).
        if (place.anchorLatitude != null && place.anchorLongitude != null) {
            val aw = if (place.googlePlaceId != null) Constants.PLACE_GOOGLE_ANCHOR_WEIGHT
            else Constants.PLACE_LOCAL_ANCHOR_WEIGHT
            sumW += aw
            sumLat += aw * place.anchorLatitude
            sumLon += aw * place.anchorLongitude
            sumRadius += aw * (place.anchorRadiusMeters ?: Constants.PLACE_MATCH_RADIUS_METERS)
        }

        for (v in visits) {
            val ageDays = (nowMs - v.startMs).coerceAtLeast(0L) / 86_400_000.0
            val recency = 2.0.pow(-ageDays / Constants.PLACE_VISIT_RECENCY_HALF_LIFE_DAYS)
            val confirmation = if (v.confirmed) Constants.PLACE_CONFIRMED_VISIT_WEIGHT else 1.0
            // Geometric trustworthiness (count, accuracy, dispersion, duration), precomputed in [0,1].
            val w = recency * confirmation * v.reliability
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
