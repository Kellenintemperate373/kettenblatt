package de.kettenblatt.data

import de.kettenblatt.geo.Geo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A route ready to navigate.
 *
 * Built either from a `.navi.json` bundle -- prepared on the phone or by
 * `tools/prep.py`, which
 * carries turn cues, street names and surface -- or straight from a `.gpx`, which
 * carries geometry only. The navigation engine treats the extras as optional, so
 * a plain GPX imported in the field still works, just without a turn banner.
 */
data class Route(
    val name: String,
    val activity: String?,
    val points: List<TrackPoint>,
    val cumDistM: DoubleArray,
    val cumAscentM: DoubleArray,
    val maneuvers: List<Maneuver> = emptyList(),
    /**
     * Cues for riding this route backwards, indexed into the reversed point
     * order. Produced by a second map-matching pass, because
     * a junction met from the other arm needs a different instruction entirely.
     */
    val reverseManeuvers: List<Maneuver> = emptyList(),
    val surfaces: List<SurfaceSpan> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    /** True when this is the imported route ridden backwards. */
    val isReversed: Boolean = false,
) {
    val distanceM: Double get() = cumDistM.lastOrNull() ?: 0.0
    val ascentM: Double get() = cumAscentM.lastOrNull() ?: 0.0
    val hasGuidance: Boolean get() = maneuvers.isNotEmpty()

    /** True when a ride in the other direction would still be guided. */
    val hasReverseGuidance: Boolean get() = reverseManeuvers.isNotEmpty()

    /**
     * The same route ridden the other way.
     *
     * Geometry, distances and surface annotation flip arithmetically. Turn cues
     * cannot: a maneuver says "turn right onto Kaldenkerkerweg", and riding the
     * other way you approach that junction from a different arm, turn a
     * different way, and join a different street. So the two directions carry
     * *separately matched* cue sets, and reversing swaps them.
     *
     * A bundle prepared before this existed has no reverse cues; reversing it
     * still works, just without guidance. Match it again to get them.
     *
     * Ascent is recomputed rather than subtracted, because the climbs of one
     * direction are the descents of the other -- a route that gains 200 m out and
     * loses it coming back does not have 200 m of ascent in reverse.
     */
    fun reversed(): Route {
        val flipped = points.reversed()
        val cumDist = RouteMath.cumulativeDistances(flipped)
        val cumAscent = RouteMath.cumulativeAscent(flipped.map { it.ele }.toDoubleArray())
        val last = points.lastIndex

        return Route(
            name = name,
            activity = activity,
            points = flipped,
            cumDistM = cumDist,
            cumAscentM = cumAscent,
            maneuvers = reverseManeuvers,
            reverseManeuvers = maneuvers,
            surfaces = surfaces
                .map { SurfaceSpan(last - it.to, last - it.from, it.surface, it.roadClass, it.use) }
                .sortedBy { it.from },
            waypoints = waypoints,
            isReversed = !isReversed,
        )
    }

    /** Surface at a track index, or null where the route was not annotated. */
    fun surfaceAt(index: Int): String? =
        surfaces.firstOrNull { index >= it.from && index <= it.to }?.surface

    /**
     * Waypoints placed on the route, ordered by how far along they sit.
     *
     * A Komoot waypoint carries only a coordinate, so its position along the
     * track has to be found once here rather than searched on every fix.
     */
    val waypointsAlongRoute: List<RouteWaypoint> by lazy {
        waypoints.mapNotNull { wp ->
            val index = points.indices.minByOrNull {
                Geo.haversine(wp.lat, wp.lon, points[it].lat, points[it].lon)
            } ?: return@mapNotNull null
            val offset = Geo.haversine(wp.lat, wp.lon, points[index].lat, points[index].lon)
            // A pin dropped far from the line is a place near the route, not a
            // stop on it, and announcing it would be noise.
            if (offset > WAYPOINT_MAX_OFFSET_M) null
            else RouteWaypoint(wp, index, cumDistM.getOrElse(index) { 0.0 })
        }.sortedBy { it.distanceAlongM }
    }

    /** Stretches of unpaved surface, in route order. */
    val unpavedSpans: List<SurfaceSpan> by lazy {
        surfaces.filter { it.isUnpaved }.sortedBy { it.from }
    }

    private companion object {
        /** How far off the line a waypoint may sit and still count as on it. */
        const val WAYPOINT_MAX_OFFSET_M = 150.0
    }

    // Identity is by name; the arrays exist for lookup, not comparison. Data
    // classes with array fields otherwise generate equals/hashCode that compare
    // by reference and surprise everyone.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class TrackPoint(val lat: Double, val lon: Double, val ele: Double)

data class Maneuver(
    val idx: Int,
    val type: String,
    val street: String?,
    val instruction: String,
)

data class SurfaceSpan(
    val from: Int,
    val to: Int,
    val surface: String?,
    val roadClass: String?,
    val use: String?,
) {
    val isUnpaved: Boolean get() = surface in UNPAVED_SURFACES
    val isFerry: Boolean get() = use == "ferry"

    companion object {
        val UNPAVED_SURFACES = setOf("gravel", "dirt", "ground", "sand", "grass", "compacted", "path")
    }
}

data class Waypoint(
    val lat: Double,
    val lon: Double,
    val name: String?,
    val sym: String?,
    val desc: String?,
) {
    /** Best available label; Komoot supplies a name, or at least a symbol. */
    val label: String get() = name ?: sym ?: "Waypoint"
}

/** A waypoint tied to its position along the route. */
data class RouteWaypoint(
    val waypoint: Waypoint,
    val index: Int,
    val distanceAlongM: Double,
) {
    val label: String get() = waypoint.label
}

// --- .navi.json ----------------------------------------------------------

@Serializable
private data class BundleJson(
    val version: Int = 1,
    val name: String,
    val activity: String? = null,
    val distanceM: Double = 0.0,
    val ascentM: Double = 0.0,
    val bbox: List<Double> = emptyList(),
    val points: List<List<Double>> = emptyList(),
    val cumDistM: List<Double> = emptyList(),
    val cumAscentM: List<Double> = emptyList(),
    val maneuvers: List<ManeuverJson> = emptyList(),
    @SerialName("maneuversReverse") val maneuversReverse: List<ManeuverJson> = emptyList(),
    val surfaces: List<SurfaceJson> = emptyList(),
    val waypoints: List<WaypointJson> = emptyList(),
    val matchQuality: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

@Serializable
private data class ManeuverJson(
    val idx: Int,
    val type: String,
    val instruction: String = "",
    val street: String? = null,
)

@Serializable
private data class SurfaceJson(
    val from: Int,
    val to: Int,
    val surface: String? = null,
    @SerialName("roadClass") val roadClass: String? = null,
    val use: String? = null,
)

@Serializable
private data class WaypointJson(
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val sym: String? = null,
    val desc: String? = null,
)

/** Drop anything addressing a point that does not exist, and order by position. */
private fun List<ManeuverJson>.toManeuvers(pointCount: Int): List<Maneuver> =
    filter { it.idx in 0 until pointCount }
        .map { Maneuver(it.idx, it.type, it.street, it.instruction) }
        .sortedBy { it.idx }

object BundleReader {
    /** Newer bundles may add fields; refusing to open them would be unhelpful. */
    private val json = Json { ignoreUnknownKeys = true }

    /** v2 added reverse-direction cues. v1 bundles still load, without them. */
    const val SUPPORTED_VERSION = 2

    fun parse(text: String): Route {
        val b = json.decodeFromString<BundleJson>(text)
        require(b.version <= SUPPORTED_VERSION) {
            "bundle version ${b.version} is newer than this app understands"
        }
        require(b.points.size >= 2) { "bundle has ${b.points.size} points, need at least 2" }

        val points = b.points.map {
            TrackPoint(it[0], it[1], if (it.size > 2) it[2] else 0.0)
        }

        // Recompute rather than trust, if the bundle's parallel arrays are the
        // wrong length -- a truncated transfer should not desync the whole engine.
        val cumDist = if (b.cumDistM.size == points.size) {
            b.cumDistM.toDoubleArray()
        } else {
            RouteMath.cumulativeDistances(points)
        }
        val cumAscent = if (b.cumAscentM.size == points.size) {
            b.cumAscentM.toDoubleArray()
        } else {
            RouteMath.cumulativeAscent(RouteMath.smoothElevation(points.map { it.ele }, cumDist))
        }

        val n = points.size
        return Route(
            name = b.name,
            activity = b.activity,
            points = points,
            cumDistM = cumDist,
            cumAscentM = cumAscent,
            // Drop anything addressing a point that does not exist rather than
            // crashing later during navigation.
            maneuvers = b.maneuvers.toManeuvers(n),
            reverseManeuvers = b.maneuversReverse.toManeuvers(n),
            surfaces = b.surfaces
                .filter { it.from in 0 until n && it.to in 0 until n && it.to >= it.from }
                .map { SurfaceSpan(it.from, it.to, it.surface, it.roadClass, it.use) },
            waypoints = b.waypoints.map { Waypoint(it.lat, it.lon, it.name, it.sym, it.desc) },
        )
    }
}

// --- shared precomputation -----------------------------------------------

/**
 * The same elevation treatment `tools/navi/elevation.py` applies, for routes
 * imported as plain GPX. Constants are kept in step with that module: without
 * both the smoothing window and the threshold, summing raw deltas overstates
 * ascent badly (68 m against ~44 m on the reference route).
 */
object RouteMath {
    const val SMOOTHING_WINDOW_M = 60.0
    const val ASCENT_THRESHOLD_M = 3.0

    fun cumulativeDistances(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            out[i] = out[i - 1] + Geo.haversine(a.lat, a.lon, b.lat, b.lon)
        }
        return out
    }

    fun smoothElevation(
        elevations: List<Double>,
        cumDist: DoubleArray,
        windowM: Double = SMOOTHING_WINDOW_M,
    ): DoubleArray {
        val n = elevations.size
        val out = DoubleArray(n)
        if (n == 0) return out

        val half = windowM / 2
        var lo = 0
        var hi = 0
        var running = 0.0
        for (i in 0 until n) {
            while (hi < n && cumDist[hi] <= cumDist[i] + half) running += elevations[hi++]
            while (cumDist[lo] < cumDist[i] - half) running -= elevations[lo++]
            out[i] = running / (hi - lo)
        }
        return out
    }

    fun cumulativeAscent(
        elevations: DoubleArray,
        thresholdM: Double = ASCENT_THRESHOLD_M,
    ): DoubleArray {
        val out = DoubleArray(elevations.size)
        if (elevations.isEmpty()) return out

        var ascent = 0.0
        var ref = elevations[0]
        for (i in 1 until elevations.size) {
            val e = elevations[i]
            if (e > ref + thresholdM) {
                ascent += e - ref
                ref = e
            } else if (e < ref) {
                ref = e
            }
            out[i] = ascent
        }
        return out
    }
}
