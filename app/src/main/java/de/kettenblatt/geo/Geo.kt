package de.kettenblatt.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Geodesic helpers. Distances are metres, bearings degrees clockwise from north. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0

    // Metres per degree, for the local-plane projection. Accurate to well under
    // a metre at the scale of a single track segment, which is all it is used for.
    const val M_PER_DEG_LAT = 110_540.0
    const val M_PER_DEG_LON_EQUATOR = 111_320.0

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest absolute angle between two bearings, 0..180. */
    fun bearingDelta(b1: Double, b2: Double): Double {
        val d = abs(b1 - b2) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}

/** A track point's index and how far the search point was from it. */
data class NearestPoint(val index: Int, val distanceM: Double)

/**
 * Index of the point nearest to (lat, lon), searched over `[start, end)`.
 *
 * Restricting the range is what keeps a route that doubles back on itself from
 * binding a later feature to an earlier, coincident part of the track -- the
 * reference route revisits 47 coordinates, so an unrestricted search regularly
 * picks the wrong leg.
 */
fun <T> nearestPointIndex(
    points: List<T>,
    lat: Double,
    lon: Double,
    start: Int = 0,
    end: Int = points.size,
    latOf: (T) -> Double,
    lonOf: (T) -> Double,
): NearestPoint {
    val from = start.coerceAtLeast(0)
    val to = end.coerceAtMost(points.size)
    require(from < to) { "empty search range [$from, $to)" }

    val plane = LocalPlane(lat, lon)
    var bestIndex = from
    var bestSq = Double.MAX_VALUE
    for (i in from until to) {
        val x = plane.x(lonOf(points[i]))
        val y = plane.y(latOf(points[i]))
        val d2 = x * x + y * y
        if (d2 < bestSq) {
            bestIndex = i
            bestSq = d2
        }
    }
    return NearestPoint(bestIndex, sqrt(bestSq))
}

/**
 * Equirectangular projection around a reference point.
 *
 * Turns lat/lon into local metre coordinates so segment maths is plain Euclidean
 * geometry -- far cheaper than a haversine per segment, and exact enough over the
 * few hundred metres any one projection is used for.
 */
class LocalPlane(private val lat0: Double, private val lon0: Double) {
    private val mPerDegLon = Geo.M_PER_DEG_LON_EQUATOR * cos(Math.toRadians(lat0))

    fun x(lon: Double): Double = (lon - lon0) * mPerDegLon
    fun y(lat: Double): Double = (lat - lat0) * Geo.M_PER_DEG_LAT

    /** Inverse of [x]/[y], for turning a projected point back into a coordinate. */
    fun lon(x: Double): Double = lon0 + x / mPerDegLon
    fun lat(y: Double): Double = lat0 + y / Geo.M_PER_DEG_LAT
}

/** Where a point falls on a segment: [t] along it, [distanceM] away from it. */
data class Projection(val t: Double, val distanceM: Double)

/**
 * Project (px, py) onto the segment a->b in a plane.
 *
 * Zero-length segments -- which occur wherever a track doubles back on a single
 * point -- return t = 0 and the distance to `a` rather than dividing by zero.
 */
fun projectOntoSegment(
    px: Double, py: Double,
    ax: Double, ay: Double,
    bx: Double, by: Double,
): Projection {
    val dx = bx - ax
    val dy = by - ay
    val segSq = dx * dx + dy * dy
    if (segSq <= 1e-12) return Projection(0.0, hypot(px - ax, py - ay))

    val t = (((px - ax) * dx + (py - ay) * dy) / segSq).coerceIn(0.0, 1.0)
    return Projection(t, hypot(px - (ax + t * dx), py - (ay + t * dy)))
}
