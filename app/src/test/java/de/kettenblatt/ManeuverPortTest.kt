package de.kettenblatt

import de.kettenblatt.data.GpxImport
import de.kettenblatt.data.RouteMath
import de.kettenblatt.prep.Costing
import de.kettenblatt.prep.Maneuvers
import de.kettenblatt.prep.decodePolyline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.util.zip.GZIPInputStream

/**
 * The Kotlin preparation pipeline against the Python one, on identical input.
 *
 * `venlo_trace_*.json.gz` are real recorded Valhalla responses, and
 * `venlo_expected.json` is the output that was verified against the Python
 * reference implementation this code was ported from. Holding the port to a
 * fixed file means tileset drift on the live service cannot be mistaken for a
 * regression, and any divergence points at a specific maneuver or span rather
 * than at "the output looks different".
 */
class ManeuverPortTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }

    private fun gzippedJson(name: String): JsonObject =
        GZIPInputStream(resource(name)).use {
            json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }

    private val traceRoute by lazy { gzippedJson("venlo_trace_route.json.gz") }
    private val traceAttributes by lazy { gzippedJson("venlo_trace_attributes.json.gz") }
    private val expected by lazy {
        json.parseToJsonElement(resource("venlo_expected.json").bufferedReader().readText())
            .jsonObject
    }

    private val route by lazy {
        GpxImport.parse(resource("venlo.gpx"), "venlo", KXmlParser())
    }

    /** The GPX importer smooths elevation; matching only needs lat/lon and distance. */
    private val cumDist by lazy { RouteMath.cumulativeDistances(route.points) }

    @Test
    fun `cue mapping reproduces the python output exactly`() {
        val actual = Maneuvers.mapManeuvers(traceRoute, route.points, cumDist)
        val gold = expected["maneuvers"]!!.jsonArray

        assertEquals("cue count", gold.size, actual.size)
        gold.forEachIndexed { i, element ->
            val e = element.jsonObject
            val a = actual[i]
            val where = "cue $i"
            assertEquals("$where index", e["idx"]!!.jsonPrimitive.content.toInt(), a.idx)
            assertEquals("$where type", e["type"]!!.jsonPrimitive.content, a.type)
            assertEquals("$where street", e["street"]?.jsonPrimitive?.content, a.street)
            assertEquals("$where instruction", e["instruction"]!!.jsonPrimitive.content, a.instruction)
        }
    }

    @Test
    fun `surface spans reproduce the python output exactly`() {
        val actual = Maneuvers.attributeSpans(traceAttributes, route.points.size)
        val gold = expected["surfaces"]!!.jsonArray

        assertEquals("span count", gold.size, actual.size)
        gold.forEachIndexed { i, element ->
            val e = element.jsonObject
            val a = actual[i]
            assertEquals("span $i from", e["from"]!!.jsonPrimitive.content.toInt(), a.from)
            assertEquals("span $i to", e["to"]!!.jsonPrimitive.content.toInt(), a.to)
            assertEquals("span $i surface", e["surface"]?.jsonPrimitive?.content, a.surface)
            assertEquals("span $i roadClass", e["roadClass"]?.jsonPrimitive?.content, a.roadClass)
            assertEquals("span $i use", e["use"]?.jsonPrimitive?.content, a.use)
        }
    }

    @Test
    fun `match quality reproduces the python assessment`() {
        val actual = Maneuvers.assessMatch(
            traceAttributes, traceRoute, "bicycle", cumDist.last(), route.points.size,
        )
        val gold = expected["quality"]!!.jsonObject

        assertEquals(gold["total"]!!.jsonPrimitive.content.toInt(), actual.totalPoints)
        assertEquals(gold["matched"]!!.jsonPrimitive.content.toInt(), actual.matched)
        assertEquals(gold["interpolated"]!!.jsonPrimitive.content.toInt(), actual.interpolated)
        assertEquals(gold["unmatched"]!!.jsonPrimitive.content.toInt(), actual.unmatched)
        assertEquals(gold["meanOffsetM"]!!.jsonPrimitive.content.toDouble(), actual.meanOffsetM, 0.01)
        assertEquals(gold["maxOffsetM"]!!.jsonPrimitive.content.toDouble(), actual.maxOffsetM, 0.01)
        assertEquals(
            gold["lengthDeviation"]!!.jsonPrimitive.content.toDouble(),
            actual.lengthDeviation,
            0.0001,
        )
        assertTrue("this match is a good one and must read as usable", actual.isUsable)
    }

    @Test
    fun `matched route length matches python`() {
        assertEquals(
            expected["routeLengthM"]!!.jsonPrimitive.content.toDouble(),
            Maneuvers.routeLengthM(traceRoute),
            0.01,
        )
    }

    @Test
    fun `a short match is refused rather than quietly used`() {
        // The failure mode that TRACE_OPTIONS exists to prevent: a route 15%
        // shorter than the input, returned with no error. It must not be usable.
        val short = Maneuvers.assessMatch(
            traceAttributes, traceRoute, "bicycle",
            originalLengthM = 28_833.0 * 1.18,
            pointCount = route.points.size,
        )
        assertTrue("deviation ${short.lengthDeviation}", short.lengthDeviation < -0.05)
        assertTrue(!short.isUsable)
    }

    // --- polyline ---------------------------------------------------------

    @Test
    fun `polyline decodes at valhalla's precision 6`() {
        val shape = traceRoute["trip"]!!.jsonObject["legs"]!!.jsonArray[0]
            .jsonObject["shape"]!!.jsonPrimitive.content
        val decoded = decodePolyline(shape)

        assertTrue("decoded ${decoded.size} points", decoded.size > 100)
        // The route is near Venlo; a precision-5 decode lands ten times further out.
        val (lat, lon) = decoded.first()
        assertEquals(51.4, lat, 0.2)
        assertEquals(6.2, lon, 0.2)
    }

    @Test
    fun `decoding at the wrong precision is visibly wrong`() {
        val shape = traceRoute["trip"]!!.jsonObject["legs"]!!.jsonArray[0]
            .jsonObject["shape"]!!.jsonPrimitive.content

        val right = decodePolyline(shape).first()
        val wrong = decodePolyline(shape, precision = 5).first()

        // Not a subtle difference: a factor of ten, which would put the whole
        // route in the Gulf of Guinea rather than raising anything.
        assertNotEquals(right.first, wrong.first, 1.0)
        assertEquals(right.first * 10, wrong.first, 0.001)
    }

    // --- costing ----------------------------------------------------------

    @Test
    fun `costing follows komoot's activity names through their variants`() {
        // The reference route is "e_touring_bicycle"; Komoot layers e_/_easy/
        // _advanced onto the same handful of sports.
        assertEquals("bicycle", Costing.forActivity("e_touring_bicycle").name)
        assertEquals("Hybrid", Costing.forActivity("e_touring_bicycle").options["bicycle_type"])
        assertEquals("Mountain", Costing.forActivity("e_mtb_advanced").options["bicycle_type"])
        assertEquals("Road", Costing.forActivity("racebike_easy").options["bicycle_type"])
        assertEquals("City", Costing.forActivity("citybike").options["bicycle_type"])

        assertEquals("pedestrian", Costing.forActivity("hike_advanced").name)
        assertEquals("pedestrian", Costing.forActivity("nordic_walking").name)

        // Unknown sports get the permissive hybrid default rather than failing.
        assertEquals("Hybrid", Costing.forActivity("something_new").options["bicycle_type"])
        assertEquals("Hybrid", Costing.forActivity(null).options["bicycle_type"])
    }

    @Test
    fun `bicycle costing keeps the options that stop it avoiding tracks`() {
        // Without these, matching drifts onto a parallel paved road wherever
        // Komoot routed over a track -- exactly the interesting parts.
        val options = Costing.forActivity("e_touring_bicycle").options
        assertEquals(0.5, options["use_roads"])
        assertEquals(0.5, options["use_hills"])
        assertEquals(0.0, options["avoid_bad_surfaces"])
    }
}
