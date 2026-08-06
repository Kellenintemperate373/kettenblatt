package de.kettenblatt

import de.kettenblatt.data.BundleReader
import de.kettenblatt.data.GpxImport
import de.kettenblatt.data.Maneuver
import de.kettenblatt.data.RouteMath
import de.kettenblatt.data.SurfaceSpan
import de.kettenblatt.prep.BundleWriter
import de.kettenblatt.prep.Maneuvers
import de.kettenblatt.prep.Trace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.util.zip.GZIPInputStream

/**
 * Writing a bundle, and reading it straight back.
 *
 * The end-to-end case builds a bundle the way the app will -- GPX plus recorded
 * Valhalla responses -- and checks it against a bundle known to navigate
 * correctly, which is the real question: does a route prepared today still come
 * out the same as one prepared before the last change?
 */
class BundleWriterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }

    private fun gzippedJson(name: String): JsonObject =
        GZIPInputStream(resource(name)).use {
            json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }

    private fun venloGpx() = GpxImport.parse(resource("venlo.gpx"), "venlo", KXmlParser())

    @Test
    fun `a written bundle reads back as the same route`() {
        val route = venloGpx().copy(
            maneuvers = listOf(
                Maneuver(4, "turn_right", "Genraydelweg", "Turn right onto Genraydelweg."),
                Maneuver(90, "ferry", null, "Take the Grubbenvorst-Velden Ferry."),
            ),
            reverseManeuvers = listOf(Maneuver(7, "turn_left", "Dorpstraat", "Turn left.")),
            surfaces = listOf(
                SurfaceSpan(0, 40, "paved", "residential", null),
                SurfaceSpan(41, 80, "compacted", "track", "ferry"),
            ),
        )

        val parsed = BundleReader.parse(BundleWriter.write(route))

        assertEquals(route.name, parsed.name)
        assertEquals(route.activity, parsed.activity)
        assertEquals(route.points.size, parsed.points.size)
        assertEquals(route.distanceM, parsed.distanceM, 0.1)
        assertEquals(route.ascentM, parsed.ascentM, 0.1)
        assertEquals(route.maneuvers, parsed.maneuvers)
        assertEquals(route.reverseManeuvers, parsed.reverseManeuvers)
        assertEquals(route.surfaces, parsed.surfaces)
        assertEquals(route.waypoints, parsed.waypoints)

        // Rounding is to 0.1 m, which no GPS can tell apart.
        route.points.forEachIndexed { i, p ->
            assertEquals(p.lat, parsed.points[i].lat, 1e-6)
            assertEquals(p.lon, parsed.points[i].lon, 1e-6)
            assertEquals(p.ele, parsed.points[i].ele, 0.05)
        }
    }

    @Test
    fun `a bundle built on the phone matches the one prep_py wrote`() {
        val gpx = venloGpx()
        val cum = RouteMath.cumulativeDistances(gpx.points)
        val traceRoute = gzippedJson("venlo_trace_route.json.gz")
        val traceAttributes = gzippedJson("venlo_trace_attributes.json.gz")

        val prepared = gpx.copy(
            maneuvers = Maneuvers.mapManeuvers(traceRoute, gpx.points, cum),
            surfaces = Maneuvers.attributeSpans(traceAttributes, gpx.points.size),
        )
        val written = BundleReader.parse(BundleWriter.write(prepared))

        // The reference bundle was matched against a newer tileset, so cue
        // *content* can drift; the shape of the file must not.
        val reference = BundleReader.parse(resource("venlo.navi.json").bufferedReader().readText())

        assertEquals(reference.name, written.name)
        assertEquals(reference.activity, written.activity)
        assertEquals(reference.points.size, written.points.size)
        assertEquals(reference.distanceM, written.distanceM, 1.0)
        assertEquals(reference.ascentM, written.ascentM, 1.0)
        assertEquals(reference.waypoints, written.waypoints)
        assertEquals("cue count", reference.maneuvers.size, written.maneuvers.size)
        assertTrue("guidance survived the round trip", written.hasGuidance)

        // Elevation is smoothed identically on both sides.
        reference.points.forEachIndexed { i, p ->
            assertEquals("point $i", p.ele, written.points[i].ele, 0.06)
        }
    }

    @Test
    fun `activity comes from the track, not from a link's mime type`() {
        // Komoot's <metadata><link> carries <type>text/html</type> before the
        // track's own <type>e_touring_bicycle</type>. Reading the first <type>
        // anywhere made every export "text/html", which picks the wrong costing
        // for a hike and gives it a cyclist's ETA.
        assertEquals("e_touring_bicycle", venloGpx().activity)
    }

    @Test
    fun `version and rounding are what the reader expects`() {
        val written = json.parseToJsonElement(BundleWriter.write(venloGpx())).jsonObject

        assertEquals(BundleReader.SUPPORTED_VERSION, written["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, written["bbox"]!!.jsonArray.size)

        val first = written["points"]!!.jsonArray.first().jsonArray
        assertEquals(3, first.size)
        // Six decimal places is ~0.1 m; more is bytes for precision no GPS has.
        assertTrue(first[0].jsonPrimitive.content.substringAfter('.', "").length <= 6)
    }

    @Test
    fun `match quality and warnings are carried into the file`() {
        val quality = Maneuvers.assessMatch(
            gzippedJson("venlo_trace_attributes.json.gz"),
            gzippedJson("venlo_trace_route.json.gz"),
            "bicycle",
            28_833.0,
            606,
        )
        val text = BundleWriter.write(venloGpx(), quality, listOf("retried with pedestrian"))
        val written = json.parseToJsonElement(text).jsonObject

        assertNotNull(written["matchQuality"])
        assertEquals("bicycle", written["matchQuality"]!!.jsonObject["costing"]!!.jsonPrimitive.content)
        assertEquals(1, written["warnings"]!!.jsonArray.size)

        // And a reader that has never heard of those fields still opens it.
        assertEquals(606, BundleReader.parse(text).points.size)
    }

    // --- trace thinning ---------------------------------------------------

    @Test
    fun `a short track is sent whole`() {
        val gpx = venloGpx()
        val trace = Trace.of(gpx.points, maxPoints = 2_000)

        assertEquals(gpx.points.size, trace.points.size)
        assertTrue(!trace.isThinned)
        assertEquals(17, trace.originalIndex(17))
    }

    @Test
    fun `a long recording is thinned but keeps both ends`() {
        // A two-hour ride at 1 Hz is 7000 points, which the service would refuse.
        val long = (0 until 7_000).map {
            de.kettenblatt.data.TrackPoint(51.0 + it * 0.00001, 6.0, 10.0)
        }
        val trace = Trace.of(long, maxPoints = 2_000)

        assertEquals(2_000, trace.points.size)
        assertTrue(trace.isThinned)
        assertEquals("the start must survive", 0, trace.originalIndex(0))
        assertEquals("the finish must survive", 6_999, trace.originalIndex(1_999))
        assertEquals(long.first(), trace.points.first())
        assertEquals(long.last(), trace.points.last())

        // Monotonic, so spans derived from it cannot run backwards.
        var previous = -1
        for (i in trace.points.indices) {
            val original = trace.originalIndex(i)
            assertTrue("index went backwards at $i", original > previous)
            previous = original
        }
    }

    @Test
    fun `spans from a thinned trace land on original indices`() {
        val long = (0 until 7_000).map {
            de.kettenblatt.data.TrackPoint(51.0 + it * 0.00001, 6.0, 10.0)
        }
        val trace = Trace.of(long, maxPoints = 2_000)

        val spans = Maneuvers.attributeSpans(
            gzippedJson("venlo_trace_attributes.json.gz"),
            pointCount = long.size,
            sentToOriginal = trace::originalIndex,
            sentCount = trace.points.size,
        )

        assertTrue(spans.isNotEmpty())
        spans.forEach {
            assertTrue("span ${it.from}..${it.to} outside the track", it.to < long.size)
            assertTrue("span ${it.from}..${it.to} inverted", it.to >= it.from)
        }
        // Spans must reach into the far end of the track, not bunch up at the
        // start as they would if the index map were ignored.
        assertTrue("spans stop at ${spans.last().to}", spans.last().to > 2_000)
    }
}
