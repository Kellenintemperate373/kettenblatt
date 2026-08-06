package de.kettenblatt.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The list of imported routes, stored as one small JSON file.
 *
 * Kept separate from [RouteStore] so it depends only on a directory rather than
 * an Android Context -- which is what makes renaming, favouriting and ordering
 * testable on the JVM instead of only on a device.
 *
 * Every mutation rewrites the whole file. With a list this size that is simpler
 * and safer than partial updates, and the file is only touched when the rider is
 * managing routes, never while navigating.
 */
class RouteIndex(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(dir, FILE_NAME)

    @Serializable
    private data class Stored(val routes: List<RouteMeta> = emptyList())

    /**
     * Routes in display order: favourites first, then most recently imported.
     *
     * Sorting here rather than in the UI keeps the order identical everywhere,
     * including immediately after a change.
     */
    fun list(): List<RouteMeta> =
        read().sortedWith(compareByDescending<RouteMeta> { it.favourite }.thenByDescending { it.importedAtMs })

    fun find(id: String): RouteMeta? = read().firstOrNull { it.id == id }

    fun add(meta: RouteMeta) {
        write(read() + meta)
    }

    /** Apply [change] to one route, or do nothing if it has since been deleted. */
    fun update(id: String, change: (RouteMeta) -> RouteMeta): RouteMeta? {
        val current = read()
        val existing = current.firstOrNull { it.id == id } ?: return null
        val updated = change(existing)
        write(current.map { if (it.id == id) updated else it })
        return updated
    }

    /** Remove a route from the index and hand back what it referenced. */
    fun remove(id: String): RouteMeta? {
        val current = read()
        val existing = current.firstOrNull { it.id == id } ?: return null
        write(current.filterNot { it.id == id })
        return existing
    }

    private fun read(): List<RouteMeta> =
        runCatching { json.decodeFromString<Stored>(file.readText()).routes }
            .getOrElse { emptyList() }

    private fun write(routes: List<RouteMeta>) {
        dir.mkdirs()
        file.writeText(json.encodeToString(Stored.serializer(), Stored(routes)))
    }

    private companion object {
        const val FILE_NAME = "index.json"
    }
}
