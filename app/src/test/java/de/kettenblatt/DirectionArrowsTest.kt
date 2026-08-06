package de.kettenblatt

import de.kettenblatt.data.BundleReader
import de.kettenblatt.data.Route
import de.kettenblatt.data.RouteMath
import de.kettenblatt.data.TrackPoint
import de.kettenblatt.geo.Geo
import de.kettenblatt.map.DirectionArrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arrowheads showing which way round a route is ridden.
 *
 * The case that matters is the loop: its start and finish sit within a few
 * metres of each other, so nothing else on the map distinguishes forwards from
 * reverse.
 */
class DirectionArrowsTest {

    private fun venlo(): Route =
        BundleReader.parse(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("venlo.navi.json"))
                .bufferedReader().readText()
        )

    /** A straight west-to-east line, 10 m between points. */
    private fun straightRoute(n: Int = 300): Route {
        val points = (0 until n).map { TrackPoint(51.0, 6.0 + it * 0.000143, 10.0) }
        val cum = RouteMath.cumulativeDistances(points)
        return Route("straight", null, points, cum, DoubleArray(points.size))
    }

    @Test
    fun `arrows are spread along the route, clear of both ends`() {
        val route = venlo()
        val arrows = DirectionArrows.along(route)

        assertTrue("no arrows", arrows.isNotEmpty())
        assertTrue("too many for one line", arrows.size <= DirectionArrows.MAX_ARROWS)

        val cum = route.cumDistM
        val first = cum[arrows.first().index]
        val last = cum[arrows.last().index]
        // Endpoints carry their own markers; an arrow on top of them is clutter.
        assertTrue("first arrow at $first m crowds the start", first > 200)
        assertTrue("last arrow crowds the finish", route.distanceM - last > 200)

        // Evenly spaced, so no stretch of the route is left without one. An
        // arrow can only land on an actual track point, and Komoot decimates --
        // this route has gaps of up to 517 m -- so the spacing can be out by up
        // to one such gap without anything being wrong.
        val pointGap = cum.toList().zipWithNext { a, b -> b - a }.max()
        val gaps = arrows.map { cum[it.index] }.zipWithNext { a, b -> b - a }
        val mean = gaps.average()
        gaps.forEach { assertEquals("uneven spacing", mean, it, pointGap + mean * 0.05) }
    }

    @Test
    fun `arrow count scales with route length`() {
        val route = venlo()
        val long = DirectionArrows.along(route, spacingM = 1_000.0).size
        val sparse = DirectionArrows.along(route, spacingM = 10_000.0).size

        assertTrue("closer spacing should give more arrows", long > sparse)
        assertTrue(long <= DirectionArrows.MAX_ARROWS)
        // A short route still gets enough to read, rather than one lonely arrow.
        assertEquals(DirectionArrows.MIN_ARROWS, sparse)
    }

    @Test
    fun `arrows point along the direction of travel`() {
        // Due east, so every arrow should read 90 degrees.
        val arrows = DirectionArrows.along(straightRoute())
        assertTrue(arrows.isNotEmpty())
        arrows.forEach { assertEquals(90.0, it.bearingDeg, 1.0) }
    }

    @Test
    fun `reversing a straight route exactly reverses its arrows`() {
        val forward = DirectionArrows.along(straightRoute())
        val backward = DirectionArrows.along(straightRoute().reversed())

        assertEquals(forward.size, backward.size)
        forward.zip(backward).forEach { (f, b) ->
            assertEquals(180.0, Geo.bearingDelta(f.bearingDeg, b.bearingDeg), 1.0)
        }
    }

    @Test
    fun `reversing the real loop turns every arrow around`() {
        // The whole point: on a loop this is the only thing that changes on the
        // map. Bearings are sampled 60 m ahead, so on a bend the two directions
        // are not exactly opposed -- what matters is that none of them still
        // points the way it did.
        val route = venlo()
        val backward = DirectionArrows.along(route.reversed())
        assertTrue(backward.isNotEmpty())

        // Arrows are nudged onto straight stretches independently in each
        // direction, so they do not land on mirrored indices. What has to hold
        // is the thing the rider sees: at a given place on the ground, the arrow
        // now points the other way.
        val last = route.points.lastIndex
        backward.forEach { b ->
            val samePlace = last - b.index
            val forwardHere = requireNotNull(DirectionArrows.bearingAt(route, samePlace))
            val turned = Geo.bearingDelta(forwardHere, b.bearingDeg)
            assertTrue(
                "arrow at point $samePlace still points the old way (${turned.toInt()} degrees)",
                turned > 90.0,
            )
        }
    }

    @Test
    fun `bearing is sampled over a window, not from one segment`() {
        // A single kinked segment must not spin the arrow: the reference route
        // averages 29 m per point, and one of them can sit across a junction.
        val points = (0 until 60).map { i ->
            val jitter = if (i == 30) 0.0004 else 0.0    // one point thrown north
            TrackPoint(51.0 + jitter, 6.0 + i * 0.000143, 10.0)
        }
        val cum = RouteMath.cumulativeDistances(points)
        val route = Route("kinked", null, points, cum, DoubleArray(points.size))

        val bearing = requireNotNull(DirectionArrows.bearingAt(route, 29))
        assertEquals("the kink dragged the arrow off course", 90.0, bearing, 20.0)
    }

    @Test
    fun `the last point looks backwards rather than giving up`() {
        val route = straightRoute()
        val atEnd = requireNotNull(DirectionArrows.bearingAt(route, route.points.lastIndex))
        // Still east: looking back must not flip the sense of direction.
        assertEquals(90.0, atEnd, 1.0)
    }

    @Test
    fun `an arrow slides off a bend onto a straight stretch`() {
        // A quarter-circle bend with straight road either side. An arrow aimed at
        // the middle of the bend should walk out of it.
        val points = ArrayList<TrackPoint>()
        repeat(40) { points.add(TrackPoint(51.0, 6.0 + it * 0.000143, 10.0)) }
        repeat(20) { i ->
            val a = Math.toRadians(i * 4.5)
            points.add(TrackPoint(51.0 + 0.0006 * (1 - Math.cos(a)), 6.00558 + 0.0009 * Math.sin(a), 10.0))
        }
        repeat(40) { points.add(TrackPoint(51.0012 + it * 0.000143, 6.0065, 10.0)) }
        val cum = RouteMath.cumulativeDistances(points)
        val route = Route("bend", null, points, cum, DoubleArray(points.size))

        val onBend = 50
        val moved = DirectionArrows.straightestNear(route, onBend, reachM = 300.0)

        assertTrue("landed at $moved (bend is 40..59), cum ${cum[moved].toInt()} m", moved !in 41..59)
        // And it did not wander further than it was allowed to.
        assertTrue(kotlin.math.abs(cum[moved] - cum[onBend]) <= 300.0)
    }

    @Test
    fun `on a straight route an arrow does not wander from its spacing`() {
        val route = straightRoute()
        // Everything is equally straight, so it should stay where it was put.
        assertEquals(120, DirectionArrows.straightestNear(route, 120, reachM = 200.0))
    }

    @Test
    fun `a degenerate route produces no arrows rather than crashing`() {
        val single = listOf(TrackPoint(51.0, 6.0, 0.0), TrackPoint(51.0, 6.0, 0.0))
        val route = Route("nowhere", null, single, DoubleArray(2), DoubleArray(2))

        assertTrue(DirectionArrows.along(route).isEmpty())
        assertNull(DirectionArrows.bearingAt(route, 0))
        assertNull(DirectionArrows.bearingAt(route, 99))
    }
}
