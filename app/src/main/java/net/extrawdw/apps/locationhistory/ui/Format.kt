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
import androidx.compose.ui.graphics.vector.ImageVector
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

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().format(timeFormatter)

    fun date(dayEpoch: Long): String = LocalDate.ofEpochDay(dayEpoch).format(dateFormatter)

    fun duration(startMs: Long, endMs: Long): String {
        val minutes = ((endMs - startMs) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "<1 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }

    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m"
        else String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)

    fun confidencePct(confidence: Float): String = "${(confidence * 100).toInt()}%"

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
