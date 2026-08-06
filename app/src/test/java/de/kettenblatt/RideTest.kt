package de.kettenblatt

import de.kettenblatt.data.GpxExport
import de.kettenblatt.data.GpxImport
import de.kettenblatt.data.Ride
import de.kettenblatt.data.RideStore
import de.kettenblatt.data.TrailPoint
import de.kettenblatt.geo.Geo
import de.kettenblatt.nav.CoveredSegments
import de.kettenblatt.nav.RideRecorder
import de.kettenblatt.nav.toRuns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Ride recording: the summary maths, persistence, and the GPX round trip.
 *
 * The round trip is the one that matters most -- an exported ride that no other
 * tool can read is worse than no export at all, and importing it back through
 * the app's own parser is the strictest check available offline.
 */
class RideTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "rides-test-${hashCode()}")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A straight eastward trail, 10 m and 10 s apart, climbing 1 m per point. */
    private fun trail(n: Int = 20, startMs: Long = 1_700_000_000_000): List<TrailPoint> =
        (0 until n).map {
            TrailPoint(
                lat = 51.0,
                lon = 6.0 + it * 0.000143,
                ele = 10.0 + it,
                timeMs = startMs + it * 10_000L,
            )
        }

    private fun ride(
        trail: List<TrailPoint> = trail(),
        endedAtMs: Long? = null,
        coveredRuns: List<List<Int>> = emptyList(),
        routeSegments: Int = 0,
    ) = Ride(
        id = "ride-1",
        routeId = "route-1",
        routeName = "Venlo loop",
        reversed = false,
        startedAtMs = trail.firstOrNull()?.timeMs ?: 1_700_000_000_000,
        endedAtMs = endedAtMs,
        trail = trail,
        coveredRuns = coveredRuns,
        routeSegments = routeSegments,
    )

    // --- summary maths ----------------------------------------------------

    @Test
    fun `distance is the sum of the trail legs`() {
        val r = ride()
        val expected = r.trail.zipWithNext()
            .sumOf { (a, b) -> Geo.haversine(a.lat, a.lon, b.lat, b.lon) }
        assertEquals(expected, r.distanceM, 0.001)
        // 19 legs of roughly 10 m.
        assertEquals(190.0, r.distanceM, 5.0)
    }

    @Test
    fun `a rest stop does not count as moving time`() {
        val riding = trail(10)
        // Twenty minutes standing still at the last position, sampled every minute.
        val resting = (1..20).map {
            riding.last().copy(timeMs = riding.last().timeMs + it * 60_000L)
        }
        val r = ride(riding + resting)

        assertEquals(9 * 10_000L, r.movingMs)
        assertTrue("elapsed should include the stop", r.elapsedMs > r.movingMs)
    }

    @Test
    fun `average speed uses moving time only`() {
        val r = ride(trail(11))
        // 10 legs of 10 m in 10 s each -- 1 m/s.
        assertEquals(1.0, requireNotNull(r.averageSpeedMps), 0.05)
    }

    @Test
    fun `a gps jump does not inflate the ride summary`() {
        // A fix landing two kilometres away in ten seconds is a bad fix. Counted
        // as riding, it reported a 7.7 km outing at 495 km/h.
        val riding = trail(20)
        val jumped = riding + TrailPoint(
            lat = 51.0,
            lon = 6.03,
            ele = 30.0,
            timeMs = riding.last().timeMs + 10_000,
        )
        val r = ride(jumped)

        assertEquals("the jump was counted as distance", ride().distanceM, r.distanceM, 1.0)
        assertEquals("the jump was counted as moving time", ride().movingMs, r.movingMs)
        assertTrue(
            "implausible average ${r.averageSpeedMps}",
            requireNotNull(r.averageSpeedMps) < Ride.MAX_PLAUSIBLE_MPS,
        )
    }

    @Test
    fun `average speed is absent when nothing moved`() {
        val point = TrailPoint(51.0, 6.0, 10.0, 1_700_000_000_000)
        val standing = (0 until 10).map { point.copy(timeMs = point.timeMs + it * 10_000L) }
        assertNull(ride(standing).averageSpeedMps)
    }

    @Test
    fun `ascent is measured from the trail elevations`() {
        // 19 gains of 1 m; smoothing softens the ends but the total should be close.
        assertEquals(19.0, ride().ascentM, 3.0)
    }

    @Test
    fun `a trail without elevation reports no ascent`() {
        assertEquals(0.0, ride(trail().map { it.copy(ele = null) }).ascentM, 0.0)
    }

    @Test
    fun `coverage is the covered share of the route segments`() {
        val r = ride(coveredRuns = listOf(listOf(0, 25), listOf(50, 75)), routeSegments = 200)
        assertEquals(0.25, r.coverage, 1e-9)
    }

    @Test
    fun `coverage is zero when the route length is unknown`() {
        assertEquals(0.0, ride(coveredRuns = listOf(listOf(0, 25))).coverage, 0.0)
    }

    // --- store ------------------------------------------------------------

    @Test
    fun `an unfinished ride is active and stays out of the history list`() {
        val store = RideStore(dir)
        store.save(ride())

        assertNotNull(store.active())
        assertTrue(store.list().isEmpty())

        store.save(ride(endedAtMs = 1_700_000_200_000))
        assertNull(store.active())
        assertEquals(1, store.list().size)
    }

    @Test
    fun `history is newest first`() {
        val store = RideStore(dir)
        store.save(ride(endedAtMs = 1).copy(id = "old", startedAtMs = 1_000))
        store.save(ride(endedAtMs = 1).copy(id = "new", startedAtMs = 9_000))

        assertEquals(listOf("new", "old"), store.list().map { it.id })
    }

    @Test
    fun `an abandoned ride is closed off at its last fix`() {
        val store = RideStore(dir)
        store.save(ride())

        val finished = requireNotNull(store.finaliseAbandoned(nowMs = Long.MAX_VALUE))

        assertEquals(ride().trail.last().timeMs, finished.endedAtMs)
        assertNull(store.active())
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a mis-tap is discarded rather than kept as a ride`() {
        val store = RideStore(dir)
        store.save(ride(trail(3)))

        assertNull(store.finaliseAbandoned(nowMs = 1_700_000_200_000))
        assertTrue(store.list().isEmpty())
        assertNull(store.active())
    }

    @Test
    fun `a corrupt ride file costs only that ride`() {
        val store = RideStore(dir)
        store.save(ride(endedAtMs = 1))
        dir.resolve("broken.json").writeText("{ not json")

        assertEquals(1, store.list().size)
    }

    // --- recorder ---------------------------------------------------------

    @Test
    fun `the recorder flushes periodically and keeps everything on finish`() {
        val store = RideStore(dir)
        val recorder = RideRecorder(store, ride(emptyList()), flushIntervalMs = 30_000)
        val covered = CoveredSegments(BooleanArray(4) { it < 2 })

        trail(20).forEach { recorder.record(it.lat, it.lon, it.ele, it.timeMs, covered) }

        // Flushed mid-ride, so a kill here would not lose the whole thing.
        val partial = requireNotNull(store.active())
        assertTrue(partial.trail.isNotEmpty())
        assertTrue(partial.trail.size < 20)

        val finished = recorder.finish(covered, endedAtMs = 1_700_000_300_000)
        assertEquals(20, finished.trail.size)
        assertEquals(listOf(listOf(0, 2)), finished.coveredRuns)
        assertNull(store.active())
    }

    @Test
    fun `the recorder discards a ride nobody rode`() {
        val store = RideStore(dir)
        val recorder = RideRecorder(store, ride(emptyList()))
        val covered = CoveredSegments.none()

        trail(3).forEach { recorder.record(it.lat, it.lon, it.ele, it.timeMs, covered) }

        assertTrue(recorder.discardIfEmpty())
        assertTrue(store.list().isEmpty())
        assertNull(store.active())
    }

    @Test
    fun `a resumed recorder appends to the trail it was given`() {
        val store = RideStore(dir)
        val interrupted = ride(trail(10), coveredRuns = listOf(listOf(0, 5)))
        val recorder = RideRecorder(store, interrupted, flushIntervalMs = 0)

        assertEquals(listOf(listOf(0, 5)), recorder.restoredRuns)
        recorder.record(51.0, 6.01, 20.0, 1_700_000_500_000, CoveredSegments.none())

        assertEquals(11, requireNotNull(store.active()).trail.size)
    }

    // --- coverage round trip ----------------------------------------------

    @Test
    fun `coverage survives being written out and read back`() {
        val flags = BooleanArray(20) { it in 2..5 || it in 11..12 }
        val runs = CoveredSegments(flags).toRuns()

        val restored = CoveredSegments.fromRuns(runs, flags.size)

        flags.indices.forEach { assertEquals("segment $it", flags[it], restored[it]) }
        assertEquals(flags.count { it }, restored.coveredCount)
    }

    @Test
    fun `coverage from a longer route is clamped rather than crashing`() {
        val restored = CoveredSegments.fromRuns(listOf(listOf(0, 500), listOf(-3, 2)), 10)

        assertEquals(10, restored.size)
        assertTrue(restored[0])
        assertTrue(restored[9])
        assertFalse(restored[10])
    }

    // --- GPX export -------------------------------------------------------

    @Test
    fun `an exported ride imports straight back into the app`() {
        val original = ride(endedAtMs = 1_700_000_300_000)
        val out = ByteArrayOutputStream()
        GpxExport.write(original, out)

        val route = GpxImport.parse(ByteArrayInputStream(out.toByteArray()), "fallback", KXmlParser())

        assertEquals(original.routeName, route.name)
        assertEquals(original.trail.size, route.points.size)
        original.trail.forEachIndexed { i, p ->
            assertEquals(p.lat, route.points[i].lat, 1e-6)
            assertEquals(p.lon, route.points[i].lon, 1e-6)
            // Import smooths elevation over distance, so this is deliberately
            // loose -- what matters is that the profile came back, not that it
            // came back untouched.
            assertEquals(requireNotNull(p.ele), route.points[i].ele, 2.0)
        }
        assertEquals(original.distanceM, route.distanceM, 1.0)
        assertEquals(original.ascentM, route.ascentM, 1.0)
    }

    @Test
    fun `a route name with markup does not produce broken xml`() {
        val original = ride(endedAtMs = 1).copy(routeName = "Tea & <cake> \"trip\"")
        val out = ByteArrayOutputStream()
        GpxExport.write(original, out)

        val route = GpxImport.parse(ByteArrayInputStream(out.toByteArray()), "fallback", KXmlParser())

        assertEquals("Tea & <cake> \"trip\"", route.name)
    }

    @Test
    fun `timestamps are written in UTC`() {
        // 2023-11-14T22:13:20Z, deliberately checked as text: a local-time export
        // silently shifts every ride by the machine's offset.
        val out = ByteArrayOutputStream()
        GpxExport.write(ride(endedAtMs = 1), out)

        assertTrue(out.toString().contains("<time>2023-11-14T22:13:20Z</time>"))
    }

    @Test
    fun `the suggested file name sorts by date and is filesystem safe`() {
        val name = GpxExport.suggestedFileName(ride().copy(routeName = "Venlo / Maas: loop"))

        assertEquals("2023-11-14 Venlo Maas loop.gpx", name)
    }

    @Test
    fun `an unnamed ride still gets a usable file name`() {
        val name = GpxExport.suggestedFileName(ride().copy(routeName = "🚲"))

        assertEquals("2023-11-14 ride.gpx", name)
    }
}
