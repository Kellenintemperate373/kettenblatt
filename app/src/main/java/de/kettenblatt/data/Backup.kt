package de.kettenblatt.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What a backup holds, and what a restore did.
 *
 * There is no account behind this app, so a backup is the only thing standing
 * between a lost phone and a lost route library.
 */
@Serializable
data class BackupManifest(
    val format: Int = FORMAT,
    val createdAtMs: Long,
    val routes: Int,
    val rides: Int,
) {
    companion object {
        /** Bumped only for a change a previous version could not read. */
        const val FORMAT = 1
    }
}

data class RestoreSummary(
    val routesAdded: Int,
    val routesSkipped: Int,
    val ridesAdded: Int,
    val ridesSkipped: Int,
    val settingsApplied: Boolean,
) {
    val addedAnything: Boolean get() = routesAdded > 0 || ridesAdded > 0
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Routes, rides and settings in a zip.
 *
 * Offline map packs are deliberately left out. They are the only thing here that
 * can be recreated from nothing -- a tap and a minute of wifi -- and at ~9 MB per
 * route they would be almost the entire file, turning a backup worth mailing to
 * yourself into one that is awkward to store anywhere.
 *
 * Takes directories rather than a Context, following [RouteIndex] and
 * [RideStore], so the whole round trip is testable on the JVM.
 */
object Backup {

    private const val MANIFEST = "manifest.json"
    private const val INDEX = "routes/index.json"
    private const val ROUTE_FILES = "routes/files/"
    private const val RIDES = "rides/"
    private const val SETTINGS = "settings.json"

    // encodeDefaults, so the manifest states its format rather than relying on a
    // reader defaulting it. A backup should be self-describing years from now.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    // --- writing ----------------------------------------------------------

    fun write(
        routesDir: File,
        ridesDir: File,
        settings: Map<String, String>,
        nowMs: Long,
        out: OutputStream,
    ): BackupManifest {
        val routes = RouteIndex(routesDir).list()
        val rideFiles = ridesDir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()

        val manifest = BackupManifest(
            createdAtMs = nowMs,
            routes = routes.size,
            rides = rideFiles.size,
        )

        ZipOutputStream(out.buffered()).use { zip ->
            zip.put(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())

            // The index carries names, favourites and import order -- the part
            // of a library that is not in the route files themselves.
            zip.put(INDEX, json.encodeToString(RouteMetaList.serializer(), RouteMetaList(routes)).toByteArray())
            routes.forEach { meta ->
                val file = File(routesDir, meta.fileName)
                if (file.exists()) zip.put(ROUTE_FILES + meta.fileName, file.readBytes())
            }

            rideFiles.forEach { zip.put(RIDES + it.name, it.readBytes()) }
            zip.put(SETTINGS, json.encodeToString(SettingsMap.serializer(), SettingsMap(settings)).toByteArray())
        }
        return manifest
    }

    /** A filename that sorts by date and survives any filesystem. */
    fun suggestedFileName(nowMs: Long): String {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(nowMs))
        return "kettenblatt-backup-$day.zip"
    }

    // --- reading ----------------------------------------------------------

    /**
     * Merge a backup into whatever is already on the phone.
     *
     * Existing routes and rides are left alone: the same button is the only one
     * anybody will find in a hurry, so it must never be the one that destroys
     * something. Restoring twice is therefore harmless.
     */
    fun restore(
        input: InputStream,
        routesDir: File,
        ridesDir: File,
        currentSettings: Map<String, String>,
        applySettings: (Map<String, String>) -> Unit,
    ): RestoreSummary {
        routesDir.mkdirs()
        ridesDir.mkdirs()

        var manifest: BackupManifest? = null
        var metas: List<RouteMeta> = emptyList()
        val routeFiles = HashMap<String, ByteArray>()
        val rides = HashMap<String, ByteArray>()
        var settings: Map<String, String>? = null

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val bytes = zip.readBytes()
                when {
                    entry.name == MANIFEST ->
                        manifest = runCatching {
                            json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
                        }.getOrNull()

                    entry.name == INDEX ->
                        metas = runCatching {
                            json.decodeFromString(RouteMetaList.serializer(), bytes.decodeToString()).routes
                        }.getOrDefault(emptyList())

                    entry.name == SETTINGS ->
                        settings = runCatching {
                            json.decodeFromString(SettingsMap.serializer(), bytes.decodeToString()).values
                        }.getOrNull()

                    entry.name.startsWith(ROUTE_FILES) ->
                        safeName(entry.name.removePrefix(ROUTE_FILES))?.let { routeFiles[it] = bytes }

                    entry.name.startsWith(RIDES) ->
                        safeName(entry.name.removePrefix(RIDES))?.let { rides[it] = bytes }
                }
            }
        }

        val found = manifest ?: throw BackupException(
            "That does not look like a Kettenblatt backup — no manifest inside."
        )
        if (found.format > BackupManifest.FORMAT) {
            throw BackupException(
                "That backup was written by a newer version of Kettenblatt (format ${found.format})."
            )
        }

        val index = RouteIndex(routesDir)
        var routesAdded = 0
        var routesSkipped = 0
        metas.forEach { meta ->
            val bytes = routeFiles[meta.fileName]
            when {
                bytes == null -> routesSkipped++          // index entry with no file behind it
                index.find(meta.id) != null -> routesSkipped++
                else -> {
                    File(routesDir, meta.fileName).writeBytes(bytes)
                    // Map packs are not in a backup, so no restored route may
                    // claim to have one.
                    index.add(meta.copy(tilesFileName = null))
                    routesAdded++
                }
            }
        }

        var ridesAdded = 0
        var ridesSkipped = 0
        rides.forEach { (name, bytes) ->
            val target = File(ridesDir, name)
            if (target.exists()) ridesSkipped++ else {
                target.writeBytes(bytes)
                ridesAdded++
            }
        }

        // Settings follow the same rule as everything else: only fill a gap.
        // Restoring onto a fresh phone brings your preferences back; importing
        // somebody else's library does not silently rewrite yours.
        val defaults = SettingsCodec.encode(Settings())
        val untouched = currentSettings.all { (k, v) -> defaults[k] == v }
        val settingsApplied = settings != null && untouched
        if (settingsApplied) applySettings(settings!!)

        return RestoreSummary(routesAdded, routesSkipped, ridesAdded, ridesSkipped, settingsApplied)
    }

    /**
     * A zip entry may name any path it likes, including `../../`, and an
     * extractor that trusts it writes wherever the archive says. Only a plain
     * file name is ever accepted here.
     */
    private fun safeName(name: String): String? =
        name.takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains('\\') && it != ".." }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}

/** Wrappers, so the JSON inside a backup is an object rather than a bare array. */
@Serializable
private data class RouteMetaList(val routes: List<RouteMeta>)

@Serializable
private data class SettingsMap(val values: Map<String, String>)
