package de.kettenblatt.data

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a recorded ride out as GPX.
 *
 * Deliberately the same shape `GpxImport` reads, so an exported ride can be
 * imported straight back into the app -- which is also how the round trip is
 * tested. Everything else that consumes GPX (Komoot, Strava, Garmin) wants a
 * `<trk>` of timestamped `<trkpt>`, which is exactly this.
 */
object GpxExport {

    fun write(ride: Ride, out: OutputStream) {
        // GPX timestamps are UTC by specification; a local time here silently
        // shifts every ride by the offset when another tool reads it.
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        out.bufferedWriter().use { w ->
            w.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            w.appendLine(
                """<gpx version="1.1" creator="Kettenblatt" """ +
                    """xmlns="http://www.topografix.com/GPX/1/1">"""
            )
            w.appendLine("  <metadata>")
            w.appendLine("    <name>${escape(ride.routeName)}</name>")
            w.appendLine("    <time>${stamp.format(Date(ride.startedAtMs))}</time>")
            w.appendLine("  </metadata>")
            w.appendLine("  <trk>")
            w.appendLine("    <name>${escape(ride.routeName)}</name>")
            w.appendLine("    <trkseg>")
            ride.trail.forEach { p ->
                w.append("""      <trkpt lat="${"%.6f".format(Locale.US, p.lat)}" """)
                w.append("""lon="${"%.6f".format(Locale.US, p.lon)}">""")
                p.ele?.let { w.append("<ele>${"%.1f".format(Locale.US, it)}</ele>") }
                w.append("<time>${stamp.format(Date(p.timeMs))}</time>")
                w.appendLine("</trkpt>")
            }
            w.appendLine("    </trkseg>")
            w.appendLine("  </trk>")
            w.appendLine("</gpx>")
        }
    }

    /** A route name can contain anything; ampersands and angle brackets break XML. */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** A filename that sorts by date and survives any filesystem. */
    fun suggestedFileName(ride: Ride): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ride.startedAtMs))
        val name = ride.routeName
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            // Stripping "Venlo / Maas" leaves a double space; collapse it.
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "ride" }
        return "$day $name.gpx"
    }
}
