package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import javax.inject.Inject

/** Initial suggestions only: user choices remain authoritative after the split editor opens. */
class SplitActivityClassifier @Inject constructor(
    private val visitDetector: VisitDetector,
    private val classifier: HeuristicClassifier,
) {
    fun classify(samples: List<LocationSampleEntity>, fallback: SegmentType): SegmentType {
        val usable = samples.filter { it.includedInComputation }
        if (usable.size < 2) return fallback
        val duration = usable.last().timestampMs - usable.first().timestampMs
        if (duration <= 0) return fallback
        // Use the same stay detector and transport classifier as timeline reconstruction.
        val stays = visitDetector.detectVisits(usable)
        if (stays.sumOf { it.durationMs } > duration / 2) return SegmentType.Stationary
        val mode = classifier.classifyTransport(usable).mode
        return if (mode == TransportMode.UNKNOWN) fallback else SegmentType.Moving(mode)
    }
}
