package net.extrawdw.apps.locationhistory.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Helpers that bucket epoch-millis timestamps into local-day and local-month keys.
 *
 * Every [LocationSample] stores a `dayEpoch` (days since the Unix epoch in the device's current
 * zone) so the timeline can query a day's data with an indexed integer range instead of scanning
 * timestamps — this keeps queries fast across many years of recording. Month keys drive the
 * partitioned, backup-friendly export.
 */
object TimeBuckets {

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Local-day index (days since epoch) for a timestamp, used as an indexed query/partition key. */
    fun dayEpoch(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        return date.toEpochDay()
    }

    fun dayEpoch(date: LocalDate): Long = date.toEpochDay()

    fun localDate(dayEpoch: Long): LocalDate = LocalDate.ofEpochDay(dayEpoch)

    /** Inclusive start / exclusive end epoch-millis for the local day containing [dayEpoch]. */
    fun dayRangeMillis(dayEpoch: Long, zone: ZoneId = ZoneId.systemDefault()): LongRange {
        val date = LocalDate.ofEpochDay(dayEpoch)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until end
    }

    /** Stable "YYYY-MM" key used to name monthly export partitions. */
    fun monthKey(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val ym = YearMonth.from(Instant.ofEpochMilli(timestampMs).atZone(zone))
        return "%04d-%02d".format(ym.year, ym.monthValue)
    }
}
