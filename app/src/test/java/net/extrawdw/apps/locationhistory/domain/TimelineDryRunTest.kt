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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * NOT a CI test — a **dry run** of the real timeline builder over an exported Pathline backup.
 * Skipped unless `-DdryRunDir=<dir>` points at a folder of CSVs (places/samples/visits/trips). The
 * `scripts/timeline-dryrun.sh` wrapper unpacks a backup dir/zip into those CSVs, forwards the dump's
 * zone and an optional window, and runs this. Wires the production [TimelineRebuilder] +
 * [TimelineMerger] + [VisitDetector] + [TripSegmenter] + [HeuristicClassifier] with the in-memory
 * fakes and an offline place matcher (nearest saved place — same rule as [PlaceMatcher], no Google),
 * annotates every unconfirmed trip with the Doppler drift-vs-walk verdict, and writes a report to
 * `<dir>/report.txt`.
 *
 * System properties: `-DdryRunDir` (CSV dir), `-Duser.timezone` (the dump's zone — used for day
 * bucketing AND display), `-DdryRunSince` / `-DdryRunUntil` (optional `yyyy-MM-dd` in that zone, or
 * epoch ms — restrict the REPORT to that window; the rebuild always runs over the full data so
 * lookback/confirmed context is intact).
 */
class TimelineDryRunTest {

    private val zone: ZoneId = ZoneId.systemDefault() // set via -Duser.timezone (the dump's zone)
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private fun lt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(fmt)

    /** A report-window bound from a system property: epoch-ms, or a `yyyy-MM-dd` local date (with
     *  [plusDays] added so an `until` date covers its whole day). Absent -> [default]. */
    private fun bound(prop: String, plusDays: Long, default: Long): Long {
        val v = System.getProperty(prop)?.trim().orEmpty()
        if (v.isEmpty()) return default
        v.toLongOrNull()?.let { return it }
        return runCatching {
            LocalDate.parse(v).plusDays(plusDays).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrDefault(default)
    }

    private val since get() = bound("dryRunSince", 0, Long.MIN_VALUE)
    private val until get() = bound("dryRunUntil", 1, Long.MAX_VALUE)
    private fun TripEntity.inRange() = startMs < until && endMs > since
    private fun VisitEntity.inRange() = startMs < until && endMs > since

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

    // --- drift-vs-walk probe ----------------------------------------------------------------------

    /** Fraction of a window's included fixes within the nearest place's radius — the geometry signal. */
    private fun inPlaceFrac(samples: List<LocationSampleEntity>, places: List<PlaceEntity>, s: Long, e: Long): Pair<Double, String?> {
        val fixes = samples.filter { it.timestampMs in s until e && it.includedInComputation }
        if (fixes.isEmpty()) return 0.0 to null
        val cLat = fixes.map { it.latitude }.average(); val cLon = fixes.map { it.longitude }.average()
        val place = places.minByOrNull { Geo.distanceMeters(cLat, cLon, it.latitude, it.longitude) } ?: return 0.0 to null
        val radius = maxOf(place.radiusMeters, Constants.STATIONARY_RADIUS_METERS)
        val within = fixes.count { Geo.distanceMeters(place.latitude, place.longitude, it.latitude, it.longitude) <= radius }
        return within.toDouble() / fixes.size to place.name
    }

    /** GPS-Doppler statistics over a span's included fixes — the speed half of the drift-vs-walk test.
     *  Drift reads ~0 moving; a real near-place walk 0.5-0.7 (the signal the geometry-only in-place flag
     *  misses). A null speed is never "moving" (never folded to 0). [n] exposes sparse spans (the "too
     *  few fixes" caveat: a sparse gap-fill can read high-Doppler off a handful of noisy fixes). */
    private data class Dop(val n: Int, val validPct: Int, val ge08: Int, val ge12: Int)

    private fun dopplerStats(samples: List<LocationSampleEntity>, s: Long, e: Long): Dop {
        val fixes = samples.filter { it.timestampMs in s until e && it.includedInComputation }
        if (fixes.isEmpty()) return Dop(0, 0, 0, 0)
        val valid = fixes.count { it.speed != null }
        val ge08 = fixes.count { (it.speed ?: 0f) >= Constants.WALK_SPEED_FLOOR_MPS }
        val ge12 = fixes.count { (it.speed ?: 0f) >= Constants.DRIFT_MOVING_SPEED_MPS }
        return Dop(fixes.size, 100 * valid / fixes.size, 100 * ge08 / fixes.size, 100 * ge12 / fixes.size)
    }

    // --- harness ----------------------------------------------------------------------------------

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

        fun rebuildAllDays() = runBlocking {
            val days = samples.map { TimeBuckets.dayEpoch(it.timestampMs) }.distinct().sorted()
            for (d in days) rebuilder.rebuildDay(d)
        }

        fun fromScratch() = apply { runBlocking { placeDao.seed(*places.toTypedArray()); sampleDao.insertAll(samples) }; rebuildAllDays() }

        fun realistic(visits: List<VisitEntity>, trips: List<TripEntity>) = apply {
            runBlocking {
                placeDao.seed(*places.toTypedArray()); sampleDao.insertAll(samples)
                visits.filter { it.confirmed }.forEach { visitDao.seed(it) }
                trips.filter { it.confirmed }.forEach { tripDao.seed(it) }
            }
            rebuildAllDays()
        }
    }

    @Test
    fun dryRun_realData_confirmedVsRebuilt() {
        val d = dir()
        assumeTrue("set -DdryRunDir=<dump-csv-dir> to run (see scripts/timeline-dryrun.sh)", d != null)
        d!!
        val places = loadPlaces(d)
        val samples = loadSamples(d)
        val allVisits = loadVisits(d)
        val allTrips = loadTrips(d)
        val sb = StringBuilder()
        fun line(s: String) = sb.appendLine(s)

        val window = when {
            since == Long.MIN_VALUE && until == Long.MAX_VALUE -> "full"
            else -> "${if (since == Long.MIN_VALUE) "(start)" else lt(since)} -> " +
                    (if (until == Long.MAX_VALUE) "(end)" else lt(until))
        }
        line("=== Pathline timeline dry-run (real data) ===")
        line("samples=${samples.size}  places=${places.size}  data ${lt(samples.first().timestampMs)} -> ${lt(samples.last().timestampMs)}  zone=$zone")
        line("report window: $window")
        line("dump: visits=${allVisits.size} (confirmed ${allVisits.count { it.confirmed }})  trips=${allTrips.size} (confirmed ${allTrips.count { it.confirmed }})")

        val walkMovingPct = (Constants.WALK_MOVING_FIX_FRACTION * 100).toInt()

        // Drift = the device never actually translated: few valid-Doppler-moving fixes (< the walk
        // gate) AND a near-zero along-path speed. True whether the coarse fixes sat INSIDE the place
        // radius (in-place drift) or fanned out PAST it (wide-scatter drift — 100 m Wi-Fi spikes, which
        // the geometry-only in-place flag can't see). A real trip clears this on EITHER signal (real
        // Doppler, or real ground covered), so it is never called drift. Doppler speed is primary; the
        // along-path average is a corroborating floor (both must be ~0). See docs/doppler-timeline-findings.md.
        fun isDrift(t: TripEntity, dop: Dop): Boolean {
            if (t.confirmed || dop.n < Constants.MIN_MOVING_WINDOW_FIXES) return false
            val secs = (t.endMs - t.startMs) / 1000.0
            val avgMps = if (secs > 0) t.distanceMeters / secs else 0.0
            return dop.ge08 < walkMovingPct && avgMps < 0.5
        }

        fun truePhantom(t: TripEntity) = isDrift(t, dopplerStats(samples, t.startMs, t.endMs))

        fun tripLine(t: TripEntity, tag: String): String {
            val (frac, pname) = inPlaceFrac(samples, places, t.startMs, t.endMs)
            val dop = dopplerStats(samples, t.startMs, t.endMs)
            val mins = (t.endMs - t.startMs) / 60000.0
            val inPlace = frac >= Constants.DRIFT_TRIP_INPLACE_FRACTION
            val probe = "dop ${dop.ge08}%>=0.8/${dop.ge12}%>=1.2 valid=${dop.validPct}% n=${dop.n}"
            val pct = (frac * 100).toInt()
            val flag = when {
                t.confirmed -> ""
                isDrift(t, dop) && inPlace -> "  <-- in-place $pct% @$pname; $probe => PHANTOM(in-place drift)"
                isDrift(t, dop) -> "  <-- scatters past @$pname (in-place $pct%); $probe => PHANTOM(wide-scatter drift)"
                inPlace -> "  <-- in-place $pct% @$pname; $probe => real WALK (near place)"
                else -> ""
            }
            return "  [$tag] ${lt(t.startMs)}->${lt(t.endMs)} ${"%.0f".format(mins)}m ${t.mode} ${"%.0f".format(t.distanceMeters)}m conf=${t.confirmed}$flag"
        }

        fun visitLines(visits: List<VisitEntity>, tag: String) {
            visits.filter { it.inRange() }.sortedBy { it.startMs }.forEach {
                line("  [$tag] ${lt(it.startMs)}->${lt(it.endMs)} \"${it.candidateName}\" conf=${it.confirmed} ongoing=${it.isOngoing} n=${it.sampleCount} r=${"%.0f".format(it.radiusMeters)}m")
            }
        }

        fun reportTrips(label: String, all: List<TripEntity>, tag: String) {
            val inRange = all.filter { !it.confirmed && it.inRange() }.sortedBy { it.startMs }
            val phantoms = inRange.count { truePhantom(it) }
            line("$label: ${inRange.size} builder trips in window; Doppler-confirmed drift phantoms (in-place + wide-scatter): $phantoms")
            inRange.forEach { line(tripLine(it, tag)) }
        }

        // ---- Section A: build from scratch (samples + places only) -------------------------------
        line("\n--- A) FROM SCRATCH (no seeded visits/trips) ---")
        val a = Harness(places, samples).fromScratch()
        reportTrips("from-scratch", a.tripDao.trips, "new")
        line("visits in window:")
        visitLines(a.visitDao.visits.sortedBy { it.startMs }, "Avisit")

        // ---- Section B: realistic (seed confirmed ground truth, rebuild fills gaps) --------------
        line("\n--- B) REALISTIC (seed confirmed visits+trips, then rebuild) ---")
        val b = Harness(places, samples).realistic(allVisits, allTrips)
        reportTrips("realistic", b.tripDao.trips, "gen")
        line("visits in window (seeded confirmed + rebuilt):")
        visitLines(b.visitDao.visits, "Bvisit")

        // ---- The dump's own output + ground truth, for contrast ----------------------------------
        line("\n--- dump's unconfirmed trips in window (the on-device builder's output) ---")
        allTrips.filter { !it.confirmed && it.inRange() }.sortedBy { it.startMs }.forEach { line(tripLine(it, "dump")) }
        line("\n--- confirmed trips in window (ground truth) ---")
        allTrips.filter { it.confirmed && it.inRange() }.sortedBy { it.startMs }.forEach { line(tripLine(it, "GT")) }

        File(d, "report.txt").writeText(sb.toString())
        println(sb.toString())
    }
}
