package de.kettenblatt.prep

import android.database.sqlite.SQLiteDatabase
import de.kettenblatt.data.TrackPoint
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** A raster tile source that permits caching. */
data class TileSource(
    val key: String,
    val name: String,
    val urlTemplate: String,
    val maxZoom: Int,
    val attribution: String,
    val needsKey: Boolean = false,
    val subdomains: List<String> = emptyList(),
) {
    fun url(z: Int, x: Int, y: Int, apiKey: String?): String = urlTemplate
        .replace("{s}", if (subdomains.isEmpty()) "" else subdomains[(x + y) % subdomains.size])
        .replace("{z}", z.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())
        .replace("{key}", apiKey.orEmpty())

    companion object {
        val ALL = listOf(
            // Topographic styling, contours and paths -- well suited to hiking,
            // and no signup. Their servers are volunteer-run, so downloads are
            // kept to actual route corridors.
            TileSource(
                key = "opentopomap",
                name = "OpenTopoMap",
                urlTemplate = "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
                maxZoom = 17,
                attribution = "Map data (c) OpenStreetMap contributors, SRTM | " +
                    "Style (c) OpenTopoMap (CC-BY-SA)",
                subdomains = listOf("a", "b", "c"),
            ),
            // Purpose-built cycling and outdoor styles. The free tier explicitly
            // permits caching, which makes this the right choice if you prepare
            // routes often.
            TileSource(
                key = "thunderforest-outdoors",
                name = "Thunderforest Outdoors",
                urlTemplate = "https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}",
                maxZoom = 22,
                attribution = "Maps (c) Thunderforest, Data (c) OpenStreetMap contributors",
                needsKey = true,
            ),
            TileSource(
                key = "thunderforest-cycle",
                name = "Thunderforest OpenCycleMap",
                urlTemplate = "https://tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey={key}",
                maxZoom = 22,
                attribution = "Maps (c) Thunderforest, Data (c) OpenStreetMap contributors",
                needsKey = true,
            ),
        )

        fun byKey(key: String): TileSource = ALL.firstOrNull { it.key == key } ?: ALL.first()
    }
}

/** What a download will cost, so it can be shown before it starts. */
data class TilePlan(
    val tiles: List<Triple<Int, Int, Int>>,
    val shape: String,
    val zoomMin: Int,
    val zoomMax: Int,
    val source: TileSource,
) {
    /** Measured against OpenTopoMap over the reference route: 27-48 KB per tile. */
    val estimatedBytes: Long get() = tiles.size * 35L * 1024
}

data class TileProgress(val done: Int, val total: Int, val unavailable: Int)

/**
 * Builds an offline map pack on the phone, the job `tools/navi/tiles.py` does.
 *
 * The output is the same MBTiles file the app already reads, so a pack built
 * here and one sideloaded from the Mac are indistinguishable.
 */
object TilePack {

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val USER_AGENT =
        "Kettenblatt/1.0 (personal route preparation; github.com/nils-fl/kettenblatt)"

    /** 429 and 5xx are worth waiting out; a volunteer tile server deserves it. */
    private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 8_000)

    /** Slippy-map (XYZ) tile containing a coordinate. */
    fun deg2tile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = ((1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * n).toInt()
        return x.coerceIn(0, n - 1) to y.coerceIn(0, n - 1)
    }

    fun tileWidthM(lat: Double, zoom: Int): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / (1 shl zoom)

    /** Tiles within [bufferM] of the track at a given zoom. */
    fun corridorTiles(points: List<TrackPoint>, zoom: Int, bufferM: Double): Set<Pair<Int, Int>> {
        if (points.isEmpty()) return emptySet()
        val meanLat = points.sumOf { it.lat } / points.size
        val pad = max(0, ceil(bufferM / tileWidthM(meanLat, zoom)).toInt())
        val n = 1 shl zoom

        val out = HashSet<Pair<Int, Int>>()
        points.forEach { p ->
            val (cx, cy) = deg2tile(p.lat, p.lon, zoom)
            for (dx in -pad..pad) {
                for (dy in -pad..pad) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until n && y in 0 until n) out.add(x to y)
                }
            }
        }
        return out
    }

    /** Every tile in the track's bounding box at a given zoom. */
    fun bboxTiles(points: List<TrackPoint>, zoom: Int): Set<Pair<Int, Int>> {
        if (points.isEmpty()) return emptySet()
        val (x0, y0) = deg2tile(points.maxOf { it.lat }, points.minOf { it.lon }, zoom)
        val (x1, y1) = deg2tile(points.minOf { it.lat }, points.maxOf { it.lon }, zoom)

        val out = HashSet<Pair<Int, Int>>()
        for (x in min(x0, x1)..max(x0, x1)) {
            for (y in min(y0, y1)..max(y0, y1)) out.add(x to y)
        }
        return out
    }

    /**
     * The cheaper of the corridor and the bounding box, with which was chosen.
     *
     * Falling back to the box when it is smaller is not just a saving: on a
     * route that folds back on itself the box also fills in the interior, so the
     * rider gets map for the ground between the legs as well.
     */
    fun tilesFor(
        points: List<TrackPoint>,
        zoom: Int,
        bufferM: Double,
    ): Pair<Set<Pair<Int, Int>>, String> {
        val corridor = corridorTiles(points, zoom, bufferM)
        val box = bboxTiles(points, zoom)
        return if (corridor.size <= box.size) corridor to "corridor" else box to "bbox"
    }

    fun plan(
        points: List<TrackPoint>,
        source: TileSource,
        zoomMin: Int,
        zoomMax: Int,
        bufferM: Double,
    ): TilePlan {
        val top = minOf(zoomMax, source.maxZoom)
        require(zoomMin <= top) { "empty zoom range $zoomMin-$top" }

        val tiles = ArrayList<Triple<Int, Int, Int>>()
        val shapes = sortedSetOf<String>()
        for (z in zoomMin..top) {
            val (chosen, shape) = tilesFor(points, z, bufferM)
            shapes.add(shape)
            chosen.sortedWith(compareBy({ it.first }, { it.second }))
                .forEach { (x, y) -> tiles.add(Triple(z, x, y)) }
        }
        return TilePlan(tiles, shapes.joinToString("/"), zoomMin, top, source)
    }

    /**
     * Download a plan into [out], resuming whatever is already there.
     *
     * Downloads take minutes and get interrupted -- a call comes in, the screen
     * locks, the rider changes their mind. Whatever arrived stays, as long as it
     * came from the same source: mixing two styles into one pack gives a map
     * that changes appearance as you ride across it.
     *
     * [shouldContinue] is checked between tiles so cancelling is immediate.
     */
    fun download(
        plan: TilePlan,
        out: File,
        routeName: String,
        bbox: List<Double>,
        apiKey: String? = null,
        shouldContinue: () -> Boolean = { true },
        onProgress: (TileProgress) -> Unit = {},
    ): File {
        out.parentFile?.mkdirs()

        val existing = existingTiles(out, plan.source)
        if (existing == null && out.exists()) out.delete()
        val remaining = if (existing.isNullOrEmpty()) {
            plan.tiles
        } else {
            plan.tiles.filterNot { it in existing }
        }

        val db = SQLiteDatabase.openOrCreateDatabase(out, null)
        try {
            if (existing == null) initialise(db, routeName, plan, bbox)
            if (remaining.isEmpty()) {
                onProgress(TileProgress(plan.tiles.size, plan.tiles.size, 0))
                return out
            }

            var done = plan.tiles.size - remaining.size
            var unavailable = 0
            db.beginTransaction()
            try {
                for ((z, x, y) in remaining) {
                    if (!shouldContinue()) break
                    val data = fetch(plan.source.url(z, x, y, apiKey))
                    if (data == null) {
                        unavailable++
                    } else {
                        // MBTiles addresses rows in TMS order, origin at the
                        // bottom; slippy-map y counts from the top. Omitting
                        // this flip gives a file full of perfectly good tiles
                        // that renders as a blank map.
                        val tmsY = (1 shl z) - 1 - y
                        db.execSQL(
                            "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)",
                            arrayOf(z, x, tmsY, data),
                        )
                        done++
                    }
                    // Commit periodically so an interruption keeps most of it.
                    if ((done + unavailable) % 100 == 0) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        db.beginTransaction()
                        onProgress(TileProgress(done, plan.tiles.size, unavailable))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            onProgress(TileProgress(done, plan.tiles.size, unavailable))
        } finally {
            db.close()
        }
        return out
    }

    private fun initialise(db: SQLiteDatabase, name: String, plan: TilePlan, bbox: List<Double>) {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name text, value text)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tiles (" +
                "zoom_level integer, tile_column integer, tile_row integer, tile_data blob)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS tile_index " +
                "ON tiles (zoom_level, tile_column, tile_row)"
        )

        val bounds = if (bbox.size == 4) {
            "${bbox[1]},${bbox[0]},${bbox[3]},${bbox[2]}"
        } else {
            "-180,-85,180,85"
        }
        listOf(
            "name" to name,
            "format" to "png",
            "type" to "baselayer",
            "version" to "1.0",
            "description" to "Offline corridor for $name",
            "attribution" to plan.source.attribution,
            "bounds" to bounds,
            "minzoom" to plan.zoomMin.toString(),
            "maxzoom" to plan.zoomMax.toString(),
        ).forEach { (k, v) ->
            db.execSQL("INSERT INTO metadata (name, value) VALUES (?, ?)", arrayOf(k, v))
        }
    }

    /**
     * Tiles already in an existing pack, or null if it cannot be reused.
     *
     * Returns slippy-map (z, x, y), undoing the stored TMS row order so the
     * caller can compare against what it wants to download.
     */
    private fun existingTiles(
        out: File,
        source: TileSource,
    ): Set<Triple<Int, Int, Int>>? {
        if (!out.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val attribution = db.rawQuery(
                    "SELECT value FROM metadata WHERE name = ?", arrayOf("attribution"),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
                if (attribution != source.attribution) return null

                val out2 = HashSet<Triple<Int, Int, Int>>()
                db.rawQuery("SELECT zoom_level, tile_column, tile_row FROM tiles", null).use { c ->
                    while (c.moveToNext()) {
                        val z = c.getInt(0)
                        out2.add(Triple(z, c.getInt(1), (1 shl z) - 1 - c.getInt(2)))
                    }
                }
                out2
            }
        }.getOrNull() // Half-written, or not an MBTiles file at all; start over.
    }

    /** Download one tile. Returns null for a tile the server does not have. */
    private fun fetch(url: String): ByteArray? {
        var attempt = 0
        while (true) {
            if (attempt > 0) Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
            val last = attempt == RETRY_DELAYS_MS.size

            val status = try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    // osmdroid taught this project that a default user agent is
                    // silently rejected; the same policy applies here.
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val code = connection.responseCode
                when {
                    code == 200 -> return connection.inputStream.use { it.readBytes() }
                    code == 404 || code == 204 -> return null
                    else -> {
                        connection.disconnect()
                        code
                    }
                }
            } catch (e: IOException) {
                if (last) throw e
                attempt++
                continue
            }

            if (status !in RETRYABLE || last) {
                throw IOException("tile fetch failed ($status) for $url")
            }
            attempt++
        }
    }

    private val RETRYABLE = setOf(429, 500, 502, 503, 504)
}
