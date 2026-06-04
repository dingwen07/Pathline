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
import androidx.compose.ui.graphics.vector.ImageVector
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.TransportMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Small UI formatting helpers shared by the timeline and map screens. */
object Format {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().format(timeFormatter)

    fun date(dayEpoch: Long): String = LocalDate.ofEpochDay(dayEpoch).format(dateFormatter)

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
