package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.RecordingRepository
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * NOT a CI test — a **dry run** of the real timeline builder over the user's exported sample/place
 * data. Skipped unless `-DdryRunDir=<path>` points at a folder of CSVs (places/samples/visits/trips)
 * produced from a Pathline dump. Wires the production [TimelineRebuilder] + [TimelineMerger] +
 * [VisitDetector] + [TripSegmenter] + [HeuristicClassifier] with the in-memory fakes and an offline
 * place matcher (nearest saved place — same rule as [PlaceMatcher], no Google), then writes a
 * confirmed-vs-rebuilt report to `<dir>/report.txt`.
 */
class TimelineDryRunTest {

    private val zone = ZoneOffset.ofHours(-4) // device tz in the dump (EDT)
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private fun lt(ms: Long) = Instant.ofEpochMilli(ms).atOffset(zone).format(fmt)

    private fun dir(): File? =
        (System.getProperty("dryRunDir") ?: "/tmp/pathline-dryrun").let(::File).takeIf { it.isDirectory }

    // --- CSV loaders ------------------------------------------------------------------------------

    private fun rows(f: File): List<Map<String, String>> {
        val lines = f.readLines().filter { it.isNotBlank() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    private fun loadPlaces(d: File) = rows(File(d, "places.csv")).map {
        PlaceEntity(
            id = it["id"]!!.toLong(), name = it["name"]!!, latitude = it["lat"]!!.toDouble(),
            longitude = it["lon"]!!.toDouble(), radiusMeters = it["radius"]!!.toDouble(),
            category = null, source = runCatching { PlaceSource.valueOf(it["source"]!!) }.getOrDefault(PlaceSource.INFERRED),
            googlePlaceId = null, address = null, confirmed = it["confirmed"] == "1", createdAtMs = 0L,
        )
    }

    private fun loadSamples(d: File) = rows(File(d, "samples.csv")).map {
        val ts = it["ts"]!!.toLong()
        LocationSampleEntity(
            timestampMs = ts, dayEpoch = TimeBuckets.dayEpoch(ts),
            latitude = it["lat"]!!.toDouble(), longitude = it["lon"]!!.toDouble(),
            altitude = null, accuracy = it["acc"]?.toFloatOrNull(), verticalAccuracyMeters = null,
            bearing = null, bearingAccuracyDegrees = null, speed = it["speed"]?.toFloatOrNull(),
            speedAccuracyMetersPerSecond = null, provider = "fused", isMock = false,
            elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null, isCharging = null,
            networkTransport = null, networkTypeName = null, cellSignalDbm = null,
            hasCellService = it["hasCell"]?.takeIf { c -> c.isNotEmpty() }?.let { c -> c == "1" },
            wifiSsid = null, wifiBssid = null, screenOn = null,
            arActivity = it["ar"]?.takeIf { a -> a.isNotEmpty() }, arConfidence = null,
            devicePhysicalState = runCatching { DevicePhysicalState.valueOf(it["state"]!!) }
                .getOrDefault(DevicePhysicalState.UNKNOWN),
            devicePhysicalStateConfidence = 1f, includedInComputation = it["incl"] == "1",
        )
    }

    private fun loadVisits(d: File) = rows(File(d, "visits.csv")).map {
        VisitEntity(
            id = it["id"]!!.toLong(), placeId = it["placeId"]?.toLongOrNull(), candidateName = null,
            candidateGooglePlaceId = null, candidateLatitude = null, candidateLongitude = null,
            startMs = it["start"]!!.toLong(), endMs = it["end"]!!.toLong(),
            dayEpoch = TimeBuckets.dayEpoch(it["start"]!!.toLong()),
            centroidLatitude = it["centLat"]!!.toDouble(), centroidLongitude = it["centLon"]!!.toDouble(),
            radiusMeters = it["radius"]!!.toDouble(), sampleCount = it["sampleCount"]?.toIntOrNull() ?: 0,
            reliability = it["reliability"]?.toFloatOrNull() ?: 0f, confirmed = it["confirmed"] == "1",
            confidence = it["confidence"]?.toFloatOrNull() ?: 0.5f, isOngoing = it["ongoing"] == "1",
        )
    }

    private fun loadTrips(d: File) = rows(File(d, "trips.csv")).map {
        TripEntity(
            id = it["id"]!!.toLong(), fromVisitId = it["fromVisit"]?.toLongOrNull(),
            toVisitId = it["toVisit"]?.toLongOrNull(), startMs = it["start"]!!.toLong(),
            endMs = it["end"]!!.toLong(), dayEpoch = TimeBuckets.dayEpoch(it["start"]!!.toLong()),
            mode = runCatching { net.extrawdw.apps.locationhistory.core.TransportMode.valueOf(it["mode"]!!) }
                .getOrDefault(net.extrawdw.apps.locationhistory.core.TransportMode.UNKNOWN),
            modeConfidence = 0.6f, encodedPolyline = "", distanceMeters = it["dist"]!!.toDouble(),
            confirmed = it["confirmed"] == "1",
        )
    }

    // --- harness ----------------------------------------------------------------------------------

    private fun placeName(places: List<PlaceEntity>, lat: Double, lon: Double): String? {
        val box = Geo.boundingBox(lat, lon, Constants.PLACE_MATCH_RADIUS_METERS)
        return places.filter { it.latitude in box[0]..box[2] && it.longitude in box[1]..box[3] }
            .map { it to Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= maxOf(Constants.PLACE_MATCH_RADIUS_METERS, it.first.radiusMeters) }
            .minByOrNull { it.second }?.first?.name
    }

    /** Fraction of a window's included fixes within the nearest place's radius — the drift signal. */
    private fun inPlaceFrac(samples: List<LocationSampleEntity>, places: List<PlaceEntity>, s: Long, e: Long): Pair<Double, String?> {
        val fixes = samples.filter { it.timestampMs in s until e && it.includedInComputation }
        if (fixes.isEmpty()) return 0.0 to null
        val cLat = fixes.map { it.latitude }.average(); val cLon = fixes.map { it.longitude }.average()
        val place = places.minByOrNull { Geo.distanceMeters(cLat, cLon, it.latitude, it.longitude) } ?: return 0.0 to null
        val radius = maxOf(place.radiusMeters, Constants.STATIONARY_RADIUS_METERS)
        val within = fixes.count { Geo.distanceMeters(place.latitude, place.longitude, it.latitude, it.longitude) <= radius }
        return within.toDouble() / fixes.size to place.name
    }

    private class Harness(val places: List<PlaceEntity>, val samples: List<LocationSampleEntity>) {
        val visitDao = FakeVisitDao()
        val tripDao = FakeTripDao(visitDao)
        val sampleDao = FakeLocationSampleDao()
        val placeDao = FakePlaceDao()
        val store = AnnotationStore(FakeTagDao(), FakeAnnotationDao(), FakeConceptDao())
        val nowMs = samples.maxOf { it.timestampMs } + 60_000L

        val matchPlace: suspend (Double, Double) -> PlaceMatch = { lat, lon ->
            val box = Geo.boundingBox(lat, lon, Constants.PLACE_MATCH_RADIUS_METERS)
            val nearest = placeDao.inBoundingBox(box[0], box[1], box[2], box[3])
                .map { it to Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }
                .filter { it.second <= maxOf(Constants.PLACE_MATCH_RADIUS_METERS, it.first.radiusMeters) }
                .minByOrNull { it.second }
            if (nearest == null) PlaceMatch.None else {
                val (p, dist) = nearest
                val prox = (1.0 - dist / Constants.PLACE_MATCH_RADIUS_METERS).coerceIn(0.0, 1.0)
                PlaceMatch.Local(p, (if (p.confirmed) 0.85 + 0.15 * prox else 0.5 * prox).toFloat())
            }
        }
        val rebuilder = TimelineRebuilder(
            sampleDao = sampleDao, visitDao = visitDao, tripDao = tripDao,
            locationRepository = LocationRepository(sampleDao),
            recordingRepository = RecordingRepository(tripDao),
            placeRepository = PlaceRepository(placeDao, visitDao, store),
            visitDetector = VisitDetector(),
            merger = TimelineMerger(visitDao, tripDao, sampleDao, placeDao, store),
            matchPlace = { lat, lon -> matchPlace(lat, lon) },
            segmentTrips = TripSegmenter(HeuristicClassifier())::segment,
            inTransaction = { block -> block() }, now = { nowMs }, log = {},
        )

        fun run() = runBlocking {
            placeDao.seed(*places.toTypedArray())
            sampleDao.insertAll(samples)
            val days = samples.map { TimeBuckets.dayEpoch(it.timestampMs) }.distinct().sorted()
            for (d in days) rebuilder.rebuildDay(d)
        }
    }

    @Test
    fun dryRun_realData_confirmedVsRebuilt() {
        val d = dir()
        assumeTrue("set -DdryRunDir=<dump-csv-dir> to run", d != null)
        d!!
        val places = loadPlaces(d)
        val samples = loadSamples(d)
        val allVisits = loadVisits(d)
        val allTrips = loadTrips(d)
        val sb = StringBuilder()
        fun line(s: String) = sb.appendLine(s)

        line("=== Pathline timeline dry-run (real data) ===")
        line("samples=${samples.size}  places=${places.size}  window ${lt(samples.first().timestampMs)} -> ${lt(samples.last().timestampMs)}")
        line("dump: visits=${allVisits.size} (confirmed ${allVisits.count { it.confirmed }})  trips=${allTrips.size} (confirmed ${allTrips.count { it.confirmed }})")

        fun tripLine(t: TripEntity, tag: String): String {
            val (frac, pname) = inPlaceFrac(samples, places, t.startMs, t.endMs)
            val mins = (t.endMs - t.startMs) / 60000.0
            val flag = if (!t.confirmed && frac >= Constants.DRIFT_TRIP_INPLACE_FRACTION) "  <-- PHANTOM(in-place ${(frac * 100).toInt()}% @${pname})" else ""
            return "  [$tag] ${lt(t.startMs)}->${lt(t.endMs)} ${"%.0f".format(mins)}m ${t.mode} ${"%.0f".format(t.distanceMeters)}m conf=${t.confirmed}$flag"
        }

        // ---- Section A: build from scratch (samples + places only) -------------------------------
        line("\n--- A) FROM SCRATCH (no seeded visits/trips) ---")
        val a = Harness(places, samples).apply { run() }
        val aVisits = a.visitDao.visits.sortedBy { it.startMs }
        val aTrips = a.tripDao.trips.sortedBy { it.startMs }
        line("rebuilt: visits=${aVisits.size}  trips=${aTrips.size}")
        line("rebuilt trips:")
        aTrips.forEach { line(tripLine(it, "new")) }
        val aPhantom = aTrips.count { val (f, _) = inPlaceFrac(samples, places, it.startMs, it.endMs); f >= Constants.DRIFT_TRIP_INPLACE_FRACTION }
        line("from-scratch in-place(phantom) trips: $aPhantom")

        // ---- Section B: realistic (seed confirmed ground truth, rebuild fills gaps) --------------
        line("\n--- B) REALISTIC (seed confirmed visits+trips, then rebuild) ---")
        val b = Harness(places, samples)
        runBlocking {
            b.placeDao.seed(*places.toTypedArray())
            b.sampleDao.insertAll(samples)
            allVisits.filter { it.confirmed }.forEach { b.visitDao.seed(it) }
            allTrips.filter { it.confirmed }.forEach { b.tripDao.seed(it) }
            val days = samples.map { TimeBuckets.dayEpoch(it.timestampMs) }.distinct().sorted()
            for (day in days) b.rebuilder.rebuildDay(day)
        }
        val bUnconfirmedTrips = b.tripDao.trips.filter { !it.confirmed }.sortedBy { it.startMs }
        line("after rebuild: total trips=${b.tripDao.trips.size}, builder-generated (unconfirmed)=${bUnconfirmedTrips.size}")
        line("builder-generated trips:")
        bUnconfirmedTrips.forEach { line(tripLine(it, "gen")) }
        val bPhantom = bUnconfirmedTrips.count { val (f, _) = inPlaceFrac(samples, places, it.startMs, it.endMs); f >= Constants.DRIFT_TRIP_INPLACE_FRACTION }
        line("realistic in-place(phantom) trips remaining: $bPhantom")

        // ---- The dump's own unconfirmed trips, for old-vs-new contrast ---------------------------
        line("\n--- dump's unconfirmed trips (the on-device builder's output) ---")
        allTrips.filter { !it.confirmed }.sortedBy { it.startMs }.forEach { line(tripLine(it, "dump")) }

        line("\n--- confirmed trips (ground truth) ---")
        allTrips.filter { it.confirmed }.sortedBy { it.startMs }.forEach { line(tripLine(it, "GT")) }

        File(d, "report.txt").writeText(sb.toString())
        println(sb.toString())
    }
}
