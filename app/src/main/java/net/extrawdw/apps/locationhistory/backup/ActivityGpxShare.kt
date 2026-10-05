package net.extrawdw.apps.locationhistory.backup

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import java.io.File
import java.time.LocalDate

/** A temporary GPX attachment, readable only by recipients chosen in Android's share sheet. */
object ActivityGpxShare {
    /** Disk work: call off the main thread. Null means there is no recorded track to share. */
    fun prepare(context: Context, trip: TripEntity, samples: List<LocationSampleEntity>): Uri? {
        val track = GpxExporter.tripSamples(trip, samples)
        if (track.isEmpty()) return null
        val directory = File(context.cacheDir, "activity-gpx").apply { mkdirs() }
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
        // Unique files keep a second export from changing an attachment a recipient is still reading.
        val file = File.createTempFile("pathline-trip-${LocalDate.ofEpochDay(trip.dayEpoch)}-", ".gpx", directory)
        try {
            file.outputStream().use { GpxExporter.write(track, it) }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun intent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("GPX", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
