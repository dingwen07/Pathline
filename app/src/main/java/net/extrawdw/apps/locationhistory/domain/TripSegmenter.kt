package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.ml.Classifier
import javax.inject.Inject
import javax.inject.Singleton

/** A single-mode portion of a trip, ready to be persisted as a TripSegment. */
data class SegmentResult(
    val startMs: Long,
    val endMs: Long,
    val mode: TransportMode,
    val confidence: Float,
    val encodedPolyline: String,
    val distanceMeters: Double,
)

/**
 * Splits the moving samples of a trip into one or more transport-mode segments. A sliding window
 * is classified along the trip, contiguous same-mode samples are merged, and short runs are folded
 * into a neighbour so brief misclassifications don't fragment the timeline. Each final segment is
 * re-classified over all its samples for a stable mode and confidence — handling multi-modal trips
 * such as walk -> light rail -> walk.
 */
@Singleton
class TripSegmenter @Inject constructor(
    private val classifier: Classifier,
) {

    fun segment(samples: List<LocationSampleEntity>): List<SegmentResult> {
        val usable = samples.filter { it.includedInComputation }
        if (usable.size < 2) return emptyList()

        // 1. Per-sample mode from the window centred on each sample.
        val window = Constants.SEGMENT_WINDOW_SIZE
        val perSample = ArrayList<TransportMode>(usable.size)
        for (i in usable.indices) {
            val from = (i - window / 2).coerceAtLeast(0)
            val to = (i + window / 2 + 1).coerceAtMost(usable.size)
            perSample.add(classifier.classifyTransport(usable.subList(from, to)).mode)
        }

        // 2. Merge contiguous same-mode runs into raw segments (index ranges).
        val ranges = ArrayList<IntRange>()
        var start = 0
        for (i in 1 until perSample.size) {
            if (perSample[i] != perSample[start]) {
                ranges.add(start until i)
                start = i
            }
        }
        ranges.add(start until perSample.size)

        // 3. Fold runs shorter than the minimum segment distance into the previous run.
        val merged = ArrayList<IntRange>()
        for (r in ranges) {
            val dist = Geo.pathLengthMeters(usable.slice(r).map { it.latitude to it.longitude })
            if (merged.isNotEmpty() && dist < Constants.MIN_SEGMENT_DISTANCE_METERS) {
                val last = merged.removeAt(merged.size - 1)
                merged.add(last.first..r.last)
            } else {
                merged.add(r)
            }
        }

        // 4. Re-classify each final segment over all its samples for a stable result.
        return merged.map { range ->
            val seg = usable.slice(range)
            val classification = classifier.classifyTransport(seg)
            val points = seg.map { it.latitude to it.longitude }
            SegmentResult(
                startMs = seg.first().timestampMs,
                endMs = seg.last().timestampMs,
                mode = classification.mode,
                confidence = classification.confidence,
                encodedPolyline = Geo.encodePolyline(points),
                distanceMeters = Geo.pathLengthMeters(points),
            )
        }
    }
}
