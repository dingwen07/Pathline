package net.extrawdw.apps.locationhistory.data.repo

import kotlinx.coroutines.flow.Flow
import net.extrawdw.apps.locationhistory.BuildConfig
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Read/write access to the append-only location-sample fact table. */
@Singleton
class LocationRepository @Inject constructor(
    private val dao: LocationSampleDao,
) {
    /** Persists a sample. Implausible fixes are still stored but flagged out of computation. */
    suspend fun record(sample: LocationSampleEntity): Long {
        val (included, reason) = computationEligibility(sample)
        if (!included) AppLog.i(TAG, "sample excluded from computation: $reason")
        return dao.insert(sample.copy(includedInComputation = included, exclusionReason = reason))
    }

    /** Persists one delivered batch in a single transaction (Room wraps list inserts in one),
     *  so a mid-batch process kill never leaves half a delivery and the commit cost is paid once. */
    suspend fun recordAll(samples: List<LocationSampleEntity>): List<Long> {
        if (samples.isEmpty()) return emptyList()
        return dao.insertAll(
            samples.map { sample ->
                val (included, reason) = computationEligibility(sample)
                if (!included) AppLog.i(TAG, "sample excluded from computation: $reason")
                sample.copy(includedInComputation = included, exclusionReason = reason)
            },
        )
    }

    suspend fun mostRecent(): LocationSampleEntity? = dao.mostRecent()

    /**
     * Mark samples within [startMs, endMs] that fall **outside** the stay circle as GPS drift
     * (excluded from computation). Used when the user marks a stay stationary — fixes outside the
     * yellow place circle are bogus. Returns how many were excluded.
     */
    suspend fun excludeDriftOutside(
        startMs: Long, endMs: Long, centerLat: Double, centerLon: Double, radiusMeters: Double,
        reason: String = "drift_outside_place",
    ): Int {
        var excluded = 0
        for (s in dao.range(startMs, endMs + 1)) {
            if (!s.includedInComputation) continue
            val d = net.extrawdw.apps.locationhistory.core.Geo.distanceMeters(
                centerLat, centerLon, s.latitude, s.longitude,
            )
            if (d > radiusMeters) {
                dao.markExcluded(s.id, reason); excluded++
            }
        }
        return excluded
    }

    suspend fun latest(limit: Int): List<LocationSampleEntity> = dao.latest(limit)

    suspend fun rangeForComputation(startMs: Long, endMs: Long): List<LocationSampleEntity> =
        dao.rangeForComputation(startMs, endMs)

    suspend fun range(startMs: Long, endMs: Long): List<LocationSampleEntity> =
        dao.range(startMs, endMs)

    fun observeByDay(dayEpoch: Long): Flow<List<LocationSampleEntity>> = dao.observeByDay(dayEpoch)

    fun observeCount(): Flow<Long> = dao.observeCount()

    fun observeRecordedDays(): Flow<List<Long>> = dao.observeRecordedDays()

    /**
     * All samples are saved; some are excluded from computation per the spec's rules. Mock locations
     * are rejected in release builds (anti-spoofing) but ACCEPTED in debug builds — otherwise the
     * Android emulator (whose fixes are all mock) can never form visits/trips for testing.
     */
    private fun computationEligibility(s: LocationSampleEntity): Pair<Boolean, String?> = when {
        s.isMock && !BuildConfig.DEBUG -> false to "mock_location"
        (s.accuracy ?: 0f) > 100f -> false to "low_accuracy"
        else -> true to null
    }

    private companion object {
        const val TAG = "LocationRepo"
    }
}
