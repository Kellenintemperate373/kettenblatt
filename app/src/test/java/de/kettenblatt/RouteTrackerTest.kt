package de.kettenblatt

import de.kettenblatt.data.BundleReader
import de.kettenblatt.data.Route
import de.kettenblatt.data.RouteMath
import de.kettenblatt.data.TrackPoint
import de.kettenblatt.data.Waypoint
import de.kettenblatt.geo.Geo
import de.kettenblatt.nav.CoveredSegments
import de.kettenblatt.nav.NavState
import de.kettenblatt.nav.RouteTracker
import de.kettenblatt.ui.ScreenDim
import de.kettenblatt.ui.needsAttention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation engine, exercised against the real Venlo route.
 *
 * That route is a closed loop which revisits 47 coordinates, so it is a genuine
 * test of the snapping logic rather than a synthetic one.
 */
class RouteTrackerTest {

    private fun venlo(): Route =
        BundleReader.parse(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("venlo.navi.json"))
                .bufferedReader().readText()
        )

    /** A straight west-to-east line, 10 m between points. */
    private fun straightRoute(n: Int = 100): Route {
        val points = (0 until n).map { TrackPoint(51.0, 6.0 + it * 0.000143, 10.0) }
        val cum = RouteMath.cumulativeDistances(points)
        return Route("straight", "touring_bicycle", points, cum, DoubleArray(points.size))
    }

    /** Walk the track itself, one fix per point, at a plausible speed. */
    private fun rideAlong(
        route: Route,
        tracker: RouteTracker,
        from: Int = 0,
        to: Int = route.points.size,
        stepMs: Long = 5_000,
        startMs: Long = 1_000_000,
    ): List<NavState> {
        val out = ArrayList<NavState>()
        for (i in from until to) {
            val p = route.points[i]
            val state = tracker.update(p.lat, p.lon, 5.0, 5.0, startMs + (i - from) * stepMs)
            if (state != null) out.add(state)
        }
        return out
    }

    // --- basic progress ---------------------------------------------------

    @Test
    fun `follows a straight route from start to finish`() {
        val route = straightRoute()
        val states = rideAlong(route, RouteTracker(route))

        assertEquals(route.points.size, states.size)
        assertTrue(states.first().distanceAlongM < 1.0)
        assertEquals(route.distanceM, states.last().distanceAlongM, 1.0)
        assertTrue(states.last().finished)
        assertFalse(states.any { it.offRoute })
    }

    @Test
    fun `distance along never goes backwards while riding forwards`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))
        states.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "regressed at index ${b.snappedIndex}: ${a.distanceAlongM} -> ${b.distanceAlongM}",
                b.distanceAlongM >= a.distanceAlongM - 1.0,
            )
        }
    }

    @Test
    fun `reports the full distance of the real route`() {
        val route = venlo()
        assertEquals(28_833.0, route.distanceM, 50.0)
        val states = rideAlong(route, RouteTracker(route))
        assertEquals(route.distanceM, states.last().distanceAlongM, 50.0)
        assertTrue(states.last().progress > 0.99)
    }

    // --- the overlapping-loop problem ------------------------------------

    @Test
    fun `starting a loop does not snap to its coincident end`() {
        // The Venlo route's last point equals its first. A plain nearest-point
        // search could bind the opening fix to the end and report the ride as
        // already finished.
        val route = venlo()
        val first = route.points.first()
        val state = RouteTracker(route).update(first.lat, first.lon, 5.0, 0.0, 0)

        assertNotNull(state)
        assertTrue("snapped to index ${state!!.snappedIndex}", state.snappedIndex < 10)
        assertTrue(state.distanceAlongM < 50.0)
        assertFalse(state.finished)
    }

    @Test
    fun `stays on the correct leg where the route overlaps itself`() {
        val route = venlo()
        val tracker = RouteTracker(route)
        val states = rideAlong(route, tracker)

        // Every fix is taken from the track in order, so the snapped index must
        // climb monotonically -- any jump backwards means it bound to another leg.
        states.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "index jumped ${a.snappedIndex} -> ${b.snappedIndex}",
                b.snappedIndex >= a.snappedIndex,
            )
        }
        assertTrue(states.last().snappedIndex > route.points.size - 5)
    }

    @Test
    fun `an out-and-back spur does not snap onto its own outbound leg`() {
        // The route detours to the Eissalon waypoint and retraces the identical
        // points home: indices 223 and 225 are the same coordinate, with a 180
        // degree turn at 224. Position alone cannot say which leg the rider is
        // on, so only continuity with the previous fix resolves it.
        val route = venlo()
        assertEquals(route.points[223].lat, route.points[225].lat, 1e-9)
        assertEquals(route.points[223].lon, route.points[225].lon, 1e-9)

        val tracker = RouteTracker(route)
        val states = rideAlong(route, tracker, 200, 250)

        states.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "went backwards at the turnaround: ${a.snappedIndex} -> ${b.snappedIndex}",
                b.snappedIndex >= a.snappedIndex,
            )
        }
        // Having ridden through the spur, the tracker must be past it.
        assertTrue(states.last().snappedIndex >= 245)
    }

    @Test
    fun `finish is only reported at the end of a loop not when passing the start`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))
        val firstFinished = states.indexOfFirst { it.finished }
        assertTrue("finished early at fix $firstFinished", firstFinished > states.size - 5)
    }

    // --- off-route hysteresis --------------------------------------------

    private fun offsetFrom(route: Route, index: Int, metres: Double): Pair<Double, Double> {
        // Push perpendicular to the track by moving north; the reference route
        // runs mostly east-west, so this is a genuine cross-track offset.
        val p = route.points[index]
        return (p.lat + metres / Geo.M_PER_DEG_LAT) to p.lon
    }

    @Test
    fun `a brief wobble inside the threshold never triggers`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 10)

        val states = (10 until 20).map { i ->
            val (lat, lon) = offsetFrom(route, i, 20.0)
            tracker.update(lat, lon, 5.0, 5.0, 2_000_000 + i * 5_000L)!!
        }
        assertFalse(states.any { it.offRoute })
    }

    @Test
    fun `going off route requires several consecutive fixes`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 10)

        val states = (10 until 16).map { i ->
            val (lat, lon) = offsetFrom(route, i, 45.0)
            tracker.update(lat, lon, 5.0, 5.0, 2_000_000 + i * 5_000L)!!
        }
        // 45 m is past the 40 m entry threshold but under the 60 m immediate one,
        // so it takes three agreeing fixes.
        assertFalse(states[0].offRoute)
        assertFalse(states[1].offRoute)
        assertTrue(states[2].offRoute)
    }

    @Test
    fun `a large excursion triggers immediately`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 10)

        val (lat, lon) = offsetFrom(route, 10, 120.0)
        val state = tracker.update(lat, lon, 5.0, 5.0, 2_000_000)!!
        assertTrue(state.offRoute)
        assertNotNull(state.bearingToRouteDeg)
        assertEquals(120.0, state.crossTrackM, 5.0)
    }

    @Test
    fun `an excursion and return raises the alarm exactly once`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        val seen = ArrayList<Boolean>()

        rideAlong(route, tracker, 0, 10).forEach { seen.add(it.offRoute) }
        // Leave the route...
        for (i in 10 until 20) {
            val (lat, lon) = offsetFrom(route, i, 100.0)
            seen.add(tracker.update(lat, lon, 5.0, 5.0, 2_000_000 + i * 5_000L)!!.offRoute)
        }
        // ...and come back.
        for (i in 20 until 40) {
            val p = route.points[i]
            seen.add(tracker.update(p.lat, p.lon, 5.0, 5.0, 2_100_000 + i * 5_000L)!!.offRoute)
        }

        val transitions = seen.zipWithNext().count { (a, b) -> a != b }
        assertEquals("alarm flapped: $seen", 2, transitions)
        assertFalse(seen.last())
    }

    @Test
    fun `noisy fixes below the accuracy limit are ignored entirely`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        val before = rideAlong(route, tracker, 0, 10).last()

        // A 200 m error reported with 100 m accuracy must not move anything.
        val (lat, lon) = offsetFrom(route, 10, 200.0)
        val after = tracker.update(lat, lon, 100.0, 5.0, 2_000_000)

        assertEquals(before, after)
        assertFalse(after!!.offRoute)
    }

    @Test
    fun `returning to the route clears the alarm`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 10)
        for (i in 10 until 15) {
            val (lat, lon) = offsetFrom(route, i, 100.0)
            tracker.update(lat, lon, 5.0, 5.0, 2_000_000 + i * 5_000L)
        }
        assertTrue(tracker.state!!.offRoute)

        val recovered = (15 until 20).map {
            val p = route.points[it]
            tracker.update(p.lat, p.lon, 5.0, 5.0, 2_200_000 + it * 5_000L)!!
        }
        assertTrue(recovered.last().offRoute.not())
    }

    // --- direction --------------------------------------------------------

    @Test
    fun `riding the route backwards is detected`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 50, 60)

        val states = (49 downTo 40).map { i ->
            val p = route.points[i]
            tracker.update(p.lat, p.lon, 5.0, 5.0, 3_000_000 + (60 - i) * 5_000L)!!
        }
        assertTrue(states.last().wrongDirection)
    }

    @Test
    fun `riding forwards is never flagged as the wrong direction`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))
        assertFalse(states.any { it.wrongDirection })
    }

    // --- maneuvers --------------------------------------------------------

    @Test
    fun `announces the next maneuver and its distance`() {
        val route = venlo()
        assertTrue("bundle should carry guidance", route.hasGuidance)

        val state = RouteTracker(route).update(
            route.points[0].lat, route.points[0].lon, 5.0, 0.0, 0
        )!!
        val next = state.nextManeuver
        assertNotNull(next)

        assertTrue(next!!.idx > 0)
        assertTrue(next.instruction.isNotEmpty())
        assertTrue(state.distanceToManeuverM!! > 0)
    }

    @Test
    fun `maneuvers are consumed in order as the route is ridden`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))
        val sequence = states.mapNotNull { it.nextManeuver?.idx }.distinct()

        assertEquals(sequence.sorted(), sequence)
        assertTrue(sequence.size > 50)
        // The last stretch has no maneuver left ahead of it.
        assertNull(states.last().nextManeuver)
    }

    @Test
    fun `distance to the next maneuver shrinks as it is approached`() {
        val route = venlo()
        val tracker = RouteTracker(route)
        val states = rideAlong(route, tracker, 0, 40)

        val target = states.first().nextManeuver!!
        val approaching = states.filter { it.nextManeuver?.idx == target.idx }
        approaching.zipWithNext().forEach { (a, b) ->
            assertTrue(b.distanceToManeuverM!! <= a.distanceToManeuverM!! + 1.0)
        }
    }

    @Test
    fun `a route without guidance simply has no maneuvers`() {
        val route = straightRoute()
        val state = RouteTracker(route).update(51.0, 6.0, 5.0, 0.0, 0)!!
        assertFalse(route.hasGuidance)
        assertNull(state.nextManeuver)
        assertNull(state.distanceToManeuverM)
    }

    @Test
    fun `surface is reported where the route is annotated`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))
        val surfaces = states.mapNotNull { it.surface }.toSet()

        assertTrue(surfaces.contains("paved_smooth"))
        assertTrue(surfaces.any { it in setOf("gravel", "dirt", "compacted") })
    }

    // --- dense recorded tracks -------------------------------------------

    /** A 15k-point looping track at ~4 m spacing, like a recorded activity. */
    private fun denseLoopingRoute(n: Int = 15_000): Route {
        val points = (0 until n).map { i ->
            val t = i.toDouble() / n * 2 * Math.PI * 3
            TrackPoint(
                lat = 51.3817 + 0.03 * kotlin.math.sin(t) + i * 1.2e-6,
                lon = 6.2167 + 0.045 * kotlin.math.cos(t) + i * 1.5e-6,
                ele = 30.0,
            )
        }
        val cum = RouteMath.cumulativeDistances(points)
        return Route("dense", "touring_bicycle", points, cum, DoubleArray(n))
    }

    @Test
    fun `tracks a dense recorded track sampled once per second`() {
        // ~4 m between points at 5 m/s means roughly one point per second.
        val route = denseLoopingRoute()
        val tracker = RouteTracker(route)
        val states = rideAlong(route, tracker, 0, 3000, stepMs = 1_000)

        assertFalse("went off route on its own track", states.any { it.offRoute })
        assertTrue(states.last().distanceAlongM > states.first().distanceAlongM)
    }

    @Test
    fun `keeps up when fixes arrive further apart than the search window`() {
        // Sampling every 320th point jumps ~1.3 km per fix -- past both the
        // 300-point search window and the plausible-jump limit, so this exercises
        // the full-scan fallback rather than the windowed path.
        val route = denseLoopingRoute()
        val tracker = RouteTracker(route)

        val states = (0 until 6000 step 320).mapNotNull { i ->
            val p = route.points[i]
            tracker.update(p.lat, p.lon, 5.0, 5.0, 1_000_000L + i * 200L)
        }

        assertTrue(states.size > 10)
        states.forEach {
            assertTrue("cross-track ${it.crossTrackM} m while sitting on the track", it.crossTrackM < 20.0)
        }
        assertFalse(states.any { it.offRoute })
    }

    // --- heading (course-up map) -----------------------------------------

    @Test
    fun `route bearing points along the direction of travel`() {
        // straightRoute runs due east.
        val route = straightRoute()
        val state = RouteTracker(route).update(51.0, 6.0, 5.0, 5.0, 0)!!
        assertEquals(90.0, state.routeBearingDeg!!, 2.0)
    }

    @Test
    fun `route bearing is available at every point of the real route`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))
        assertTrue(states.all { it.routeBearingDeg != null })
        assertTrue(states.all { it.routeBearingDeg!! in 0.0..360.0 })
    }

    @Test
    fun `route bearing is steadier than per-segment bearings`() {
        // Looking ahead is the whole point: with a 29 m median segment length, a
        // per-segment bearing swings enough to make a course-up map judder.
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))

        val lookAheadSwing = states.map { it.routeBearingDeg!! }
            .zipWithNext { a, b -> Geo.bearingDelta(a, b) }
            .average()

        val perSegmentSwing = (1 until route.points.size - 1).map { i ->
            Geo.bearingDelta(
                Geo.bearing(route.points[i - 1].lat, route.points[i - 1].lon,
                            route.points[i].lat, route.points[i].lon),
                Geo.bearing(route.points[i].lat, route.points[i].lon,
                            route.points[i + 1].lat, route.points[i + 1].lon),
            )
        }.average()

        assertTrue(
            "look-ahead $lookAheadSwing should be steadier than per-segment $perSegmentSwing",
            lookAheadSwing < perSegmentSwing,
        )
    }

    @Test
    fun `route bearing survives the end of the route`() {
        // Nothing lies ahead of the last point, so it looks back instead of
        // returning null and letting the map snap back to north.
        val route = straightRoute()
        val last = route.points.last()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker)
        val state = tracker.update(last.lat, last.lon, 5.0, 5.0, 9_000_000)!!
        assertEquals(90.0, state.routeBearingDeg!!, 5.0)
    }

    // --- ETA --------------------------------------------------------------

    @Test
    fun `eta falls as the route is ridden`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route)).filter { it.etaSeconds != null }
        assertTrue(states.first().etaSeconds!! > states.last().etaSeconds!!)
        assertTrue("finished with ${states.last().etaSeconds}s left", states.last().etaSeconds!! < 120)
    }

    /** Ride the track with the provider reporting no speed at all. */
    private fun rideWithoutProviderSpeed(
        route: Route,
        tracker: RouteTracker,
        to: Int = route.points.size,
        stepMs: Long = 5_000,
    ): List<NavState> = (0 until to).mapNotNull { i ->
        val p = route.points[i]
        tracker.update(p.lat, p.lon, 5.0, null, 1_000_000L + i * stepMs)
    }

    @Test
    fun `speed is derived when the provider reports none`() {
        // Plenty of hardware never reports Doppler speed, and no fix has it at
        // the start of a ride. Reading that as "stationary" left the speed
        // window empty for the whole ride.
        val route = venlo()
        val states = rideWithoutProviderSpeed(route, RouteTracker(route), to = 120)

        val speeds = states.mapNotNull { it.speedMps }
        assertTrue("no speed was ever derived", speeds.isNotEmpty())
        // 5 s between points on this route is a plausible riding pace.
        assertTrue("implausible derived speed ${speeds.last()}", speeds.last() in 0.5..25.0)
    }

    @Test
    fun `speed is derived when the provider reports an implausible near-zero`() {
        // Not hypothetical: the emulator's fused provider reports 0.18 m/s at
        // any pace, and some handsets do the same. A provider that says "barely
        // moving" while the rider covers a kilometre must not be believed.
        val route = venlo()
        val tracker = RouteTracker(route)
        val states = (0 until 120).mapNotNull { i ->
            val p = route.points[i]
            tracker.update(p.lat, p.lon, 5.0, 0.18, 1_000_000L + i * 5_000L)
        }

        val speeds = states.mapNotNull { it.speedMps }
        assertTrue("no speed was derived past the provider's 0.18", speeds.isNotEmpty())
        assertTrue("implausible derived speed ${speeds.last()}", speeds.last() in 0.5..25.0)
    }

    @Test
    fun `a stationary rider is not moving just because the fix wanders`() {
        // GPS jitter while parked: a couple of metres between fixes, every
        // second. Judging movement fix-to-fix would read that as walking pace.
        val route = straightRoute()
        val tracker = RouteTracker(route)
        var t = 1_000_000L
        repeat(60) { i ->
            // Alternate either side of the same point, ~2 m of wander.
            val lon = 6.0 + 0.0000286 * (i % 2)
            tracker.update(51.0, lon, 5.0, null, t)
            t += 1_000
        }

        assertNull("jitter was mistaken for riding", tracker.state!!.speedMps)
    }

    @Test
    fun `eta adapts to real pace without a provider speed`() {
        val route = venlo()
        val tracker = RouteTracker(route)
        // Deliberately slow: 20 s per point is far off the 16 km/h default, so an
        // ETA still sitting on the default is easy to spot.
        val states = rideWithoutProviderSpeed(route, tracker, to = 120, stepMs = 20_000)

        val last = states.last()
        val onDefault = last.distanceRemainingM / RouteTracker.DEFAULT_CYCLING_MPS
        assertNotNull(last.etaSeconds)
        assertTrue(
            "ETA ${last.etaSeconds} still matches the hardcoded default ${onDefault.toLong()}",
            last.etaSeconds!! > onDefault * 1.5,
        )
    }

    @Test
    fun `a rest stop is still excluded when speed is derived`() {
        val route = venlo()
        val tracker = RouteTracker(route)
        rideWithoutProviderSpeed(route, tracker, to = 60)
        val moving = tracker.state!!.etaSeconds!!

        // Twenty minutes parked, with no provider speed to say so.
        val p = route.points[59]
        var t = 5_000_000L
        repeat(120) {
            t += 10_000
            tracker.update(p.lat, p.lon, 5.0, null, t)
        }

        assertEquals(
            "standing still leaked into the average",
            moving.toDouble(), tracker.state!!.etaSeconds!!.toDouble(), 120.0,
        )
    }

    @Test
    fun `eta uses an activity default before any speed is known`() {
        val route = straightRoute()
        val state = RouteTracker(route).update(51.0, 6.0, 5.0, null, 0)!!
        assertNotNull(state.etaSeconds)
        // 99 segments of 10 m is ~990 m; at the 16 km/h cycling default that is
        // a little under four minutes.
        assertEquals(990.0 / RouteTracker.DEFAULT_CYCLING_MPS, state.etaSeconds!!.toDouble(), 15.0)
    }

    @Test
    fun `a rest stop does not wreck the eta`() {
        val route = venlo()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 60)
        val moving = tracker.state!!.etaSeconds!!

        // Twenty minutes stationary at the same point, reported as not moving.
        val p = route.points[59]
        var t = 5_000_000L
        repeat(120) {
            t += 10_000
            tracker.update(p.lat, p.lon, 5.0, 0.0, t)
        }

        val afterStop = tracker.state!!.etaSeconds!!
        assertEquals(
            "stationary fixes leaked into the speed average",
            moving.toDouble(), afterStop.toDouble(), 60.0,
        )
    }

    @Test
    fun `eta includes a climbing penalty`() {
        // Two identical-length routes, one flat and one climbing 600 m.
        val n = 100
        val points = (0 until n).map { TrackPoint(51.0, 6.0 + it * 0.000143, 10.0) }
        val cum = RouteMath.cumulativeDistances(points)

        val flat = Route("flat", "hike", points, cum, DoubleArray(n))
        val climbing = Route(
            "climb", "hike", points, cum,
            DoubleArray(n) { it * 600.0 / (n - 1) },
        )

        val flatEta = RouteTracker(flat).update(51.0, 6.0, 5.0, null, 0)!!.etaSeconds!!
        val climbEta = RouteTracker(climbing).update(51.0, 6.0, 5.0, null, 0)!!.etaSeconds!!

        // Naismith adds an hour for 600 m of ascent.
        assertEquals(3600.0, (climbEta - flatEta).toDouble(), 60.0)
    }

    // --- reversing --------------------------------------------------------

    @Test
    fun `reversing flips the geometry end to end`() {
        val route = venlo()
        val back = route.reversed()

        assertTrue(back.isReversed)
        assertEquals(route.points.size, back.points.size)
        assertEquals(route.points.first(), back.points.last())
        assertEquals(route.points.last(), back.points.first())
        assertEquals(route.distanceM, back.distanceM, 1.0)
    }

    @Test
    fun `reversing twice returns the original`() {
        val route = venlo()
        val there = route.reversed().reversed()

        assertFalse(there.isReversed)
        assertEquals(route.points, there.points)
        assertEquals(route.distanceM, there.distanceM, 0.5)
    }

    @Test
    fun `reversing swaps in separately matched cues`() {
        // Cues cannot be mirrored -- ridden the other way you meet each junction
        // from a different arm -- so prep.py matches both directions and
        // reversing swaps the two sets.
        val route = venlo()
        val back = route.reversed()

        assertTrue(route.hasGuidance)
        assertTrue("reverse cues missing from the bundle", back.hasGuidance)
        assertEquals(route.reverseManeuvers, back.maneuvers)
        assertEquals(route.maneuvers, back.reverseManeuvers)
    }

    @Test
    fun `reverse cues are genuinely different instructions`() {
        val route = venlo()
        val forwardText = route.maneuvers.map { it.instruction }
        val reverseText = route.reverseManeuvers.map { it.instruction }

        assertTrue(reverseText.isNotEmpty())
        // The opening turns differ: from the finish you leave on the streets the
        // forward route arrives on.
        assertNotEquals(forwardText.first(), reverseText.first())
        assertTrue(reverseText.toSet() != forwardText.toSet())
    }

    @Test
    fun `reverse cue indices address the reversed point order`() {
        val route = venlo()
        val back = route.reversed()
        assertTrue(back.maneuvers.all { it.idx in route.points.indices })
        assertEquals(back.maneuvers.map { it.idx }.sorted(), back.maneuvers.map { it.idx })
    }

    @Test
    fun `a bundle without reverse cues still reverses`() {
        // v1 bundles and plain GPX imports have no backward guidance; reversing
        // must still give a navigable route.
        val points = (0 until 20).map { TrackPoint(51.0, 6.0 + it * 0.000143, 0.0) }
        val cum = RouteMath.cumulativeDistances(points)
        val route = Route("plain", null, points, cum, DoubleArray(20))

        val back = route.reversed()
        assertFalse(back.hasGuidance)
        assertTrue(back.distanceM > 0)
    }

    @Test
    fun `a reversed route is still guided while riding it`() {
        val route = venlo().reversed()
        val states = rideAlong(route, RouteTracker(route), 0, 120)
        assertTrue(states.any { it.nextManeuver != null })
    }

    @Test
    fun `reversing remaps surface spans onto the new indices`() {
        val route = venlo()
        val back = route.reversed()

        assertEquals(route.surfaces.size, back.surfaces.size)
        assertTrue(back.surfaces.all { it.from <= it.to })
        assertTrue(back.surfaces.zipWithNext().all { (a, b) -> b.from >= a.from })

        // The surface under the finish line is the surface under the new start.
        assertEquals(route.surfaceAt(route.points.lastIndex), back.surfaceAt(0))
        assertEquals(route.surfaceAt(0), back.surfaceAt(back.points.lastIndex))
    }

    @Test
    fun `reversing recomputes ascent rather than reusing it`() {
        // Climbs one way are descents the other, so the totals are independent.
        val n = 50
        val points = (0 until n).map {
            TrackPoint(51.0, 6.0 + it * 0.000143, if (it < n / 2) 100.0 else 200.0)
        }
        val cum = RouteMath.cumulativeDistances(points)
        val route = Route(
            "hill", "hike", points, cum,
            RouteMath.cumulativeAscent(points.map { it.ele }.toDoubleArray()),
        )

        assertEquals(100.0, route.ascentM, 1.0)
        assertEquals(0.0, route.reversed().ascentM, 1.0)
    }

    @Test
    fun `a reversed route tracks from its new start`() {
        val route = venlo().reversed()
        val states = rideAlong(route, RouteTracker(route))

        assertFalse(states.any { it.offRoute })
        assertTrue(states.first().distanceAlongM < 50.0)
        assertEquals(route.distanceM, states.last().distanceAlongM, 50.0)
        assertFalse(states.any { it.wrongDirection })
    }

    // --- coverage ---------------------------------------------------------

    @Test
    fun `riding the whole route covers all of it`() {
        val route = straightRoute()
        val states = rideAlong(route, RouteTracker(route))
        val covered = states.last().covered

        assertEquals(route.points.size - 1, covered.size)
        assertEquals(covered.size, covered.coveredCount)
        assertEquals(1, covered.runs().size)
    }

    @Test
    fun `coverage grows only where the rider has been`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        val half = rideAlong(route, tracker, 0, 50).last()

        assertTrue(half.covered[10])
        assertFalse("the rest of the route is not ridden yet", half.covered[80])
    }

    @Test
    fun `a skipped stretch stays uncovered on a sparsely sampled route`() {
        // The reference bundle has a point every ~48 m, so a two-kilometre
        // shortcut is only forty points. Judging the gap by point count marked
        // it as ridden; the rider needs to see it stayed bright.
        val route = venlo()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 55)
        val skippedTo = 95
        val end = rideAlong(route, tracker, skippedTo, 120, startMs = 9_000_000).last()

        assertTrue(end.covered[20])
        assertFalse("a 2 km shortcut was marked as ridden", end.covered[75])
        assertEquals("expected two disjoint ridden stretches", 2, end.covered.runs().size)
    }

    @Test
    fun `a gps jump does not become a sprint`() {
        // Coming out of a tunnel the fix can land hundreds of metres along.
        // Averaging across that leaves Speed and ETA nonsense for minutes.
        val route = venlo()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 40)
        val plausible = tracker.state!!.speedMps!!

        val jumped = route.points[120]
        val after = tracker.update(jumped.lat, jumped.lon, 5.0, null, 1_000_000 + 40 * 5_000 + 5_000)!!

        val speed = after.speedMps
        assertTrue(
            "jump reported as ${speed} m/s, having been riding at $plausible",
            speed == null || speed < RouteTracker.MAX_PLAUSIBLE_MPS,
        )
    }

    @Test
    fun `a resumed ride keeps the coverage it was interrupted with`() {
        // What the rider sees after a crash: the stretch already ridden must
        // still be dimmed, and riding on must extend it rather than restart it.
        val route = straightRoute()
        val interrupted = rideAlong(route, RouteTracker(route), 0, 40).last().covered

        val resumed = RouteTracker(route, initialCoverage = interrupted)
        val state = rideAlong(route, resumed, 40, 60, startMs = 9_000_000).last()

        assertTrue("the stretch ridden before the crash was forgotten", state.covered[10])
        assertTrue(state.covered[50])
        assertEquals("resuming should not stripe the line", 1, state.covered.runs().size)
    }

    @Test
    fun `a skipped section stays uncovered`() {
        // Ride the first quarter, jump past the middle, ride the last quarter --
        // the middle must not be claimed as ridden just because it is behind you.
        val route = straightRoute()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 25)
        val end = rideAlong(route, tracker, 75, 100, startMs = 9_000_000).last()

        assertTrue(end.covered[10])
        assertFalse("the skipped middle was marked as ridden", end.covered[50])
        assertTrue(end.covered[80])
        assertEquals("expected two disjoint ridden stretches", 2, end.covered.runs().size)
    }

    @Test
    fun `an off-route detour does not cover the route it bypassed`() {
        val route = straightRoute()
        val tracker = RouteTracker(route)
        rideAlong(route, tracker, 0, 20)

        // Leave the route, travel past several segments, rejoin further on.
        for (i in 20 until 60) {
            val (lat, lon) = offsetFrom(route, i, 150.0)
            tracker.update(lat, lon, 5.0, 5.0, 3_000_000L + i * 1_000L)
        }
        val back = rideAlong(route, tracker, 60, 80, startMs = 5_000_000).last()

        assertTrue(back.covered[10])
        assertFalse("the bypassed stretch was marked as ridden", back.covered[40])
        assertTrue(back.covered[70])
    }

    @Test
    fun `gaps between sparse fixes are filled in`() {
        // At a 4 s interval the rider crosses several segments between fixes; the
        // covered line must be continuous rather than striped.
        val route = straightRoute()
        val tracker = RouteTracker(route)
        for (i in 0 until 60 step 5) {
            val p = route.points[i]
            tracker.update(p.lat, p.lon, 5.0, 5.0, 1_000_000L + i * 800L)
        }
        val covered = tracker.state!!.covered
        assertEquals("coverage should be one continuous run", 1, covered.runs().size)
        assertTrue(covered[3])
    }

    @Test
    fun `coverage survives the overlapping legs of the real loop`() {
        val route = venlo()
        val states = rideAlong(route, RouteTracker(route))
        val covered = states.last().covered
        // A loop ridden once covers essentially all of itself.
        assertTrue(covered.coveredCount > covered.size * 0.95)
    }

    // --- waypoints and surfaces -------------------------------------------

    @Test
    fun `the real route's waypoint is placed on the line`() {
        val route = venlo()
        val wp = route.waypointsAlongRoute.single()

        assertEquals("Eissalon Clevers Grubbenvorst", wp.label)
        // It sits on the out-and-back spur around index 224.
        assertTrue("placed at index ${wp.index}", wp.index in 200..250)
        assertTrue(wp.distanceAlongM > 0 && wp.distanceAlongM < route.distanceM)
    }

    @Test
    fun `a waypoint far from the route is not treated as a stop on it`() {
        val points = (0 until 20).map { TrackPoint(51.0, 6.0 + it * 0.000143, 0.0) }
        val cum = RouteMath.cumulativeDistances(points)
        val faraway = Waypoint(51.02, 6.0, "Somewhere else", null, null)
        val route = Route("t", null, points, cum, DoubleArray(20), waypoints = listOf(faraway))

        assertTrue(route.waypointsAlongRoute.isEmpty())
    }

    @Test
    fun `distance to the waypoint counts down and clears once passed`() {
        val route = venlo()
        val tracker = RouteTracker(route)
        val states = rideAlong(route, tracker, 150, 280)

        val approaching = states.mapNotNull { it.distanceToWaypointM }
        assertTrue(approaching.isNotEmpty())
        assertTrue(approaching.first() > approaching.last())
        // Past the spur there is nothing left ahead.
        assertNull(states.last().nextWaypoint)
    }

    @Test
    fun `the next unpaved stretch is reported with its length`() {
        val route = venlo()
        assertTrue("bundle should carry surfaces", route.unpavedSpans.isNotEmpty())

        val state = RouteTracker(route).update(
            route.points[0].lat, route.points[0].lon, 5.0, 5.0, 0
        )!!
        assertNotNull(state.distanceToUnpavedM)
        assertTrue(state.unpavedLengthM!! > 0)
    }

    @Test
    fun `the unpaved chip stays up while riding through the stretch`() {
        // Once inside a gravel section the distance ahead is zero but the
        // remaining length still matters, so the span must not be skipped.
        val route = venlo()
        val span = route.unpavedSpans.first()
        val tracker = RouteTracker(route)
        val states = rideAlong(route, tracker, 0, span.to + 2)

        val inside = states.filter { it.snappedIndex in span.from..span.to }
        assertTrue(inside.isNotEmpty())
        assertTrue(inside.all { it.distanceToUnpavedM != null })
        assertEquals(0.0, inside.last().distanceToUnpavedM!!, 1.0)
    }

    // --- screen dimming ---------------------------------------------------

    private fun stateWith(
        maneuverAhead: Double?,
        waypointAhead: Double? = null,
        offRoute: Boolean = false,
        wrongDirection: Boolean = false,
        finished: Boolean = false,
    ) = NavState(
        snappedIndex = 0, crossTrackM = 0.0, distanceAlongM = 0.0,
        distanceRemainingM = 1000.0, ascentRemainingM = 0.0, progress = 0.0,
        offRoute = offRoute, bearingToRouteDeg = null, nextManeuver = null,
        distanceToManeuverM = maneuverAhead,
        nextWaypoint = null, distanceToWaypointM = waypointAhead,
        surface = null, distanceToUnpavedM = null, unpavedLengthM = null,
        routeBearingDeg = 90.0,
        speedMps = 5.0, etaSeconds = 60, wrongDirection = wrongDirection, finished = finished,
        covered = CoveredSegments.none(),
    )

    @Test
    fun `screen stays lit when a turn is coming up`() {
        assertTrue(stateWith(maneuverAhead = 120.0).needsAttention())
        assertTrue(stateWith(maneuverAhead = ScreenDim.WAKE_AHEAD_M).needsAttention())
    }

    @Test
    fun `screen may dim on a long stretch with nothing ahead`() {
        assertFalse(stateWith(maneuverAhead = 2_000.0).needsAttention())
        // A route with no cues at all has nothing to wake for either.
        assertFalse(stateWith(maneuverAhead = null).needsAttention())
    }

    @Test
    fun `a route without turn cues must never dim`() {
        // Nothing would ever wake it, and with no guidance the map is the only
        // thing telling the rider where to go. A plain GPX import is this case;
        // a reversed bundle no longer is, since prep.py matches both directions.
        val route = straightRoute()
        assertFalse(route.hasGuidance)

        val state = RouteTracker(route).update(
            route.points[0].lat, route.points[0].lon, 5.0, 5.0, 0
        )!!
        assertNull(state.distanceToManeuverM)
        // needsAttention alone says "may dim"; the screen is what must refuse,
        // which NavigationScreen does by gating on hasGuidance.
        assertFalse(state.needsAttention())
    }

    @Test
    fun `an approaching waypoint also keeps the screen lit`() {
        // Missing the cafe you deliberately routed past is exactly what a dark
        // screen would cause.
        assertTrue(stateWith(maneuverAhead = 2_000.0, waypointAhead = 150.0).needsAttention())
        assertFalse(stateWith(maneuverAhead = 2_000.0, waypointAhead = 2_000.0).needsAttention())
    }

    @Test
    fun `anything gone wrong keeps the screen lit`() {
        assertTrue(stateWith(2_000.0, offRoute = true).needsAttention())
        assertTrue(stateWith(2_000.0, wrongDirection = true).needsAttention())
        assertTrue(stateWith(2_000.0, finished = true).needsAttention())
        // No fix yet is also worth showing.
        assertTrue((null as NavState?).needsAttention())
    }

    // --- degenerate input -------------------------------------------------

    @Test
    fun `a two-point route still tracks`() {
        val points = listOf(TrackPoint(51.0, 6.0, 0.0), TrackPoint(51.0, 6.001, 0.0))
        val route = Route("tiny", null, points, RouteMath.cumulativeDistances(points), DoubleArray(2))
        val state = RouteTracker(route).update(51.0, 6.0005, 5.0, 1.0, 0)
        assertNotNull(state)
        assertTrue(state!!.distanceAlongM > 0)
    }

    @Test
    fun `repeated identical points do not divide by zero`() {
        val points = listOf(
            TrackPoint(51.0, 6.0, 0.0),
            TrackPoint(51.0, 6.0, 0.0),
            TrackPoint(51.0, 6.001, 0.0),
        )
        val route = Route("dupes", null, points, RouteMath.cumulativeDistances(points), DoubleArray(3))
        val state = RouteTracker(route).update(51.0, 6.0, 5.0, 0.0, 0)
        assertNotNull(state)
        assertEquals(0.0, state!!.crossTrackM, 1.0)
    }
}
