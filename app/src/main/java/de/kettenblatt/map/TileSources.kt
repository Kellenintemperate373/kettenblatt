package de.kettenblatt.map

import android.content.Context
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File

/**
 * Map tile sources, online and offline.
 *
 * Note what is deliberately *absent*: any in-app bulk download. osmdroid's
 * Mapnik source carries `FLAG_NO_BULK`, so constructing a `CacheManager`
 * against it throws -- osmdroid enforcing the OSM Foundation's tile policy. Tile
 * packs are built by `prep/TilePack.kt` instead, from a
 * source that permits it. Browsing the map online still caches normally.
 */
object TileSources {

    /**
     * The name embedded in a generated `.mbtiles`.
     *
     * MBTiles has no field for a tile source name, so `IArchiveFile.getTileSources()`
     * comes back empty and osmdroid cannot discover it. The name therefore has to
     * be supplied here, and osmdroid falls back to the archive's only table
     * regardless of what it is called.
     */
    private const val OFFLINE_SOURCE_NAME = "kettenblatt-offline"

    fun online(): ITileSource = TileSourceFactory.MAPNIK

    private fun offlineSource(minZoom: Int, maxZoom: Int): ITileSource =
        XYTileSource(OFFLINE_SOURCE_NAME, minZoom, maxZoom, 256, ".png", emptyArray())

    /**
     * A provider backed by a sideloaded tile pack, or null if it cannot be read.
     *
     * Returning null rather than throwing lets the caller fall back to online
     * tiles: a corrupt pack should degrade the map, not prevent navigating.
     */
    fun offline(context: Context, mbtiles: File): Pair<MapTileProviderBase, ITileSource>? =
        runCatching {
            val provider = OfflineTileProvider(
                SimpleRegisterReceiver(context),
                arrayOf(mbtiles),
            )
            val bounds = MbtilesMeta.read(mbtiles)
            provider to offlineSource(bounds.minZoom, bounds.maxZoom)
        }.getOrNull()
}

/** The zoom range a tile pack actually contains, read from its metadata table. */
data class MbtilesMeta(val minZoom: Int, val maxZoom: Int) {
    companion object {
        fun read(file: File): MbtilesMeta = runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                    var min = 0
                    var max = 20
                    while (c.moveToNext()) {
                        when (c.getString(0)) {
                            "minzoom" -> min = c.getString(1).toIntOrNull() ?: min
                            "maxzoom" -> max = c.getString(1).toIntOrNull() ?: max
                        }
                    }
                    MbtilesMeta(min, max)
                }
            }
        }.getOrElse { MbtilesMeta(0, 20) }
    }
}
