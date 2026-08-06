package de.kettenblatt

import de.kettenblatt.data.RouteIndex
import de.kettenblatt.data.RouteMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Route management: naming, favourites, ordering and removal.
 *
 * These run on the JVM because the index only needs a directory -- which is the
 * reason it was split out of RouteStore, whose file copying needs a Context.
 */
class RouteIndexTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun index(): RouteIndex = RouteIndex(temp.root)

    private fun meta(
        id: String,
        name: String = id,
        importedAtMs: Long = 1_000,
        favourite: Boolean = false,
        tiles: String? = null,
    ) = RouteMeta(
        id = id,
        name = name,
        fileName = "$id.navi.json",
        importedAtMs = importedAtMs,
        distanceM = 1000.0,
        ascentM = 10.0,
        hasGuidance = true,
        maneuverCount = 5,
        tilesFileName = tiles,
        favourite = favourite,
    )

    // --- basics -----------------------------------------------------------

    @Test
    fun `an empty or missing index reads as no routes`() {
        assertTrue(index().list().isEmpty())

        File(temp.root, "index.json").writeText("this is not json")
        assertTrue("a corrupt index must not crash the list", index().list().isEmpty())
    }

    @Test
    fun `added routes survive a reload`() {
        index().add(meta("a", name = "Venlo loop"))
        // A fresh instance re-reads from disk rather than any in-memory state.
        assertEquals("Venlo loop", index().find("a")?.name)
    }

    // --- ordering ---------------------------------------------------------

    @Test
    fun `newest imports come first`() {
        val i = index()
        i.add(meta("old", importedAtMs = 1_000))
        i.add(meta("new", importedAtMs = 9_000))
        assertEquals(listOf("new", "old"), i.list().map { it.id })
    }

    @Test
    fun `favourites sort above everything else`() {
        val i = index()
        i.add(meta("recent", importedAtMs = 9_000))
        i.add(meta("old-favourite", importedAtMs = 1_000, favourite = true))

        assertEquals(
            "a favourite belongs at the top even when it is the oldest",
            listOf("old-favourite", "recent"),
            i.list().map { it.id },
        )
    }

    @Test
    fun `favourites keep recency order among themselves`() {
        val i = index()
        i.add(meta("fav-old", importedAtMs = 1_000, favourite = true))
        i.add(meta("fav-new", importedAtMs = 5_000, favourite = true))
        i.add(meta("plain", importedAtMs = 9_000))

        assertEquals(listOf("fav-new", "fav-old", "plain"), i.list().map { it.id })
    }

    // --- rename -----------------------------------------------------------

    @Test
    fun `renaming changes the display name only`() {
        val i = index()
        i.add(meta("a", name = "2026-07-31_3158524017_Fahrradtour"))

        val renamed = i.update("a") { it.copy(name = "Blaue Lagune") }

        assertEquals("Blaue Lagune", renamed?.name)
        // The stored file must keep its name, or the route stops opening.
        assertEquals("a.navi.json", i.find("a")?.fileName)
    }

    @Test
    fun `updating an unknown route does nothing`() {
        val i = index()
        i.add(meta("a"))
        assertNull(i.update("does-not-exist") { it.copy(name = "x") })
        assertEquals(1, i.list().size)
    }

    // --- favourite --------------------------------------------------------

    @Test
    fun `favourite toggles both ways`() {
        val i = index()
        i.add(meta("a"))
        assertFalse(i.find("a")!!.favourite)

        i.update("a") { it.copy(favourite = true) }
        assertTrue(i.find("a")!!.favourite)

        i.update("a") { it.copy(favourite = false) }
        assertFalse(i.find("a")!!.favourite)
    }

    @Test
    fun `an index written before favourites existed still loads`() {
        // Older files have no `favourite` key at all.
        File(temp.root, "index.json").writeText(
            """
            {"routes":[{"id":"a","name":"Old","fileName":"a.navi.json",
              "importedAtMs":1000,"distanceM":1000.0,"ascentM":10.0,
              "hasGuidance":true,"maneuverCount":5}]}
            """.trimIndent()
        )
        val loaded = index().find("a")
        assertEquals("Old", loaded?.name)
        assertFalse(loaded!!.favourite)
    }

    // --- delete -----------------------------------------------------------

    @Test
    fun `removing returns what it referenced so the files can be cleaned up`() {
        val i = index()
        i.add(meta("a", tiles = "a.mbtiles"))

        val removed = i.remove("a")

        assertEquals("a.navi.json", removed?.fileName)
        assertEquals("a.mbtiles", removed?.tilesFileName)
        assertTrue(i.list().isEmpty())
    }

    @Test
    fun `removing an unknown route leaves the rest alone`() {
        val i = index()
        i.add(meta("a"))
        assertNull(i.remove("b"))
        assertEquals(listOf("a"), i.list().map { it.id })
    }

    @Test
    fun `deleting one route does not disturb the others`() {
        val i = index()
        i.add(meta("a", importedAtMs = 1_000))
        i.add(meta("b", importedAtMs = 2_000, favourite = true))
        i.add(meta("c", importedAtMs = 3_000))

        i.remove("c")

        assertEquals(listOf("b", "a"), i.list().map { it.id })
        assertTrue(i.find("b")!!.favourite)
    }
}
