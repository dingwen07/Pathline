package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import java.io.File
import java.util.zip.GZIPOutputStream

/** A compact, serializable projection of a sample for export. */
@Serializable
data class ExportedSample(
    val t: Long, val lat: Double, val lon: Double,
    val alt: Double?, val acc: Float?, val spd: Float?, val brg: Float?,
    val prov: String?, val mock: Boolean,
    val bat: Int?, val chg: Boolean?, val net: String?, val cell: Int?, val svc: Boolean?,
    val ssid: String?, val bssid: String?, val scr: Boolean?,
    val ar: String?, val state: String, val stateConf: Float,
    val incl: Boolean,
)

/**
 * Writes raw samples to **per-month, gzipped partitions** in the app files dir. The Room database
 * itself is excluded from cloud auto-backup (it grows unbounded across years); these immutable
 * monthly partitions let incremental cloud backup re-upload only the months that changed — keeping
 * backups efficient over many years. Always enqueued with a charging constraint.
 */
@HiltWorker
class ExportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val locationRepository: LocationRepository,
) : CoroutineWorker(appContext, params) {

    private val json = Json { encodeDefaults = true }

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_DAYS * 86_400_000L
        val samples = locationRepository.range(windowStart, now + 1)
        if (samples.isEmpty()) return Result.success()

        val dir = File(applicationContext.filesDir, Constants.EXPORT_DIR).apply { mkdirs() }
        samples.groupBy { TimeBuckets.monthKey(it.timestampMs) }.forEach { (month, monthSamples) ->
            val file = File(dir, "$month.jsonl.gz")
            runCatching {
                GZIPOutputStream(file.outputStream().buffered()).bufferedWriter().use { writer ->
                    for (s in monthSamples) {
                        writer.appendLine(json.encodeToString(ExportedSample.serializer(), s.toExport()))
                    }
                }
            }
        }
        return Result.success()
    }

    private fun LocationSampleEntity.toExport() = ExportedSample(
        t = timestampMs, lat = latitude, lon = longitude,
        alt = altitude, acc = accuracy, spd = speed, brg = bearing,
        prov = provider, mock = isMock,
        bat = batteryPct, chg = isCharging, net = networkTransport?.name, cell = cellSignalDbm, svc = hasCellService,
        ssid = wifiSsid, bssid = wifiBssid, scr = screenOn,
        ar = arActivity, state = devicePhysicalState.name, stateConf = devicePhysicalStateConfidence,
        incl = includedInComputation,
    )

    private companion object {
        const val WINDOW_DAYS = 62L
    }
}
