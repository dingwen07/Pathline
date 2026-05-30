package net.extrawdw.apps.locationhistory.data.enrich

import net.extrawdw.apps.locationhistory.core.NetworkTransport

/**
 * A cheap, point-in-time snapshot of device state captured alongside each location sample.
 * All fields are nullable because the underlying source may be unavailable or permission-gated.
 */
data class DeviceContext(
    val batteryPct: Int?,
    val isCharging: Boolean?,
    val networkTransport: NetworkTransport?,
    val networkTypeName: String?,
    val cellSignalDbm: Int?,
    val hasCellService: Boolean?,
    val wifiSsid: String?,
    val wifiBssid: String?,
    val screenOn: Boolean?,
)
