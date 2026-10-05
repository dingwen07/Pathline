package net.extrawdw.apps.locationhistory.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Train
import android.content.Context
import android.app.LocaleManager
import android.content.res.Resources
import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import androidx.compose.ui.graphics.vector.ImageVector
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.TransportMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Small UI formatting helpers shared by the timeline and map screens. */
object Format {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    private val compactDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val preciseTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().format(timeFormatter)

    fun date(dayEpoch: Long): String = LocalDate.ofEpochDay(dayEpoch).format(dateFormatter)

    fun compactDate(dayEpoch: Long): String = LocalDate.ofEpochDay(dayEpoch).format(compactDateFormatter)

    fun preciseTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().format(preciseTimeFormatter)

    fun elapsed(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) {
            "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }

    /** System regional units remain independent of the app's selected display language. */
    fun speed(context: Context, metersPerSecond: Double): String = speedFormatter(context)(metersPerSecond)

    internal fun speedFormatter(context: Context): (Double) -> String {
        val systemLocale = context.getSystemService(LocaleManager::class.java)?.systemLocales?.get(0)
            ?: Resources.getSystem().configuration.locales[0]
        return speedFormatter(systemLocale, context.resources.configuration.locales[0])
    }

    internal fun speed(metersPerSecond: Double, systemLocale: Locale, displayLocale: Locale): String =
        speedFormatter(systemLocale, displayLocale)(metersPerSecond)

    private fun speedFormatter(systemLocale: Locale, displayLocale: Locale): (Double) -> String {
        val converter = NumberFormatter.withLocale(ULocale.forLocale(systemLocale))
            .unit(MeasureUnit.METER_PER_SECOND)
            .usage("default")
            .precision(Precision.maxFraction(1))
        val formatter = NumberFormatter.withLocale(ULocale.forLocale(displayLocale))
            .unit(converter.format(0).outputUnit)
            .unitWidth(NumberFormatter.UnitWidth.SHORT)
            .precision(Precision.maxFraction(1))
        return { metersPerSecond -> formatter.format(converter.format(metersPerSecond).toBigDecimal()).toString() }
    }

    fun altitude(context: Context, meters: Double): String {
        return altitudeFormatter(context)(meters)
    }

    internal fun altitudeFormatter(context: Context): (Double) -> String {
        val systemLocale = context.getSystemService(LocaleManager::class.java)?.systemLocales?.get(0)
            ?: Resources.getSystem().configuration.locales[0]
        return altitudeFormatter(systemLocale, context.resources.configuration.locales[0])
    }

    internal fun altitude(meters: Double, systemLocale: Locale, displayLocale: Locale): String =
        altitudeFormatter(systemLocale, displayLocale)(meters)

    private fun altitudeFormatter(systemLocale: Locale, displayLocale: Locale): (Double) -> String {
        // Ask ICU for the system's base length unit, including measurement overrides. Keep the
        // entire profile in meters or feet instead of switching units as its altitude changes.
        val unit = NumberFormatter.withLocale(ULocale.forLocale(systemLocale))
            .unit(MeasureUnit.METER).usage("default").format(1).outputUnit
        val feet = unit == MeasureUnit.FOOT
        val formatter = NumberFormatter.withLocale(ULocale.forLocale(displayLocale))
            .unit(if (feet) MeasureUnit.FOOT else MeasureUnit.METER)
            .unitWidth(NumberFormatter.UnitWidth.SHORT)
            .precision(Precision.integer())
        return { meters -> formatter.format(if (feet) meters / 0.3048 else meters).toString() }
    }

    fun duration(context: Context, startMs: Long, endMs: Long): String {
        val minutes = ((endMs - startMs) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> context.getString(R.string.duration_under_minute)
            minutes < 60 -> context.getString(R.string.duration_minutes, minutes.toInt())
            else -> context.getString(
                R.string.duration_hours_minutes,
                (minutes / 60).toInt(),
                (minutes % 60).toInt(),
            )
        }
    }

    fun distance(context: Context, meters: Double): String =
        if (meters < 1000) context.getString(R.string.distance_meters, meters.toInt())
        else context.getString(R.string.distance_kilometers, meters / 1000.0)

    fun confidencePct(context: Context, confidence: Float): String =
        context.getString(R.string.confidence_percent, (confidence * 100).toInt())

    fun transportIcon(mode: TransportMode): ImageVector = when (mode) {
        TransportMode.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
        TransportMode.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
        TransportMode.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
        TransportMode.CAR -> Icons.Filled.DirectionsCar
        TransportMode.BUS -> Icons.Filled.DirectionsBus
        TransportMode.RAIL -> Icons.Filled.Train
        TransportMode.FERRY -> Icons.Filled.DirectionsBoat
        TransportMode.FLIGHT -> Icons.Filled.Flight
        TransportMode.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
    }
}
