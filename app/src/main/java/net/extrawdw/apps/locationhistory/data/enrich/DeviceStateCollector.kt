package net.extrawdw.apps.locationhistory.data.enrich

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.core.NetworkTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects the enriched device-state metadata attached to every location sample. Reads are cheap,
 * non-blocking and resilient: anything unavailable or permission-gated returns null rather than
 * throwing, so a missing permission never breaks recording.
 */
@Singleton
class DeviceStateCollector @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Latest Wi-Fi info delivered by the location-aware network callback. The synchronous
     * `getNetworkCapabilities().transportInfo` read is *always* location-redacted on API 31+
     * (BSSID becomes the [REDACTED_BSSID] sentinel, SSID becomes unknown) — the only way to get
     * the real values is a callback registered with [ConnectivityManager.FLAG_INCLUDE_LOCATION_INFO]
     * while holding fine location. Registered lazily on the first snapshot so a missing permission
     * at startup never breaks recording; cleared when the Wi-Fi network is lost.
     */
    @Volatile
    private var callbackWifiInfo: WifiInfo? = null

    @Volatile
    private var wifiCallbackRegistered = false

    /**
     * Listener invoked when the connected Wi-Fi network is lost (after a short debounce), when armed via
     * [armWifiLossDetection]. A Wi-Fi disconnect is a near-free departure hint (you carried the phone
     * out of range / off a hotspot): the recorder routes it to a
     * [net.extrawdw.apps.locationhistory.core.RecorderState.SENSING_DEPARTURE] check. The
     * Wi-Fi-transport-filtered [onLost] below is the clean edge -- the service's default-network callback
     * can't tell "Wi-Fi specifically dropped" from any handoff. Invoked on [wifiHandler]'s looper, so the
     * listener hands off (launches a coroutine) rather than blocking.
     */
    @Volatile
    private var onWifiLost: (() -> Unit)? = null

    /** Debounces Wi-Fi loss so a dual-band/mesh roam (lose-then-immediately-regain, no real movement)
     *  doesn't fire a departure hint: [onLost] schedules [fireWifiLost] after [WIFI_LOSS_DEBOUNCE_MS]
     *  and a reconnect (onCapabilitiesChanged) cancels it. The runnable re-reads [onWifiLost] at fire
     *  time, so a disarm in the meantime is a no-op. */
    private val wifiHandler = Handler(Looper.getMainLooper())
    private val fireWifiLost = Runnable { onWifiLost?.invoke() }

    /** Arm the Wi-Fi-loss departure hint and eagerly register the callback (so a cold STATIONARY start
     *  catches the first disconnect without waiting for a Wi-Fi snapshot). Idempotent. */
    fun armWifiLossDetection(listener: () -> Unit) {
        onWifiLost = listener
        ensureWifiCallbackRegistered()
    }

    /** Disarm the Wi-Fi-loss hint (leaving the network callback registered for snapshot enrichment). */
    fun disarmWifiLossDetection() {
        onWifiLost = null
    }

    private fun ensureWifiCallbackRegistered() {
        if (wifiCallbackRegistered) return
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object :
            ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                callbackWifiInfo = caps.transportInfo as? WifiInfo
                // A (re)connect cancels a pending loss hint -- a dual-band/mesh roam loses then
                // immediately regains a Wi-Fi network without the user moving.
                wifiHandler.removeCallbacks(fireWifiLost)
            }

            override fun onLost(network: Network) {
                callbackWifiInfo = null
                // Debounce: a real departure stays disconnected past the window; a mesh/band-steer flap
                // reconnects within it and cancels this via onCapabilitiesChanged above.
                wifiHandler.removeCallbacks(fireWifiLost)
                wifiHandler.postDelayed(fireWifiLost, WIFI_LOSS_DEBOUNCE_MS)
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback, wifiHandler) }
            .onSuccess { wifiCallbackRegistered = true }
    }

    fun snapshot(): DeviceContext {
        val battery = readBattery()
        val (transport, typeName) = readNetwork()
        val wifi = if (transport == NetworkTransport.WIFI) readWifi() else (null to null)
        val (cellDbm, hasService) = readCellular()
        return DeviceContext(
            batteryPct = battery?.first,
            isCharging = battery?.second,
            networkTransport = transport,
            networkTypeName = typeName,
            cellSignalDbm = cellDbm,
            hasCellService = hasService,
            wifiSsid = wifi.first,
            wifiBssid = wifi.second,
            screenOn = readScreenOn(),
        )
    }

    /** @return battery percent (0..100) to isCharging, or null if unreadable. */
    private fun readBattery(): Pair<Int, Boolean>? = runCatching {
        val intent: Intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else return null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        pct to charging
    }.getOrNull()

    private fun readNetwork(): Pair<NetworkTransport?, String?> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return null to null
        val network = cm.activeNetwork ?: return NetworkTransport.NONE to "none"
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkTransport.NONE to "none"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }
        transport to transport.name.lowercase()
    }.getOrDefault(null to null)

    /**
     * @return SSID to BSSID, best-effort, from the location-aware callback (see
     * [callbackWifiInfo]). The first snapshot after registration can race the callback delivery
     * and miss — the next location batch then carries it. The redaction sentinel is filtered so a
     * still-redacted value is stored as null, never as `02:00:...`.
     */
    private fun readWifi(): Pair<String?, String?> = runCatching {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null to null
        ensureWifiCallbackRegistered()
        val info = callbackWifiInfo ?: return null to null
        val ssid =
            info.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
        val bssid = info.bssid?.takeIf { it != REDACTED_BSSID }
        ssid to bssid
    }.getOrDefault(null to null)

    /** @return strongest cell signal dBm to whether the device has any cellular service. */
    private fun readCellular(): Pair<Int?, Boolean?> = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null to null
        val signal =
            tm.signalStrength ?: return null to (tm.simState == TelephonyManager.SIM_STATE_READY)
        val dbm =
            signal.cellSignalStrengths.mapNotNull { it.dbm.takeIf { d -> d != Int.MAX_VALUE } }
                .maxOrNull()
        val hasService = dbm != null && dbm > -120
        dbm to hasService
    }.getOrDefault(null to null)

    private fun readScreenOn(): Boolean? = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive
    }.getOrNull()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** The BSSID placeholder Android substitutes when Wi-Fi info is location-redacted. */
        const val REDACTED_BSSID = "02:00:00:00:00:00"

        /** Wi-Fi-loss debounce: a mesh/band-steer roam reconnects within this; a real departure doesn't. */
        private const val WIFI_LOSS_DEBOUNCE_MS = 12_000L
    }
}
