package net.extrawdw.apps.locationhistory.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields

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

    /**
     * Monday-aligned week bucket as a `dayEpoch` (the Monday's epoch-day). The Unix epoch day 0
     * (1970-01-01) is a Thursday, so adding 3 before the divide aligns week starts to Monday.
     *
     * This MUST stay in lock-step with the SQL the dirty-week triggers use:
     * `((dayEpoch + 3) / 7) * 7 - 3`. SQLite integer division truncates toward zero, which matches
     * Kotlin's `Long` division for the non-negative epoch-days we deal with (dates ≥ 1970).
     */
    fun weekStartDayEpoch(dayEpoch: Long): Long = ((dayEpoch + 3) / 7) * 7 - 3

    /** [weekStartDayEpoch] for the week containing [timestampMs]. */
    fun weekStart(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        weekStartDayEpoch(dayEpoch(timestampMs, zone))

    /** ISO-style "YYYY-Www" label naming a week partition, derived from its Monday. */
    fun weekKey(weekStartDayEpoch: Long): String {
        val monday = LocalDate.ofEpochDay(weekStartDayEpoch)
        val week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val year = monday.get(IsoFields.WEEK_BASED_YEAR)
        return "%04d-W%02d".format(year, week)
    }

    /** Inclusive-start / exclusive-end `dayEpoch` range covered by a week bucket. */
    fun weekDayRange(weekStartDayEpoch: Long): LongRange = weekStartDayEpoch until (weekStartDayEpoch + 7)
}
