package de.kettenblatt.prep

import de.kettenblatt.data.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.round

/**
 * Writes the `.navi.json` bundle, the mirror of `data/Route.kt`'s reader.
 *
 * Rounding is deliberate: coordinates to 6
 * decimal places (~0.1 m) and distances to 0.1 m. Full float repr roughly
 * doubles the file for precision no GPS can use, and the file still gets
 * written to a phone's storage on every prepare.
 */
object BundleWriter {

    /** v2 adds `maneuversReverse`; [de.kettenblatt.data.BundleReader] reads v1 too. */
    const val BUNDLE_VERSION = 2

    private const val COORD_DP = 6
    private const val ELEVATION_DP = 1
    private const val DISTANCE_DP = 1

    private val json = Json

    fun write(
        route: Route,
        quality: MatchQuality? = null,
        warnings: List<String> = emptyList(),
    ): String = json.encodeToString(JsonObject.serializer(), build(route, quality, warnings))

    fun build(
        route: Route,
        quality: MatchQuality? = null,
        warnings: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("version", BUNDLE_VERSION)
        put("name", route.name)
        put("activity", route.activity?.let { JsonPrimitive(it) } ?: JsonNull)
        put("distanceM", round(route.distanceM, DISTANCE_DP))
        put("ascentM", round(route.ascentM, DISTANCE_DP))
        put("bbox", bbox(route))

        putJsonArray("points") {
            route.points.forEach { p ->
                addJsonArray {
                    add(round(p.lat, COORD_DP))
                    add(round(p.lon, COORD_DP))
                    add(round(p.ele, ELEVATION_DP))
                }
            }
        }
        put("cumDistM", doubles(route.cumDistM, DISTANCE_DP))
        put("cumAscentM", doubles(route.cumAscentM, DISTANCE_DP))

        put("maneuvers", maneuvers(route.maneuvers))
        put("maneuversReverse", maneuvers(route.reverseManeuvers))

        putJsonArray("surfaces") {
            route.surfaces.forEach { s ->
                add(buildJsonObject {
                    put("from", s.from)
                    put("to", s.to)
                    s.surface?.let { put("surface", it) }
                    s.roadClass?.let { put("roadClass", it) }
                    s.use?.let { put("use", it) }
                })
            }
        }

        putJsonArray("waypoints") {
            route.waypoints.forEach { w ->
                add(buildJsonObject {
                    put("lat", round(w.lat, COORD_DP))
                    put("lon", round(w.lon, COORD_DP))
                    w.name?.let { put("name", it) }
                    w.sym?.let { put("sym", it) }
                    w.desc?.let { put("desc", it) }
                })
            }
        }

        quality?.let {
            putJsonObject("matchQuality") {
                put("costing", it.costing)
                put("total", it.totalPoints)
                put("matched", it.matched)
                put("interpolated", it.interpolated)
                put("unmatched", it.unmatched)
                put("meanOffsetM", round(it.meanOffsetM, 2))
                put("maxOffsetM", round(it.maxOffsetM, 2))
                put("lengthDeviation", round(it.lengthDeviation, 4))
            }
        }
        if (warnings.isNotEmpty()) {
            putJsonArray("warnings") { warnings.forEach { add(it) } }
        }
    }

    private fun maneuvers(list: List<de.kettenblatt.data.Maneuver>) = buildJsonArray {
        list.forEach { m ->
            add(buildJsonObject {
                put("idx", m.idx)
                put("type", m.type)
                put("instruction", m.instruction)
                m.street?.let { put("street", it) }
            })
        }
    }

    private fun bbox(route: Route): JsonArray {
        val lats = route.points.map { it.lat }
        val lons = route.points.map { it.lon }
        return buildJsonArray {
            add(round(lats.min(), COORD_DP))
            add(round(lons.min(), COORD_DP))
            add(round(lats.max(), COORD_DP))
            add(round(lons.max(), COORD_DP))
        }
    }

    private fun doubles(values: DoubleArray, dp: Int) = buildJsonArray {
        values.forEach { add(round(it, dp)) }
    }

    private fun round(value: Double, dp: Int): Double {
        val factor = Math.pow(10.0, dp.toDouble())
        return round(value * factor) / factor
    }
}
