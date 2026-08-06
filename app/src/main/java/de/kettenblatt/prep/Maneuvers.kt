package de.kettenblatt.prep

import de.kettenblatt.data.Maneuver
import de.kettenblatt.data.SurfaceSpan
import de.kettenblatt.data.TrackPoint
import de.kettenblatt.geo.nearestPointIndex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Turns Valhalla's map-matching output into annotations on the original track.
 *
 * Everything here maps *back onto the imported track's own points*: the original geometry stays authoritative for navigation, so a poor
 * match costs street names rather than corrupting the line being followed.
 *
 * The mapping is not a plain nearest-neighbour lookup. The reference route is a
 * loop that revisits 47 coordinates, so a maneuver on the return leg sits right
 * on top of the outbound leg and nearest-neighbour would bind it to the wrong
 * one. Both [mapManeuvers] and [attributeSpans] advance a cursor and never look
 * backwards.
 */
object Maneuvers {

    // Valhalla maneuver type enum, confirmed against a live response rather than
    // taken from memory. Only genuine decision points are kept.
    private val START_TYPES = setOf(1, 2, 3)
    private val DESTINATION_TYPES = setOf(4, 5, 6)

    val MANEUVER_NAMES = mapOf(
        9 to "slight_right",
        10 to "turn_right",
        11 to "sharp_right",
        12 to "uturn_right",
        13 to "uturn_left",
        14 to "sharp_left",
        15 to "turn_left",
        16 to "slight_left",
        17 to "ramp_straight",
        18 to "ramp_right",
        19 to "ramp_left",
        20 to "exit_right",
        21 to "exit_left",
        22 to "keep_straight",
        23 to "keep_right",
        24 to "keep_left",
        25 to "merge",
        26 to "roundabout",
        28 to "ferry",
        38 to "merge_right",
        39 to "merge_left",
        41 to "steps",
    )

    // Deliberately excluded, with reasons:
    //   7  becomes         - road changes name, no action required
    //   8  continue        - explicitly "carry on", the opposite of a decision point
    //   27 roundabout exit - the enter maneuver already says which exit to take
    //   29 ferry exit      - informational; the enter maneuver is the actionable one
    val IGNORED_TYPES = setOf(0, 7, 8, 27, 29) + START_TYPES + DESTINATION_TYPES

    /**
     * How far the search for a maneuver's track index may roam from where the
     * route's own distance measurement puts it. Generous enough to absorb the
     * difference between matched and original length, tight enough that it
     * cannot reach a coincident point on another leg of a loop.
     */
    const val SEARCH_TOLERANCE_M = 400.0

    /**
     * A trace_route whose length differs from the original by more than this is
     * not following the same path, and its maneuvers describe a route the rider
     * will not take.
     */
    const val MAX_LENGTH_DEVIATION = 0.05

    /**
     * Bind Valhalla maneuvers to indices in the original track.
     *
     * Valhalla's `begin_shape_index` addresses its own matched geometry, which
     * is denser than the input (823 points against 606 on the reference route),
     * so the index cannot be used directly. Each maneuver is instead located
     * geographically, restricted to a window around where the route's own
     * running distance says it should be, and never allowed to move backwards.
     */
    fun mapManeuvers(
        traceRoute: JsonObject,
        points: List<TrackPoint>,
        cumDistM: DoubleArray,
    ): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        var cursor = 0
        var travelledM = 0.0

        for ((m, shape) in legManeuvers(traceRoute)) {
            val type = m.int("type") ?: 0
            val begin = m.int("begin_shape_index") ?: 0
            // Length is consumed for every maneuver, including skipped ones, so
            // the running distance stays aligned with the route.
            val segLengthM = (m.double("length") ?: 0.0) * 1000.0

            val name = MANEUVER_NAMES[type]
            if (type in IGNORED_TYPES || name == null || begin !in shape.indices) {
                travelledM += segLengthM
                continue
            }

            val (lat, lon) = shape[begin]
            var lo = lowerBound(cumDistM, travelledM - SEARCH_TOLERANCE_M)
            val hi = upperBound(cumDistM, travelledM + SEARCH_TOLERANCE_M)
            lo = maxOf(lo, cursor)

            val index = nearestPointIndex(
                points = points,
                lat = lat,
                lon = lon,
                start = lo,
                end = maxOf(hi, lo + 1),
                latOf = { it.lat },
                lonOf = { it.lon },
            ).index
            cursor = index

            val streets = m.strings("street_names").ifEmpty { m.strings("begin_street_names") }
            out.add(
                Maneuver(
                    idx = index,
                    type = name,
                    street = streets.firstOrNull(),
                    instruction = (m.string("instruction") ?: "").trim(),
                )
            )
            travelledM += segLengthM
        }
        return out
    }

    /**
     * Group the track into runs of constant road attribution.
     *
     * `matched_points` is 1:1 with the trace that was *sent*, so this mapping
     * needs no geometric search at all -- each sent point already carries its
     * edge index. [sentToOriginal] translates those positions back to the full
     * track when a long recording was decimated before matching.
     */
    fun attributeSpans(
        traceAttributes: JsonObject,
        pointCount: Int,
        sentToOriginal: (Int) -> Int = { it },
        sentCount: Int = pointCount,
    ): List<SurfaceSpan> {
        val edges = traceAttributes["edges"]?.jsonArray
        val matched = traceAttributes["matched_points"]?.jsonArray

        fun attrsAt(i: Int): Triple<String?, String?, String?> {
            val point = matched?.getOrNull(i)?.jsonObject ?: return NO_ATTRS
            val edgeIndex = point.int("edge_index") ?: return NO_ATTRS
            val edge = edges?.getOrNull(edgeIndex)?.jsonObject ?: return NO_ATTRS
            return Triple(edge.string("surface"), edge.string("road_class"), edge.string("use"))
        }

        val spans = ArrayList<SurfaceSpan>()
        var start = 0
        var current = attrsAt(0)
        for (i in 1 until sentCount) {
            val a = attrsAt(i)
            if (a != current) {
                if (current.any()) spans.add(span(start, i - 1, current, sentToOriginal, pointCount))
                start = i
                current = a
            }
        }
        if (current.any()) spans.add(span(start, sentCount - 1, current, sentToOriginal, pointCount))
        return spans
    }

    fun assessMatch(
        traceAttributes: JsonObject,
        traceRoute: JsonObject,
        costingName: String,
        originalLengthM: Double,
        pointCount: Int,
    ): MatchQuality {
        val matched = traceAttributes["matched_points"]?.jsonArray.orEmpty()
        val counts = HashMap<String, Int>()
        val offsets = ArrayList<Double>()
        matched.forEach { element ->
            val point = element.jsonObject
            val type = point.string("type") ?: "unmatched"
            counts[type] = (counts[type] ?: 0) + 1
            if (type != "unmatched") {
                offsets.add(point.double("distance_from_trace_point") ?: 0.0)
            }
        }

        return MatchQuality(
            costing = costingName,
            totalPoints = pointCount,
            matched = counts["matched"] ?: 0,
            interpolated = counts["interpolated"] ?: 0,
            unmatched = counts["unmatched"] ?: 0,
            meanOffsetM = if (offsets.isEmpty()) 0.0 else offsets.sum() / offsets.size,
            maxOffsetM = offsets.maxOrNull() ?: 0.0,
            originalLengthM = originalLengthM,
            matchedLengthM = routeLengthM(traceRoute),
        )
    }

    /**
     * Total matched route length in metres.
     *
     * Valhalla reports kilometres or miles depending on the request; this asks
     * the response which it used rather than assuming.
     */
    fun routeLengthM(traceRoute: JsonObject): Double {
        val trip = traceRoute["trip"]?.jsonObject ?: return 0.0
        val length = trip["summary"]?.jsonObject?.double("length") ?: 0.0
        val units = trip.string("units") ?: "kilometers"
        return length * (if (units.startsWith("mi")) 1609.344 else 1000.0)
    }

    // --- internals --------------------------------------------------------

    private val NO_ATTRS = Triple<String?, String?, String?>(null, null, null)

    private fun Triple<String?, String?, String?>.any() =
        first != null || second != null || third != null

    private fun span(
        from: Int,
        to: Int,
        attrs: Triple<String?, String?, String?>,
        sentToOriginal: (Int) -> Int,
        pointCount: Int,
    ) = SurfaceSpan(
        from = sentToOriginal(from).coerceIn(0, pointCount - 1),
        to = sentToOriginal(to).coerceIn(0, pointCount - 1),
        surface = attrs.first,
        roadClass = attrs.second,
        use = attrs.third,
    )

    /** Flatten legs into (maneuver, decoded leg shape) pairs. */
    private fun legManeuvers(
        traceRoute: JsonObject,
    ): List<Pair<JsonObject, List<Pair<Double, Double>>>> {
        val legs = traceRoute["trip"]?.jsonObject?.get("legs")?.jsonArray ?: return emptyList()
        val out = ArrayList<Pair<JsonObject, List<Pair<Double, Double>>>>()
        legs.forEach { legElement ->
            val leg = legElement.jsonObject
            val shape = leg.string("shape")?.let { decodePolyline(it) } ?: emptyList()
            leg["maneuvers"]?.jsonArray?.forEach { out.add(it.jsonObject to shape) }
        }
        return out
    }

    /** First index whose cumulative distance is >= [value]. */
    private fun lowerBound(sorted: DoubleArray, value: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose cumulative distance is > [value]. */
    private fun upperBound(sorted: DoubleArray, value: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.double(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.string(key: String) = runCatching {
        this[key]?.jsonPrimitive?.content
    }.getOrNull()

    private fun JsonObject.strings(key: String): List<String> =
        this[key]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            .orEmpty()
}

/** How well a match followed the original track. */
data class MatchQuality(
    val costing: String,
    val totalPoints: Int,
    val matched: Int,
    val interpolated: Int,
    val unmatched: Int,
    val meanOffsetM: Double,
    val maxOffsetM: Double,
    val originalLengthM: Double,
    val matchedLengthM: Double,
) {
    val lengthDeviation: Double
        get() = if (originalLengthM <= 0) 0.0 else (matchedLengthM - originalLengthM) / originalLengthM

    val isUsable: Boolean get() = abs(lengthDeviation) <= Maneuvers.MAX_LENGTH_DEVIATION
}
