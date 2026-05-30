package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity

/** A single day's timeline: an ordered list of visits and trips. */
data class TimelineDay(
    val dayEpoch: Long,
    val items: List<TimelineItem>,
)

/** One entry on the timeline — either a stay at a place or movement between places. */
sealed interface TimelineItem {
    val startMs: Long
    val endMs: Long
    val confirmed: Boolean

    data class VisitItem(
        val visit: VisitEntity,
        val place: PlaceEntity?,
    ) : TimelineItem {
        override val startMs get() = visit.startMs
        override val endMs get() = visit.endMs
        override val confirmed get() = visit.confirmed

        /** Best available display label: confirmed place > candidate suggestion > coordinates. */
        val displayName: String
            get() = place?.name
                ?: visit.candidateName
                ?: "%.4f, %.4f".format(visit.centroidLatitude, visit.centroidLongitude)
    }

    data class TripItem(
        val trip: TripEntity,
    ) : TimelineItem {
        override val startMs get() = trip.startMs
        override val endMs get() = trip.endMs
        override val confirmed get() = trip.confirmed
    }
}
