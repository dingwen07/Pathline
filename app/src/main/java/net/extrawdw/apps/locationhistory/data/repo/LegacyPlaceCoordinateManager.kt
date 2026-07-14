package net.extrawdw.apps.locationhistory.data.repo

import androidx.room.withTransaction
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateRepairDecision
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateProfiles
import net.extrawdw.apps.locationhistory.core.coordinates.GooglePlacesCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.core.coordinates.getOrNull
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.PlaceCoordinateRepairDao
import net.extrawdw.apps.locationhistory.data.db.PlaceCoordinateRepairEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only path that may change a legacy place's coordinate state. It never touches samples,
 * visits, trips, geofences, or GPX inputs. Every state/geometry change is journaled in the same
 * encrypted Room transaction and serialized against timeline maintenance.
 */
@Singleton
class LegacyPlaceCoordinateManager @Inject constructor(
    private val db: AppDatabase,
    private val placeDao: PlaceDao,
    private val visitDao: VisitDao,
    private val repairDao: PlaceCoordinateRepairDao,
    private val adapter: GoogleAndroidCoordinateAdapter,
    private val writeLock: TimelineWriteLock,
) {
    private val historicalProfile =
        GoogleAndroidCoordinateProfiles.HISTORICAL_PLACES_ANDROID_5_2_MAINLAND_2026_07
    private val historicalMapProfile =
        GoogleAndroidCoordinateProfiles.HISTORICAL_MAP_ANDROID_MAINLAND_2026_07_USER_CONFIRMED

    /** Idempotently unlock only rows whose persisted numbers prove a no-transform interpretation. */
    suspend fun classifySafeRows() = writeLock.withLock {
        db.withTransaction {
            placeDao.unresolvedCoordinates().forEach { current ->
                val classification = when {
                    isExactHistoricalIdentity(current) ->
                        PlaceCoordinateState.WGS84_CANONICAL to
                                PlaceCoordinateRepairDecision.AUTO_OUTSIDE_MAINLAND_IDENTITY

                    isProvablyUntouchedLocal(current) ->
                        PlaceCoordinateState.WGS84_CANONICAL to
                                PlaceCoordinateRepairDecision.AUTO_UNTOUCHED_LOCAL_IDENTITY

                    current.coordinateState == PlaceCoordinateState.UNKNOWN &&
                            current.source == PlaceSource.MAPS &&
                            current.hasCompleteAnchor() && current.centerRawEqualsAnchor() ->
                        PlaceCoordinateState.LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE to
                                PlaceCoordinateRepairDecision.AUTO_CLASSIFIED_GOOGLE_PROVIDER_BASELINE

                    else -> null
                } ?: return@forEach
                if (classification.first == current.coordinateState) return@forEach

                val repaired = current.copy(coordinateState = classification.first)
                repairDao.insert(
                    current.journal(
                        repaired = repaired,
                        decision = classification.second,
                        profileId = historicalProfile.id,
                    )
                )
                placeDao.update(repaired)
            }
        }
    }

    /** Apply one explicit interpretation choice; stale editor snapshots fail closed. */
    suspend fun repair(
        original: PlaceEntity,
        decision: PlaceCoordinateRepairDecision,
    ): Boolean = writeLock.withLock {
        db.withTransaction {
            val current = placeDao.byId(original.id) ?: return@withTransaction false
            if (current.coordinateState == PlaceCoordinateState.WGS84_CANONICAL ||
                !current.geometryRawEquals(original)
            ) return@withTransaction false
            if (current.coordinateState ==
                PlaceCoordinateState.LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE &&
                decision.usesSavedCenter()
            ) return@withTransaction false

            val canonical = when (decision) {
                PlaceCoordinateRepairDecision.SAVED_CENTER_AS_WGS84 ->
                    validWgs(current.latitude, current.longitude)

                PlaceCoordinateRepairDecision.SAVED_CENTER_AS_HISTORICAL_MAP ->
                    adapter.fromMapInteraction(
                        net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate(
                            current.latitude,
                            current.longitude,
                        ),
                        historicalMapProfile,
                    ).getOrNull()

                PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_WGS84 -> {
                    if (!current.hasCompleteAnchor()) null
                    else validWgs(current.anchorLatitude!!, current.anchorLongitude!!)
                }

                PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER -> {
                    if (!current.hasCompleteAnchor()) null
                    else adapter.fromPlacesResult(
                        GooglePlacesCoordinate(
                            current.anchorLatitude!!,
                            current.anchorLongitude!!,
                        ),
                        historicalProfile,
                    ).getOrNull()
                }

                PlaceCoordinateRepairDecision.AUTO_OUTSIDE_MAINLAND_IDENTITY,
                PlaceCoordinateRepairDecision.AUTO_UNTOUCHED_LOCAL_IDENTITY,
                PlaceCoordinateRepairDecision.AUTO_CLASSIFIED_GOOGLE_PROVIDER_BASELINE,
                PlaceCoordinateRepairDecision.UNKNOWN -> null
            } ?: return@withTransaction false

            // A legacy mixed center is never interpreted or inverse-transformed. Baseline choices
            // replace it with the normalized baseline; saved-center choices were rejected above.
            val repaired = current.copy(
                latitude = canonical.latitude,
                longitude = canonical.longitude,
                anchorLatitude = canonical.latitude,
                anchorLongitude = canonical.longitude,
                coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
                source = if (decision.usesGoogleBaseline()) PlaceSource.MAPS else current.source,
            )
            repairDao.insert(
                current.journal(
                    repaired = repaired,
                    decision = decision,
                    profileId = when (decision) {
                        PlaceCoordinateRepairDecision
                            .GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER -> historicalProfile.id

                        PlaceCoordinateRepairDecision
                            .SAVED_CENTER_AS_HISTORICAL_MAP -> historicalMapProfile.id

                        else -> null
                    },
                )
            )
            placeDao.update(repaired)
            true
        }
    }

    suspend fun hasUndo(placeId: Long): Boolean {
        val current = placeDao.byId(placeId) ?: return false
        val repair = repairDao.latestActive(placeId) ?: return false
        return repair.decision.isUserRepair() && current.matchesRepaired(repair)
    }

    /** Undo only if no later maintenance/editor write changed the repaired geometry. */
    suspend fun undo(currentSnapshot: PlaceEntity): Boolean = writeLock.withLock {
        db.withTransaction {
            val current = placeDao.byId(currentSnapshot.id) ?: return@withTransaction false
            if (!current.geometryRawEquals(currentSnapshot)) return@withTransaction false
            val repair = repairDao.latestActive(current.id) ?: return@withTransaction false
            if (!repair.decision.isUserRepair() || !current.matchesRepaired(repair)) {
                return@withTransaction false
            }

            placeDao.update(
                current.copy(
                    latitude = repair.originalLatitude,
                    longitude = repair.originalLongitude,
                    radiusMeters = repair.originalRadiusMeters,
                    anchorLatitude = repair.originalAnchorLatitude,
                    anchorLongitude = repair.originalAnchorLongitude,
                    anchorRadiusMeters = repair.originalAnchorRadiusMeters,
                    coordinateState = repair.originalCoordinateState,
                    source = repair.originalSource,
                )
            )
            repairDao.markUndone(repair.id, System.currentTimeMillis())
            true
        }
    }

    private suspend fun isProvablyUntouchedLocal(place: PlaceEntity): Boolean {
        if (place.source == PlaceSource.MAPS || place.googlePlaceId != null ||
            !place.hasCompleteAnchor() || !place.centerRawEqualsAnchor()
        ) return false
        return visitDao.listForPlace(place.id).any { visit ->
            rawEquals(place.anchorLatitude!!, visit.centroidLatitude) &&
                    rawEquals(place.anchorLongitude!!, visit.centroidLongitude)
        }
    }

    private fun isExactHistoricalIdentity(place: PlaceEntity): Boolean {
        if (!identityBothWays(place.latitude, place.longitude)) return false
        return when {
            place.anchorLatitude == null && place.anchorLongitude == null -> true
            place.hasCompleteAnchor() ->
                identityBothWays(place.anchorLatitude!!, place.anchorLongitude!!)
            else -> false
        }
    }

    private fun identityBothWays(latitude: Double, longitude: Double): Boolean {
        val wgs = validWgs(latitude, longitude) ?: return false
        val forward = adapter.toPlacesRequest(wgs, historicalProfile).getOrNull() ?: return false
        val inverse = adapter.fromPlacesResult(
            GooglePlacesCoordinate(latitude, longitude),
            historicalProfile,
        ).getOrNull() ?: return false
        return rawEquals(latitude, forward.latitude) && rawEquals(longitude, forward.longitude) &&
                rawEquals(latitude, inverse.latitude) && rawEquals(longitude, inverse.longitude)
    }

    private fun validWgs(latitude: Double, longitude: Double): Wgs84Coordinate? =
        if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
        ) Wgs84Coordinate(latitude, longitude) else null

    private fun PlaceEntity.hasCompleteAnchor(): Boolean =
        anchorLatitude != null && anchorLongitude != null

    private fun PlaceEntity.centerRawEqualsAnchor(): Boolean =
        rawEquals(latitude, anchorLatitude!!) && rawEquals(longitude, anchorLongitude!!)

    private fun PlaceEntity.geometryRawEquals(other: PlaceEntity): Boolean =
        rawEquals(latitude, other.latitude) && rawEquals(longitude, other.longitude) &&
                rawEquals(radiusMeters, other.radiusMeters) &&
                rawEquals(anchorLatitude, other.anchorLatitude) &&
                rawEquals(anchorLongitude, other.anchorLongitude) &&
                rawEquals(anchorRadiusMeters, other.anchorRadiusMeters) &&
                coordinateState == other.coordinateState && source == other.source

    private fun PlaceEntity.matchesRepaired(repair: PlaceCoordinateRepairEntity): Boolean =
        rawEquals(latitude, repair.repairedLatitude) &&
                rawEquals(longitude, repair.repairedLongitude) &&
                rawEquals(radiusMeters, repair.repairedRadiusMeters) &&
                rawEquals(anchorLatitude, repair.repairedAnchorLatitude) &&
                rawEquals(anchorLongitude, repair.repairedAnchorLongitude) &&
                rawEquals(anchorRadiusMeters, repair.repairedAnchorRadiusMeters) &&
                coordinateState == repair.repairedCoordinateState &&
                source == repair.repairedSource

    private fun PlaceEntity.journal(
        repaired: PlaceEntity,
        decision: PlaceCoordinateRepairDecision,
        profileId: String?,
    ) = PlaceCoordinateRepairEntity(
        placeId = id,
        originalLatitude = latitude,
        originalLongitude = longitude,
        originalRadiusMeters = radiusMeters,
        originalAnchorLatitude = anchorLatitude,
        originalAnchorLongitude = anchorLongitude,
        originalAnchorRadiusMeters = anchorRadiusMeters,
        originalCoordinateState = coordinateState,
        originalSource = source,
        repairedLatitude = repaired.latitude,
        repairedLongitude = repaired.longitude,
        repairedRadiusMeters = repaired.radiusMeters,
        repairedAnchorLatitude = repaired.anchorLatitude,
        repairedAnchorLongitude = repaired.anchorLongitude,
        repairedAnchorRadiusMeters = repaired.anchorRadiusMeters,
        repairedCoordinateState = repaired.coordinateState,
        repairedSource = repaired.source,
        decision = decision,
        profileId = profileId,
        repairedAtMs = System.currentTimeMillis(),
    )

    private fun rawEquals(first: Double, second: Double): Boolean =
        first.toRawBits() == second.toRawBits()

    private fun rawEquals(first: Double?, second: Double?): Boolean = when {
        first == null || second == null -> first == null && second == null
        else -> rawEquals(first, second)
    }

    private fun PlaceCoordinateRepairDecision.isUserRepair(): Boolean = when (this) {
        PlaceCoordinateRepairDecision.SAVED_CENTER_AS_WGS84,
        PlaceCoordinateRepairDecision.SAVED_CENTER_AS_HISTORICAL_MAP,
        PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_WGS84,
        PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER -> true

        PlaceCoordinateRepairDecision.UNKNOWN,
        PlaceCoordinateRepairDecision.AUTO_OUTSIDE_MAINLAND_IDENTITY,
        PlaceCoordinateRepairDecision.AUTO_UNTOUCHED_LOCAL_IDENTITY,
        PlaceCoordinateRepairDecision.AUTO_CLASSIFIED_GOOGLE_PROVIDER_BASELINE -> false
    }

    private fun PlaceCoordinateRepairDecision.usesSavedCenter(): Boolean = when (this) {
        PlaceCoordinateRepairDecision.SAVED_CENTER_AS_WGS84,
        PlaceCoordinateRepairDecision.SAVED_CENTER_AS_HISTORICAL_MAP -> true

        else -> false
    }

    private fun PlaceCoordinateRepairDecision.usesGoogleBaseline(): Boolean = when (this) {
        PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_WGS84,
        PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER -> true

        else -> false
    }
}
