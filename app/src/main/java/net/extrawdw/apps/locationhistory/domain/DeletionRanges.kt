package net.extrawdw.apps.locationhistory.domain

/** Ordered union of half-open intervals; touching ranges have no retained data between them. */
internal fun unionTimeRanges(ranges: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    val out = mutableListOf<Pair<Long, Long>>()
    for (range in ranges.filter { it.first < it.second }.sortedBy { it.first }) {
        val last = out.lastOrNull()
        if (last != null && range.first <= last.second) {
            out[out.lastIndex] = last.first to maxOf(last.second, range.second)
        } else out += range
    }
    return out
}
