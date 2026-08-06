package de.kettenblatt.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** What the route list shows without parsing the whole file. */
@Serializable
data class RouteMeta(
    val id: String,
    /** Display name. Renaming changes only this, never the stored file. */
    val name: String,
    val fileName: String,
    val importedAtMs: Long,
    val distanceM: Double,
    val ascentM: Double,
    val hasGuidance: Boolean,
    val maneuverCount: Int = 0,
    val tilesFileName: String? = null,
    /** Favourites sort to the top of the list. Defaulted so older index files load. */
    val favourite: Boolean = false,
) {
    val isBundle: Boolean get() = fileName.endsWith(BUNDLE_SUFFIX)

    companion object {
        const val BUNDLE_SUFFIX = ".navi.json"
    }
}

/**
 * Imported routes on disk.
 *
 * Files are *copied* into app storage on import rather than referenced by their
 * content URI: a SAF grant does not necessarily survive a reboot, and a route
 * that stops opening halfway through a trip is worse than a few hundred KB of
 * duplication.
 *
 * A small JSON index is enough for a list of routes -- Room would be machinery
 * without a purpose here.
 */
class RouteStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "routes").apply { mkdirs() }
    private val index: RouteIndex get() = RouteIndex(dir)

    fun list(): List<RouteMeta> = index.list()

    fun find(id: String): RouteMeta? = index.find(id)

    /** Change a route's display name. The stored file keeps its original name. */
    fun rename(id: String, name: String): RouteMeta? {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A route needs a name" }
        return index.update(id) { it.copy(name = trimmed) }
    }

    fun setFavourite(id: String, favourite: Boolean): RouteMeta? =
        index.update(id) { it.copy(favourite = favourite) }

    /**
     * Copy a picked file in and register it.
     *
     * The file is parsed first so a bad import fails before anything is stored.
     */
    fun import(uri: Uri, nowMs: Long): RouteMeta {
        val displayName = displayName(uri) ?: "route"
        val isBundle = displayName.endsWith(RouteMeta.BUNDLE_SUFFIX, ignoreCase = true)

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("cannot read $displayName")

        val route = parse(bytes, displayName, isBundle)

        val id = UUID.randomUUID().toString()
        val stored = "$id-${sanitise(displayName)}"
        File(dir, stored).writeBytes(bytes)

        val meta = RouteMeta(
            id = id,
            name = route.name,
            fileName = stored,
            importedAtMs = nowMs,
            distanceM = route.distanceM,
            ascentM = route.ascentM,
            hasGuidance = route.hasGuidance,
            maneuverCount = route.maneuvers.size,
        )
        index.add(meta)
        return meta
    }

    /**
     * Replace a route's stored file with a freshly prepared bundle.
     *
     * The id is kept, so any offline tile pack stays attached and the route does
     * not jump to the top of the list for having gained turn cues. The display
     * name is kept too: it may have been renamed since import, and preparation
     * is not a reason to undo that.
     */
    fun replaceBundle(routeId: String, bundleJson: String): RouteMeta? {
        val meta = find(routeId) ?: return null
        val route = BundleReader.parse(bundleJson)

        val stored = "$routeId-${sanitise(route.name)}${RouteMeta.BUNDLE_SUFFIX}"
        File(dir, stored).writeText(bundleJson)
        if (meta.fileName != stored) File(dir, meta.fileName).delete()

        return index.update(routeId) {
            it.copy(
                fileName = stored,
                distanceM = route.distanceM,
                ascentM = route.ascentM,
                hasGuidance = route.hasGuidance,
                maneuverCount = route.maneuvers.size,
            )
        }
    }

    /** Attach a sideloaded offline tile pack to an existing route. */
    fun importTiles(routeId: String, uri: Uri): RouteMeta? {
        val meta = find(routeId) ?: return null
        val stored = "$routeId.mbtiles"
        context.contentResolver.openInputStream(uri)?.use { input ->
            File(dir, stored).outputStream().use { input.copyTo(it) }
        } ?: return null

        return index.update(routeId) { it.copy(tilesFileName = stored) }
    }

    fun load(meta: RouteMeta): Route {
        val file = File(dir, meta.fileName)
        return parse(file.readBytes(), meta.fileName, meta.isBundle)
    }

    fun tilesFile(meta: RouteMeta): File? =
        meta.tilesFileName?.let { File(dir, it) }?.takeIf { it.exists() }

    /**
     * Where a route's tile pack lives, whether or not it exists yet.
     *
     * Stable across runs, which is what lets an interrupted download resume:
     * the partial pack is found again by name.
     */
    fun tilesFileFor(routeId: String): File = File(dir, "$routeId.mbtiles")

    /** Register a pack built in place by [tilesFileFor]. */
    fun attachTilesFile(routeId: String): RouteMeta? {
        if (!tilesFileFor(routeId).exists()) return null
        return index.update(routeId) { it.copy(tilesFileName = "$routeId.mbtiles") }
    }

    /** Forget a route and remove its files, including any tile pack. */
    fun delete(id: String) {
        index.remove(id)?.let { removed ->
            File(dir, removed.fileName).delete()
            removed.tilesFileName?.let { File(dir, it).delete() }
        }
    }

    /**
     * Parse a file, turning library-level failures into something readable.
     *
     * A serialization or XML exception says nothing useful to someone who has
     * just picked the wrong file. Our own validation messages ("need at least 2
     * track points", "bundle version N is newer") already explain themselves and
     * are passed through unchanged.
     */
    private fun parse(bytes: ByteArray, name: String, isBundle: Boolean): Route =
        try {
            if (isBundle) {
                BundleReader.parse(bytes.decodeToString())
            } else {
                GpxImport.parse(bytes.inputStream(), name.substringBeforeLast('.'))
            }
        } catch (e: SerializationException) {
            // Must precede the IllegalArgumentException branch: kotlinx's
            // SerializationException is a subclass of it, so passing that branch
            // through first would leak raw parser output to the user.
            throw IllegalArgumentException(unreadable(name), e)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException(unreadable(name), e)
        }

    private fun unreadable(name: String) =
        "\"$name\" doesn't look like a GPX file or a .navi.json bundle"

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitise(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
