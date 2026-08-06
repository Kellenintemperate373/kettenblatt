package de.kettenblatt

import de.kettenblatt.data.Backup
import de.kettenblatt.data.BackupException
import de.kettenblatt.data.Ride
import de.kettenblatt.data.RideStore
import de.kettenblatt.data.RouteIndex
import de.kettenblatt.data.RouteMeta
import de.kettenblatt.data.Settings
import de.kettenblatt.data.SettingsCodec
import de.kettenblatt.data.TrailPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Backup and restore.
 *
 * There is no account behind this app, so this is the only route out of a lost
 * phone. It is also the one feature that reads a file a stranger could have
 * written, which is why the hostile cases are here too.
 */
class BackupTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "backup-test-${hashCode()}")
    private val phoneA = File(root, "a")
    private val phoneB = File(root, "b")

    private fun routes(phone: File) = File(phone, "routes").apply { mkdirs() }
    private fun rides(phone: File) = File(phone, "rides").apply { mkdirs() }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun meta(id: String, name: String, favourite: Boolean = false) = RouteMeta(
        id = id,
        name = name,
        fileName = "$id-${name.replace(" ", "_")}.navi.json",
        importedAtMs = 1_700_000_000_000,
        distanceM = 28_833.0,
        ascentM = 43.3,
        hasGuidance = true,
        maneuverCount = 70,
        favourite = favourite,
    )

    private fun addRoute(phone: File, m: RouteMeta, body: String = """{"route":"${m.id}"}""") {
        File(routes(phone), m.fileName).writeText(body)
        RouteIndex(routes(phone)).add(m)
    }

    private fun addRide(phone: File, id: String) {
        RideStore(rides(phone)).save(
            Ride(
                id = id, routeId = "r", routeName = "Venlo loop", reversed = false,
                startedAtMs = 1_700_000_000_000, endedAtMs = 1_700_000_100_000,
                trail = listOf(TrailPoint(51.0, 6.0, 10.0, 1_700_000_000_000)),
            )
        )
    }

    private fun backUp(phone: File, settings: Map<String, String> = SettingsCodec.encode(Settings())): ByteArray {
        val out = ByteArrayOutputStream()
        Backup.write(routes(phone), rides(phone), settings, 1_700_000_000_000, out)
        return out.toByteArray()
    }

    private fun restoreInto(
        phone: File,
        bytes: ByteArray,
        current: Map<String, String> = SettingsCodec.encode(Settings()),
        onSettings: (Map<String, String>) -> Unit = {},
    ) = Backup.restore(ByteArrayInputStream(bytes), routes(phone), rides(phone), current, onSettings)

    // --- round trip -------------------------------------------------------

    @Test
    fun `a library survives a move to another phone`() {
        addRoute(phoneA, meta("r1", "Venlo loop", favourite = true))
        addRoute(phoneA, meta("r2", "Maas ferry"))
        addRide(phoneA, "ride-1")

        val summary = restoreInto(phoneB, backUp(phoneA))

        assertEquals(2, summary.routesAdded)
        assertEquals(1, summary.ridesAdded)

        val restored = RouteIndex(routes(phoneB)).list()
        assertEquals(listOf("Venlo loop", "Maas ferry"), restored.map { it.name })
        // Favourites sort first, so the ordering is itself part of the restore.
        assertTrue("favourite was lost", restored.first().favourite)
        assertEquals(70, restored.first().maneuverCount)
        assertEquals(1, RideStore(rides(phoneB)).list().size)

        // And the route file itself came across, not just its index entry.
        val file = File(routes(phoneB), restored.first().fileName)
        assertTrue(file.exists())
        assertEquals("""{"route":"r1"}""", file.readText())
    }

    @Test
    fun `restoring twice changes nothing the second time`() {
        addRoute(phoneA, meta("r1", "Venlo loop"))
        addRide(phoneA, "ride-1")
        val bytes = backUp(phoneA)

        restoreInto(phoneB, bytes)
        val again = restoreInto(phoneB, bytes)

        assertEquals(0, again.routesAdded)
        assertEquals(1, again.routesSkipped)
        assertEquals(0, again.ridesAdded)
        assertEquals(1, again.ridesSkipped)
        assertEquals(1, RouteIndex(routes(phoneB)).list().size)
    }

    @Test
    fun `restoring never overwrites what is already there`() {
        // The same id, renamed on this phone. The local name must win.
        addRoute(phoneA, meta("r1", "Original name"))
        val bytes = backUp(phoneA)

        addRoute(phoneB, meta("r1", "Renamed here"))
        val summary = restoreInto(phoneB, bytes)

        assertEquals(0, summary.routesAdded)
        assertEquals("Renamed here", RouteIndex(routes(phoneB)).find("r1")!!.name)
    }

    @Test
    fun `an offline map is not claimed after a restore`() {
        // Packs are not in a backup, so a restored route must not say it has one
        // -- the map would fall back to a file that does not exist.
        addRoute(phoneA, meta("r1", "Venlo loop").copy(tilesFileName = "r1.mbtiles"))

        restoreInto(phoneB, backUp(phoneA))

        assertNull(RouteIndex(routes(phoneB)).find("r1")!!.tilesFileName)
    }

    @Test
    fun `a backup is small enough to mail to yourself`() {
        repeat(10) { addRoute(phoneA, meta("r$it", "Route $it"), body = "x".repeat(45_000)) }
        // Ten prepared routes at roughly the size of the reference bundle.
        assertTrue("backup was ${backUp(phoneA).size} bytes", backUp(phoneA).size < 2_000_000)
    }

    // --- settings ---------------------------------------------------------

    @Test
    fun `settings come back on a phone still at defaults`() {
        addRoute(phoneA, meta("r1", "Venlo loop"))
        val theirs = SettingsCodec.encode(Settings(units = de.kettenblatt.data.Units.IMPERIAL))

        var applied: Map<String, String>? = null
        val summary = restoreInto(phoneB, backUp(phoneA, theirs), onSettings = { applied = it })

        assertTrue(summary.settingsApplied)
        assertEquals("IMPERIAL", applied!![SettingsCodec.UNITS])
    }

    @Test
    fun `settings are left alone on a phone that has its own`() {
        // Importing somebody else's route library must not rewrite your units.
        addRoute(phoneA, meta("r1", "Venlo loop"))
        val backup = backUp(phoneA, SettingsCodec.encode(Settings(units = de.kettenblatt.data.Units.IMPERIAL)))
        val mine = SettingsCodec.encode(Settings(autoDimDelayMs = 30_000))

        var applied: Map<String, String>? = null
        val summary = restoreInto(phoneB, backup, current = mine, onSettings = { applied = it })

        assertFalse(summary.settingsApplied)
        assertNull(applied)
        assertEquals(1, summary.routesAdded)   // the routes still arrive
    }

    // --- hostile and broken input ----------------------------------------

    @Test
    fun `a zip that names a path outside the app is not written there`() {
        // Zip slip: an entry called ../../evil.txt escapes the target directory
        // unless the extractor refuses it.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":1,"createdAtMs":0,"routes":0,"rides":0}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("rides/../../../evil.json"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
        }

        val escaped = File(root, "evil.json")
        val summary = restoreInto(phoneB, out.toByteArray())

        assertEquals(0, summary.ridesAdded)
        assertFalse("wrote outside the app's own directory", escaped.exists())
        assertFalse(File(root, "../evil.json").exists())
    }

    @Test
    fun `a zip that is not a backup is refused with a readable message`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("holiday.jpg"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }

        val error = runCatching { restoreInto(phoneB, out.toByteArray()) }.exceptionOrNull()

        assertTrue(error is BackupException)
        assertTrue(error!!.message!!.contains("does not look like"))
    }

    @Test
    fun `a backup from a newer version says so rather than half-restoring`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":99,"createdAtMs":0,"routes":1,"rides":0}""".toByteArray())
            zip.closeEntry()
        }

        val error = runCatching { restoreInto(phoneB, out.toByteArray()) }.exceptionOrNull()

        assertTrue(error is BackupException)
        assertTrue(error!!.message!!.contains("newer version"))
        assertTrue(RouteIndex(routes(phoneB)).list().isEmpty())
    }

    @Test
    fun `an index entry with no file behind it is skipped, not half-added`() {
        addRoute(phoneA, meta("r1", "Venlo loop"))
        File(routes(phoneA), meta("r1", "Venlo loop").fileName).delete()

        val summary = restoreInto(phoneB, backUp(phoneA))

        assertEquals(0, summary.routesAdded)
        assertEquals(1, summary.routesSkipped)
        assertTrue(RouteIndex(routes(phoneB)).list().isEmpty())
    }

    @Test
    fun `an empty library backs up and restores without complaint`() {
        val summary = restoreInto(phoneB, backUp(phoneA))
        assertEquals(0, summary.routesAdded)
        assertFalse(summary.addedAnything)
    }

    @Test
    fun `the file name sorts by date`() {
        assertEquals("kettenblatt-backup-2023-11-14.zip", Backup.suggestedFileName(1_700_000_000_000))
    }

    @Test
    fun `the manifest counts what went in`() {
        addRoute(phoneA, meta("r1", "Venlo loop"))
        addRoute(phoneA, meta("r2", "Maas ferry"))
        addRide(phoneA, "ride-1")

        val out = ByteArrayOutputStream()
        val manifest = Backup.write(routes(phoneA), rides(phoneA), emptyMap(), 1_700_000_000_000, out)

        assertEquals(2, manifest.routes)
        assertEquals(1, manifest.rides)
        assertNotNull(manifest.format)
    }
}
