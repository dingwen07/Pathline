package net.extrawdw.apps.locationhistory.backup

import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Renders a week's location samples as a GPX 1.1 track. This is a **one-way, lossy interoperability
 * export** — it carries lat/lon/elevation/time (plus speed/course as GPX extensions) but cannot
 * represent the device-state / battery / AR / ML fields or the visit/trip semantics, so it is
 * deliberately not part of the restore path. It is always written unencrypted in an open format.
 */
object GpxExporter {

    /** Write the given samples (already ordered by time) as a single GPX track segment. */
    fun write(samples: List<LocationSampleEntity>, out: OutputStream) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.append(
                "<gpx version=\"1.1\" creator=\"Pathline\" " +
                        "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                        "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                        "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
            )
            w.append("  <trk>\n    <name>Pathline track</name>\n    <trkseg>\n")
            for (s in samples) {
                w.append("      <trkpt lat=\"").append(s.latitude.toString())
                    .append("\" lon=\"").append(s.longitude.toString()).append("\">\n")
                s.altitude?.let {
                    w.append("        <ele>").append(it.toString()).append("</ele>\n")
                }
                w.append("        <time>")
                    .append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(s.timestampMs)))
                    .append("</time>\n")
                val speed = s.speed
                val course = s.bearing
                if (speed != null || course != null) {
                    w.append("        <extensions>\n")
                    speed?.let {
                        w.append("          <speed>").append(it.toString()).append("</speed>\n")
                    }
                    course?.let {
                        w.append("          <course>").append(it.toString()).append("</course>\n")
                    }
                    w.append("        </extensions>\n")
                }
                w.append("      </trkpt>\n")
            }
            w.append("    </trkseg>\n  </trk>\n</gpx>\n")
        }
    }
}
