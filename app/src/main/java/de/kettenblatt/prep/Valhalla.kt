package de.kettenblatt.prep

import de.kettenblatt.data.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Client for a Valhalla instance -- the one thing the app cannot do for itself.
 *
 * Mirrors `tools/navi/valhalla.py`, request for request, so a route prepared on
 * the phone is the same bundle `prep.py` writes. Two endpoints matter:
 *
 * * `trace_route` returns route directions for the matched track, which is where
 *   turn maneuvers and street names come from.
 * * `trace_attributes` returns per-edge attribution (surface, road class) plus
 *   `matched_points`, which is 1:1 with the input and is what lets those
 *   attributes be mapped back onto the original Komoot points.
 *
 * The default is the FOSSGIS public instance: a whole-planet graph, no API key,
 * and a fair-use limit of one call a second against the four a route costs.
 */
class Valhalla(
    baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    fun traceRoute(points: List<TrackPoint>, costing: Costing): JsonObject =
        post("trace_route", buildJsonObject {
            put("shape", shapeOf(points))
            // The shape came from Komoot's router, not Valhalla's, so its
            // vertices do not sit on Valhalla's graph nodes and edge_walk
            // cannot apply.
            put("shape_match", "map_snap")
            putTraceOptions()
            putCosting(costing)
        })

    fun traceAttributes(points: List<TrackPoint>, costing: Costing): JsonObject =
        post("trace_attributes", buildJsonObject {
            put("shape", shapeOf(points))
            put("shape_match", "map_snap")
            putTraceOptions()
            putJsonObject("filters") {
                put("action", "include")
                putJsonArray("attributes") {
                    ATTRIBUTE_FILTERS.forEach { add(it) }
                }
            }
            putCosting(costing)
        })

    /** Cheap reachability probe, so a bad base URL fails before a long trace does. */
    fun status(): JsonObject {
        val connection = open("$base/status", post = false)
        return connection.readJson("status")
    }

    private fun post(action: String, payload: JsonObject): JsonObject {
        val connection = open("$base/$action", post = true)
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        } catch (e: IOException) {
            throw ValhallaException(unreachable(e), e)
        }
        return connection.readJson(action)
    }

    private fun open(url: String, post: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            // FOSSGIS asks apps hitting the public instance to identify
            // themselves; on any other host it is harmless.
            setRequestProperty("X-Client-Id", CLIENT_ID)
            setRequestProperty("Accept-Encoding", "gzip")
            if (post) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

    private fun HttpURLConnection.readJson(action: String): JsonObject {
        val code = try {
            responseCode
        } catch (e: IOException) {
            throw ValhallaException(unreachable(e), e)
        }

        val stream = if (code in 200..299) inputStream else errorStream
        val body = stream?.let { raw ->
            val decoded = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            decoded.use { it.readBytes().decodeToString() }
        }.orEmpty()
        disconnect()

        if (code !in 200..299) {
            throw ValhallaException("$action failed ($code): ${errorDetail(body)}")
        }
        return try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw ValhallaException("$action returned something that is not JSON", e)
        }
    }

    /** Valhalla puts a readable message in `error`; anything else is noise. */
    private fun errorDetail(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(200).ifEmpty { "no detail" }

    private fun unreachable(e: IOException) =
        "cannot reach Valhalla at $base (${e.message ?: e::class.simpleName}). " +
            "Check the connection, or the server address in Settings."

    private fun JsonObjectBuilder.putTraceOptions() {
        putJsonObject("trace_options") {
            put("search_radius", SEARCH_RADIUS_M)
            put("gps_accuracy", GPS_ACCURACY_M)
        }
    }

    private fun JsonObjectBuilder.putCosting(costing: Costing) {
        put("costing", costing.name)
        if (costing.options.isNotEmpty()) {
            putJsonObject("costing_options") {
                putJsonObject(costing.name) {
                    costing.options.forEach { (k, v) ->
                        when (v) {
                            is String -> put(k, v)
                            is Boolean -> put(k, v)
                            is Number -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                }
            }
        }
    }

    private fun shapeOf(points: List<TrackPoint>) = buildJsonArray {
        points.forEach { p ->
            add(buildJsonObject {
                put("lat", round6(p.lat))
                put("lon", round6(p.lon))
            })
        }
    }

    companion object {
        /**
         * FOSSGIS's public instance: whole planet, no key, refreshed daily.
         *
         * Fair use is one call per second; preparing a route costs four.
         */
        const val DEFAULT_BASE_URL = "https://valhalla1.openstreetmap.de"
        const val CLIENT_ID = "kettenblatt"
        const val DEFAULT_TIMEOUT_MS = 180_000

        /**
         * The candidate radius the matcher considers around each input point,
         * and the single setting that decides whether this works at all.
         *
         * Komoot decimates its routes -- the reference route has gaps of up to
         * 517 m between consecutive points. Below ~75 m the matcher cannot find
         * candidate edges across those gaps, so the router bridges them by
         * picking its own cheaper path and silently returns a route 15% SHORTER
         * than the input, with no error raised. 100 is also Valhalla's maximum,
         * so there is no headroom: [MatchQuality.isUsable] exists to catch the
         * failure if a sparser route ever hits it.
         */
        const val SEARCH_RADIUS_M = 100
        const val GPS_ACCURACY_M = 10

        private val ATTRIBUTE_FILTERS = listOf(
            "edge.names",
            "edge.surface",
            "edge.road_class",
            "edge.use",
            "edge.length",
            "edge.begin_shape_index",
            "edge.end_shape_index",
            "matched.point",
            "matched.type",
            "matched.edge_index",
            "matched.distance_from_trace_point",
            "shape",
        )

        private fun round6(v: Double) = Math.round(v * 1_000_000.0) / 1_000_000.0
    }
}

class ValhallaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A Valhalla costing model plus its options. */
data class Costing(val name: String, val options: Map<String, Any> = emptyMap()) {
    companion object {
        /**
         * Pick a costing model from Komoot's activity type.
         *
         * The costing must be permissive enough to traverse whatever Komoot
         * routed over, otherwise matching fails on exactly the tracks and paths
         * that make a route interesting.
         */
        fun forActivity(activity: String?): Costing {
            val a = activity.orEmpty().lowercase()
            if (PEDESTRIAN_HINTS.any { it in a }) return PEDESTRIAN

            val bicycleType = BICYCLE_TYPES.firstOrNull { (hint, _) -> hint in a }?.second ?: "Hybrid"
            return Costing(
                "bicycle",
                mapOf(
                    "bicycle_type" to bicycleType,
                    // Komoot happily routes over unpaved tracks and paths. Left
                    // at the defaults, bicycle costing avoids them strongly
                    // enough that map matching drifts onto a parallel road.
                    "use_roads" to 0.5,
                    "use_hills" to 0.5,
                    "avoid_bad_surfaces" to 0.0,
                ),
            )
        }

        /**
         * The costing to fall back to when the preferred one matches badly.
         *
         * Pedestrian costing traverses almost anything, so it recovers tracks
         * that bicycle costing refuses -- at the cost of less apt turn phrasing.
         */
        val PEDESTRIAN = Costing("pedestrian", mapOf("shortest" to false))

        // Komoot sport identifiers appear in <trk><type>. Substring matching
        // keeps this robust against the e_/_easy/_advanced variants Komoot
        // layers on.
        private val PEDESTRIAN_HINTS = listOf(
            "hike", "jogging", "walk", "mountaineering", "climbing", "nordic", "snowshoe",
        )
        private val BICYCLE_TYPES = listOf(
            "mtb" to "Mountain",
            "downhill" to "Mountain",
            "gravel" to "Hybrid",
            "racebike" to "Road",
            "road_bike" to "Road",
            "citybike" to "City",
            "touring" to "Hybrid",
        )
    }
}

/**
 * Decode an encoded polyline into (lat, lon) pairs.
 *
 * Valhalla encodes at precision 6, unlike the precision-5 polylines most other
 * services use. Decoding at the wrong precision silently yields coordinates off
 * by a factor of ten -- which lands the whole route in the Gulf of Guinea rather
 * than raising anything.
 */
fun decodePolyline(encoded: String, precision: Int = POLYLINE_PRECISION): List<Pair<Double, Double>> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<Pair<Double, Double>>()
    var lat = 0L
    var lon = 0L
    var i = 0

    while (i < encoded.length) {
        var deltaLat = 0L
        var deltaLon = 0L
        for (target in 0..1) {
            var shift = 0
            var result = 0L
            while (true) {
                if (i >= encoded.length) throw ValhallaException("truncated polyline")
                val b = encoded[i].code - 63
                i++
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            val delta = if (result and 1L == 1L) (result shr 1).inv() else (result shr 1)
            if (target == 0) deltaLat = delta else deltaLon = delta
        }
        lat += deltaLat
        lon += deltaLon
        out.add(lat / factor to lon / factor)
    }
    return out
}

const val POLYLINE_PRECISION = 6
