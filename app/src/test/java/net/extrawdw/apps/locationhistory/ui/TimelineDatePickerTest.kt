package net.extrawdw.apps.locationhistory.ui

import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineDatePickerTest {
    @Test fun calendarDatesRemainTheSameAcrossTimezonesAndDst() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Singapore", "America/Los_Angeles", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (date in listOf("2026-10-05", "2026-03-08", "2026-11-01", "1969-12-31")) {
                    val day = LocalDate.parse(date).toEpochDay()
                    assertEquals("$date in $zone", day * 86_400_000L, pickerMillis(day))
                    assertEquals("$date in $zone", day, pickerDay(pickerMillis(day)))
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
