package de.kettenblatt

import de.kettenblatt.data.GpxImport
import de.kettenblatt.prep.TilePack
import de.kettenblatt.prep.TileSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * Tile selection, against the numbers `tools/navi/tiles.py` produces.
 *
 * A pack built on the phone has to cover exactly the ground the desktop one
 * did -- a corridor one tile narrower is a blank strip beside the route,
 * discovered in a field with no signal.
 */
class TilePackTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }

    private val expected by lazy {
        json.parseToJsonElement(resource("venlo_tiles_expected.json").bufferedReader().readText())
            .jsonObject
    }

    private val route by lazy { GpxImport.parse(resource("venlo.gpx"), "venlo", KXmlParser()) }

    @Test
    fun `tile addressing matches the python implementation`() {
        val cases = expected["deg2tile"]!!.jsonObject
        assertTrue("no cases loaded", cases.isNotEmpty())
        cases.forEach { (key, value) ->
            val (lat, lon, z) = key.split(",")
            val gold = value.jsonArray.map { it.jsonPrimitive.content.toInt() }

            val (x, y) = TilePack.deg2tile(lat.toDouble(), lon.toDouble(), z.toInt())
            assertEquals("x for $key", gold[0], x)
            assertEquals("y for $key", gold[1], y)
        }
    }

    @Test
    fun `web mercator clamps rather than running off the projection`() {
        // Beyond ~85 degrees the projection has no bottom; a raw formula gives
        // a row index outside the tile grid and the pack ends up unreadable.
        val n = 1 shl 5
        val (_, far) = TilePack.deg2tile(89.9, 10.0, 5)
        val (_, south) = TilePack.deg2tile(-89.9, 10.0, 5)
        assertTrue(far in 0 until n)
        assertTrue(south in 0 until n)
    }

    @Test
    fun `corridor and bbox counts match python, and the cheaper one wins`() {
        val counts = expected["counts"]!!.jsonObject
        counts.forEach { (zoom, value) ->
            val z = zoom.toInt()
            val gold = value.jsonObject
            val corridor = TilePack.corridorTiles(route.points, z, 500.0)
            val box = TilePack.bboxTiles(route.points, z)
            val (chosen, shape) = TilePack.tilesFor(route.points, z, 500.0)

            assertEquals("corridor at z$z", gold["corridor"]!!.jsonPrimitive.content.toInt(), corridor.size)
            assertEquals("bbox at z$z", gold["bbox"]!!.jsonPrimitive.content.toInt(), box.size)
            assertEquals("chosen at z$z", gold["n"]!!.jsonPrimitive.content.toInt(), chosen.size)
            assertEquals("shape at z$z", gold["shape"]!!.jsonPrimitive.content, shape)
        }
    }

    @Test
    fun `a plan covers the same tiles the desktop pack would`() {
        val plan = TilePack.plan(
            route.points, TileSource.byKey("opentopomap"), zoomMin = 12, zoomMax = 16, bufferM = 500.0,
        )
        assertEquals(expected["total_12_16"]!!.jsonPrimitive.content.toInt(), plan.tiles.size)
        assertEquals(12, plan.zoomMin)
        assertEquals(16, plan.zoomMax)
        // ~35 KB a tile: enough to warn before spending someone's bandwidth.
        assertTrue(plan.estimatedBytes > 10L * 1024 * 1024)
        assertTrue(plan.tiles.distinct().size == plan.tiles.size)
    }

    @Test
    fun `a zoom beyond the source's maximum is clamped, not requested`() {
        // Asking OpenTopoMap for zoom 20 returns nothing useful; the pack would
        // simply be missing its deepest levels with no explanation.
        val plan = TilePack.plan(
            route.points, TileSource.byKey("opentopomap"), zoomMin = 12, zoomMax = 20, bufferM = 500.0,
        )
        assertEquals(17, plan.zoomMax)
        assertTrue(plan.tiles.all { it.first <= 17 })
    }

    @Test
    fun `a bigger buffer never covers less ground`() {
        val narrow = TilePack.corridorTiles(route.points, 15, 200.0)
        val wide = TilePack.corridorTiles(route.points, 15, 1_000.0)
        assertTrue(wide.size > narrow.size)
        assertTrue("a wider corridor must contain the narrower one", wide.containsAll(narrow))
    }

    @Test
    fun `tile urls fill in every placeholder`() {
        val topo = TileSource.byKey("opentopomap")
        val url = topo.url(14, 8472, 5454, null)
        assertEquals("https://a.tile.opentopomap.org/14/8472/5454.png", url)
        assertTrue("no placeholder may survive", !url.contains("{"))

        val thunder = TileSource.byKey("thunderforest-cycle")
        assertTrue(thunder.needsKey)
        assertTrue(thunder.url(14, 1, 2, "abc123").endsWith("apikey=abc123"))
    }

    @Test
    fun `subdomains spread the load across the volunteer servers`() {
        val topo = TileSource.byKey("opentopomap")
        val hosts = (0 until 6).map { topo.url(14, it, 0, null).substringAfter("//").substringBefore('.') }
        assertEquals(setOf("a", "b", "c"), hosts.toSet())
    }

    @Test
    fun `an unknown source falls back rather than failing a download`() {
        assertEquals("opentopomap", TileSource.byKey("something-else").key)
    }
}
