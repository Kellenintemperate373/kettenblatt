package de.kettenblatt.map

import de.kettenblatt.data.Route
import de.kettenblatt.geo.Geo
import kotlin.math.abs
import kotlin.math.roundToInt

/** One arrowhead on the line: which point it sits on, and where it points. */
data class DirectionArrow(val index: Int, val bearingDeg: Double)

/**
 * Arrowheads along the route, showing which way round it is ridden.
 *
 * A loop is the case that needs this: start and finish sit almost on top of each
 * other, so two dots say nothing about direction, and choosing forwards or
 * reverse in the preview changed nothing you could see on the map.
 *
 * Deliberately free of Android imports, so the spacing and bearings are testable
 * on the JVM rather than by eye on a map.
 */
object DirectionArrows {

    /** Roughly one arrow per this much route, before the count is clamped. */
    const val SPACING_M = 2_500.0
    const val MIN_ARROWS = 6
    const val MAX_ARROWS = 20

    /**
     * Bearings are taken over this much route rather than from one segment.
     *
     * Segments average 29 m on the reference route and a single one can sit at
     * an odd angle across a junction; sampling further ahead points the arrow
     * along the road rather than along one noisy step.
     */
    const val BEARING_WINDOW_M = 60.0

    fun along(route: Route, spacingM: Double = SPACING_M): List<DirectionArrow> {
        val cum = route.cumDistM
        val total = route.distanceM
        if (route.points.size < 2 || total <= 0.0) return emptyList()

        val count = (total / spacingM).roundToInt().coerceIn(MIN_ARROWS, MAX_ARROWS)

        // Half-step offsets keep arrows off both endpoints, which carry their
        // own markers and would otherwise be crowded.
        return (0 until count).mapNotNull { i ->
            val at = total * (i + 0.5) / count
            val index = straightestNear(route, indexAt(cum, at), spacingM * NUDGE_FRACTION)
            bearingAt(route, index)?.let { DirectionArrow(index, it) }
        }.distinctBy { it.index }
    }

    /** How far an arrow may slide from its ideal spot to find a straight stretch. */
    const val NUDGE_FRACTION = 0.12

    /**
     * The straightest point within [reachM] of [index].
     *
     * An arrow that lands on a bend points along the road but not along the
     * pixels either side of it, which reads as a mistake. Sliding it a little way
     * to a straight stretch costs nothing -- the spacing is arbitrary anyway --
     * and makes every arrow look deliberate.
     */
    fun straightestNear(route: Route, index: Int, reachM: Double): Int {
        val cum = route.cumDistM
        if (index !in route.points.indices) return index

        var lo = index
        while (lo > 0 && cum[index] - cum[lo - 1] <= reachM) lo--
        var hi = index
        while (hi < route.points.lastIndex && cum[hi + 1] - cum[index] <= reachM) hi++

        var best = index
        var bestBend = Double.MAX_VALUE
        for (i in lo..hi) {
            val bend = curvatureAt(route, i) ?: continue
            // Ties go to the point nearest the ideal spacing, so a long straight
            // does not drag every arrow to one end of itself.
            if (bend < bestBend - 1.0 || (bend < bestBend + 1.0 && abs(i - index) < abs(best - index))) {
                best = i
                bestBend = minOf(bend, bestBend)
            }
        }
        return best
    }

    /**
     * How much the line turns at [index], in degrees.
     *
     * Compares the bearing arriving with the bearing leaving. Looking only ahead
     * is not enough: the exit of a bend has perfectly straight road in front of
     * it and would score as straight, which is exactly where an arrow looks
     * wrong.
     */
    fun curvatureAt(route: Route, index: Int, windowM: Double = NEAR_WINDOW_M): Double? {
        val points = route.points
        val cum = route.cumDistM
        if (index !in points.indices) return null

        var behind = index
        while (behind > 0 && cum[index] - cum[behind] < windowM) behind--
        var ahead = index
        while (ahead < points.lastIndex && cum[ahead] - cum[index] < windowM) ahead++
        if (behind == index || ahead == index) return null

        val arriving = Geo.bearing(
            points[behind].lat, points[behind].lon, points[index].lat, points[index].lon,
        )
        val leaving = Geo.bearing(
            points[index].lat, points[index].lon, points[ahead].lat, points[ahead].lon,
        )
        return Geo.bearingDelta(arriving, leaving)
    }

    private const val NEAR_WINDOW_M = 25.0

    /** First point at or past [distanceM] along the route. */
    private fun indexAt(cum: DoubleArray, distanceM: Double): Int {
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < distanceM) lo = mid + 1 else hi = mid
        }
        return lo.coerceIn(0, cum.size - 1)
    }

    /** Direction of travel at [index], sampled forwards over [BEARING_WINDOW_M]. */
    fun bearingAt(route: Route, index: Int, windowM: Double = BEARING_WINDOW_M): Double? {
        val points = route.points
        val cum = route.cumDistM
        if (index !in points.indices) return null

        val target = cum[index] + windowM
        var ahead = index
        while (ahead < points.lastIndex && cum[ahead] < target) ahead++

        // At the very end there is nothing ahead to aim at, so look back instead
        // and keep the same sense of direction.
        val (from, to) = if (ahead > index) index to ahead else {
            var behind = index
            while (behind > 0 && cum[index] - cum[behind] < windowM) behind--
            if (behind == index) return null
            behind to index
        }

        val a = points[from]
        val b = points[to]
        if (a.lat == b.lat && a.lon == b.lon) return null
        return Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    }
}
