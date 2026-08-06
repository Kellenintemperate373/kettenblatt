package de.kettenblatt.data

import android.util.Xml
import de.kettenblatt.geo.Geo
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Reads a plain GPX into the same [Route] the preprocessed bundle produces.
 *
 * This is the degraded path: geometry, elevation and waypoints, but no turn
 * cues or street names, because Komoot's export contains none -- deriving them
 * from geometry alone would fire an alert at every bend in the road. Run the
 * route through Add turn cues to get guidance.
 *
 * Tags are matched on local name, so GPX 1.0, 1.1 and any namespace prefix all
 * parse without special-casing.
 */
object GpxImport {

    // Consecutive points closer than this collapse; zero-length segments make
    // bearings undefined.
    private const val MIN_SEPARATION_M = 0.1

    /**
     * [parser] is injectable only so the JVM tests can supply a real one:
     * `android.util.Xml` is a stub outside the device, and the export/import
     * round trip is worth testing without dragging in an emulator.
     */
    fun parse(
        input: InputStream,
        fallbackName: String,
        parser: XmlPullParser = Xml.newPullParser(),
    ): Route {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val trackPoints = ArrayList<TrackPoint>()
        val routePoints = ArrayList<TrackPoint>()
        val waypoints = ArrayList<Waypoint>()

        var metadataName: String? = null
        var trackName: String? = null
        var activity: String? = null

        // Coordinates live on the element, elevation and names in children, so
        // the current element has to be remembered while its children are read.
        var lat = 0.0
        var lon = 0.0
        var ele = 0.0
        var haveEle = false
        var name: String? = null
        var sym: String? = null
        var desc: String? = null
        var context: String? = null
        var inMetadata = false
        var inTrack = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "metadata" -> inMetadata = true
                    "trk" -> inTrack = true
                    "wpt", "trkpt", "rtept" -> {
                        context = tag
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        ele = 0.0
                        haveEle = false
                        name = null
                        sym = null
                        desc = null
                    }
                    "ele" -> parser.nextText().trim().toDoubleOrNull()?.let {
                        ele = it
                        haveEle = true
                    }
                    "name" -> {
                        val text = parser.nextText().trim()
                        when {
                            context != null -> name = text
                            inMetadata -> metadataName = text
                            trackName == null -> trackName = text
                        }
                    }
                    "sym" -> sym = parser.nextText().trim()
                    "desc" -> desc = parser.nextText().trim()
                    // Komoot's sport identifier, but only the one inside <trk>.
                    // A <link> carries a <type> too -- its MIME type -- and
                    // taking the first <type> anywhere read every Komoot export
                    // as "text/html", which sends a hike to bicycle costing and
                    // gives it a cyclist's ETA.
                    "type" -> if (inTrack && context == null && activity == null) {
                        activity = parser.nextText().trim()
                    }
                }

                XmlPullParser.END_TAG -> when (tag) {
                    "metadata" -> inMetadata = false
                    "trk" -> inTrack = false
                    "wpt" -> {
                        waypoints.add(Waypoint(lat, lon, name, sym, desc))
                        context = null
                    }
                    "trkpt" -> {
                        trackPoints.add(TrackPoint(lat, lon, if (haveEle) ele else Double.NaN))
                        context = null
                    }
                    "rtept" -> {
                        routePoints.add(TrackPoint(lat, lon, if (haveEle) ele else Double.NaN))
                        context = null
                    }
                }
            }
            event = parser.next()
        }

        // Prefer the track; fall back to route points for files that only have those.
        val raw = if (trackPoints.size >= 2) trackPoints else routePoints
        val points = dedupe(raw)
        require(points.size >= 2) { "GPX has ${points.size} track points, need at least 2" }

        val cumDist = RouteMath.cumulativeDistances(points)
        val filled = fillMissingElevation(points)
        val smoothed = RouteMath.smoothElevation(filled, cumDist)

        return Route(
            name = metadataName ?: trackName ?: fallbackName,
            activity = activity,
            points = points.mapIndexed { i, p -> p.copy(ele = smoothed[i]) },
            cumDistM = cumDist,
            cumAscentM = RouteMath.cumulativeAscent(smoothed),
            waypoints = waypoints,
        )
    }

    private fun dedupe(points: List<TrackPoint>): List<TrackPoint> {
        if (points.isEmpty()) return points
        val out = ArrayList<TrackPoint>(points.size)
        out.add(points[0])
        for (p in points.drop(1)) {
            val prev = out.last()
            if (Geo.haversine(prev.lat, prev.lon, p.lat, p.lon) >= MIN_SEPARATION_M) out.add(p)
        }
        return out
    }

    /** Carry neighbouring elevations into gaps; a track with none flattens to zero. */
    private fun fillMissingElevation(points: List<TrackPoint>): List<Double> {
        var last = points.firstOrNull { !it.ele.isNaN() }?.ele ?: 0.0
        return points.map {
            if (it.ele.isNaN()) last else { last = it.ele; it.ele }
        }
    }
}
