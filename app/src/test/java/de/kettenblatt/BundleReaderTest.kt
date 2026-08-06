package de.kettenblatt

import de.kettenblatt.data.BundleReader
import de.kettenblatt.data.RouteMath
import de.kettenblatt.data.TrackPoint
import de.kettenblatt.geo.Geo
import de.kettenblatt.geo.projectOntoSegment
import de.kettenblatt.ui.compassPoint
import de.kettenblatt.ui.formatDistance
import de.kettenblatt.ui.formatDuration
import de.kettenblatt.ui.formatShortDistance
import de.kettenblatt.ui.formatSpeed
import de.kettenblatt.ui.maneuverLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class BundleReaderTest {

    private fun venloText(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("venlo.navi.json"))
            .bufferedReader().readText()

    @Test
    fun `reads the bundle produced by prep_py`() {
        val route = BundleReader.parse(venloText())

        assertEquals("Fahrradtour Venlo - Blaue Lagune", route.name)
        assertEquals("e_touring_bicycle", route.activity)
        assertEquals(606, route.points.size)
        assertEquals(28_833.0, route.distanceM, 50.0)
        assertTrue(route.hasGuidance)
        assertEquals(70, route.maneuvers.size)
    }

    @Test
    fun `carries the waypoint through`() {
        val wp = BundleReader.parse(venloText()).waypoints.single()
        assertEquals("Eissalon Clevers Grubbenvorst", wp.name)
        assertEquals("Restaurant", wp.sym)
        assertEquals(51.419667, wp.lat, 1e-6)
    }

    @Test
    fun `maneuvers arrive in route order`() {
        val idx = BundleReader.parse(venloText()).maneuvers.map { it.idx }
        assertEquals(idx.sorted(), idx)
    }

    @Test
    fun `identifies the ferry crossings`() {
        val route = BundleReader.parse(venloText())
        assertEquals(2, route.maneuvers.count { it.type == "ferry" })
        assertEquals(2, route.surfaces.count { it.isFerry })
    }

    @Test
    fun `reports surface including unpaved stretches`() {
        val route = BundleReader.parse(venloText())
        assertTrue(route.surfaces.any { it.isUnpaved })
        assertEquals("paved_smooth", route.surfaceAt(0))
    }

    @Test
    fun `parallel arrays match the point count`() {
        val route = BundleReader.parse(venloText())
        assertEquals(route.points.size, route.cumDistM.size)
        assertEquals(route.points.size, route.cumAscentM.size)
        assertTrue(route.cumDistM.toList() == route.cumDistM.sorted())
    }

    @Test
    fun `recomputes distances when the bundle's arrays are the wrong length`() {
        // A truncated transfer must not desync indices from geometry.
        val text = """
            {"version":1,"name":"t","points":[[51.0,6.0,10.0],[51.0,6.001,10.0]],
             "cumDistM":[0.0],"cumAscentM":[]}
        """.trimIndent()
        val route = BundleReader.parse(text)
        assertEquals(2, route.cumDistM.size)
        assertEquals(2, route.cumAscentM.size)
        assertTrue(route.distanceM > 60)
    }

    @Test
    fun `drops maneuvers pointing outside the track`() {
        val text = """
            {"version":1,"name":"t","points":[[51.0,6.0,0.0],[51.0,6.001,0.0]],
             "maneuvers":[{"idx":99,"type":"turn_left","instruction":"x"},
                          {"idx":1,"type":"turn_right","instruction":"y"}]}
        """.trimIndent()
        val route = BundleReader.parse(text)
        assertEquals(1, route.maneuvers.size)
        assertEquals(1, route.maneuvers.single().idx)
    }

    @Test
    fun `tolerates unknown fields from a future prep_py`() {
        val text = """
            {"version":1,"name":"t","somethingNew":42,
             "points":[[51.0,6.0,0.0],[51.0,6.001,0.0]]}
        """.trimIndent()
        assertEquals("t", BundleReader.parse(text).name)
    }

    @Test
    fun `refuses a bundle from a newer format version`() {
        val text = """{"version":99,"name":"t","points":[[51.0,6.0,0.0],[51.0,6.1,0.0]]}"""
        assertThrows(IllegalArgumentException::class.java) { BundleReader.parse(text) }
    }

    @Test
    fun `refuses a bundle with too few points`() {
        val text = """{"version":1,"name":"t","points":[[51.0,6.0,0.0]]}"""
        assertThrows(IllegalArgumentException::class.java) { BundleReader.parse(text) }
    }

    @Test
    fun `a bundle without guidance is still navigable`() {
        val text = """
            {"version":1,"name":"t","points":[[51.0,6.0,0.0],[51.0,6.001,0.0]]}
        """.trimIndent()
        val route = BundleReader.parse(text)
        assertFalse(route.hasGuidance)
        assertNull(route.surfaceAt(0))
        assertTrue(route.distanceM > 0)
    }
}

class RouteMathTest {

    @Test
    fun `ascent threshold rejects noise but keeps real climbs`() {
        val wobble = doubleArrayOf(100.0, 102.0, 100.0, 102.0, 100.0)
        assertEquals(0.0, RouteMath.cumulativeAscent(wobble).last(), 0.001)

        val climb = doubleArrayOf(100.0, 110.0)
        assertEquals(10.0, RouteMath.cumulativeAscent(climb).last(), 0.001)
    }

    @Test
    fun `smoothing matches the preprocessing constants`() {
        assertEquals(60.0, RouteMath.SMOOTHING_WINDOW_M, 0.0)
        assertEquals(3.0, RouteMath.ASCENT_THRESHOLD_M, 0.0)
    }

    @Test
    fun `smoothing recovers a noisy climb the way prep_py does`() {
        // Mirrors test_smoothing_survives_worst_case_sawtooth in the Python
        // toolchain: a 99.5 m climb sampled every 10 m, with the worst-case
        // alternating noise, runs away to 450 m unsmoothed.
        //
        // The cumulative distances are exact rather than derived from lat/lon.
        // A distance window quantises to whole samples, so at 10 m spacing a
        // 0.2% change in spacing flips it between 7 and 5 samples and visibly
        // changes the result -- worth isolating from geodesy here.
        val n = 200
        val cum = DoubleArray(n) { 10.0 * it }
        val clean = (0 until n).map { 100.0 + 0.5 * it }
        val noisy = clean.mapIndexed { i, e -> e + if (i % 2 == 0) -2.0 else 2.0 }

        val raw = RouteMath.cumulativeAscent(noisy.toDoubleArray()).last()
        val smoothed = RouteMath.cumulativeAscent(RouteMath.smoothElevation(noisy, cum)).last()

        assertTrue("unsmoothed should run away, got $raw", raw > 400)
        assertEquals(99.5, smoothed, 5.0)
    }

    @Test
    fun `real track spacing still suppresses most of the noise`() {
        // The same signal on realistic ~10 m geodesic spacing. The window covers
        // fewer samples, so rejection is weaker -- but it must still remove the
        // bulk of a 450 m error.
        val n = 200
        val points = (0 until n).map { TrackPoint(51.0, 6.0 + it * 0.000143, 0.0) }
        val cum = RouteMath.cumulativeDistances(points)
        val noisy = (0 until n).map { 100.0 + 0.5 * it + if (it % 2 == 0) -2.0 else 2.0 }

        val smoothed = RouteMath.cumulativeAscent(RouteMath.smoothElevation(noisy, cum)).last()
        assertTrue("got $smoothed", smoothed in 95.0..115.0)
    }

    @Test
    fun `cumulative distance starts at zero and rises`() {
        val points = listOf(
            TrackPoint(51.0, 6.0, 0.0),
            TrackPoint(51.0, 6.001, 0.0),
            TrackPoint(51.001, 6.001, 0.0),
        )
        val cum = RouteMath.cumulativeDistances(points)
        assertEquals(0.0, cum[0], 0.0)
        assertTrue(cum[1] > 0 && cum[2] > cum[1])
    }
}

class GeoTest {

    @Test
    fun `haversine matches a known distance`() {
        assertEquals(111_195.0, Geo.haversine(51.0, 6.0, 52.0, 6.0), 200.0)
        assertEquals(0.0, Geo.haversine(51.0, 6.0, 51.0, 6.0), 1e-6)
    }

    @Test
    fun `bearings point the right way`() {
        assertEquals(0.0, Geo.bearing(51.0, 6.0, 52.0, 6.0), 0.1)
        assertEquals(90.0, Geo.bearing(51.0, 6.0, 51.0, 7.0), 0.5)
        assertEquals(180.0, Geo.bearing(51.0, 6.0, 50.0, 6.0), 0.1)
        assertEquals(270.0, Geo.bearing(51.0, 6.0, 51.0, 5.0), 0.5)
    }

    @Test
    fun `bearing delta wraps around north`() {
        assertEquals(20.0, Geo.bearingDelta(350.0, 10.0), 0.001)
        assertEquals(20.0, Geo.bearingDelta(10.0, 350.0), 0.001)
        assertEquals(180.0, Geo.bearingDelta(0.0, 180.0), 0.001)
    }

    @Test
    fun `projection clamps to the ends of a segment`() {
        assertEquals(0.5, projectOntoSegment(5.0, 3.0, 0.0, 0.0, 10.0, 0.0).t, 1e-9)
        assertEquals(3.0, projectOntoSegment(5.0, 3.0, 0.0, 0.0, 10.0, 0.0).distanceM, 1e-9)
        assertEquals(1.0, projectOntoSegment(20.0, 0.0, 0.0, 0.0, 10.0, 0.0).t, 1e-9)
        assertEquals(0.0, projectOntoSegment(-5.0, 0.0, 0.0, 0.0, 10.0, 0.0).t, 1e-9)
    }

    @Test
    fun `a zero length segment does not divide by zero`() {
        val p = projectOntoSegment(3.0, 4.0, 1.0, 1.0, 1.0, 1.0)
        assertEquals(0.0, p.t, 0.0)
        assertEquals(kotlin.math.hypot(2.0, 3.0), p.distanceM, 1e-9)
    }
}

class FormatTest {

    /**
     * Formatting follows the device locale on purpose -- a German phone should
     * read "1,2 km". Pinning the locale here keeps the assertions readable
     * without asserting that only English is correct.
     */
    @Before
    fun pinLocale() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previous)
    }

    private lateinit var previous: Locale

    @Test
    fun `decimal separator follows the locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1,2 km", formatDistance(1234.0))
        Locale.setDefault(Locale.US)
        assertEquals("1.2 km", formatDistance(1234.0))
    }

    @Test
    fun `distances switch units for legibility`() {
        assertEquals("120 m", formatDistance(123.0))
        assertEquals("1.2 km", formatDistance(1234.0))
        assertEquals("28 km", formatDistance(28_400.0))
    }

    @Test
    fun `turn distances stay coarse enough to read while moving`() {
        assertEquals("30 m", formatShortDistance(28.0))
        assertEquals("300 m", formatShortDistance(310.0))
        assertEquals("1.2 km", formatShortDistance(1234.0))
    }

    @Test
    fun `durations drop the hour when there is none`() {
        assertEquals("25m", formatDuration(1500))
        assertEquals("1h 5m", formatDuration(3900))
    }

    @Test
    fun `speed handles the no-fix case`() {
        assertEquals("—", formatSpeed(null))
        assertEquals("18.0 km/h", formatSpeed(5.0))
    }

    @Test
    fun `compass points cover the circle`() {
        assertEquals("N", compassPoint(0.0))
        assertEquals("E", compassPoint(90.0))
        assertEquals("S", compassPoint(180.0))
        assertEquals("W", compassPoint(270.0))
        assertEquals("N", compassPoint(360.0))
        assertEquals("NW", compassPoint(-45.0))
    }

    @Test
    fun `maneuver labels prefer valhalla's own phrasing`() {
        assertEquals(
            "Turn right onto Genraydelweg.",
            maneuverLabel("turn_right", "Genraydelweg", "Turn right onto Genraydelweg."),
        )
        // Falls back for a bundle without instruction text.
        assertEquals("turn right onto Genraydelweg", maneuverLabel("turn_right", "Genraydelweg", ""))
        assertEquals("turn right", maneuverLabel("turn_right", null, ""))
    }
}
