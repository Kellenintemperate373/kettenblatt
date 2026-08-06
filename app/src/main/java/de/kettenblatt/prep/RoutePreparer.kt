package de.kettenblatt.prep

import de.kettenblatt.data.Maneuver
import de.kettenblatt.data.Route
import de.kettenblatt.data.RouteMath
import de.kettenblatt.data.TrackPoint
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.max

/** Where a preparation run has got to, for the progress line in the UI. */
enum class PrepStage {
    MATCHING,
    RETRYING,
    MATCHING_REVERSE,
    DONE,
}

/** A prepared route plus everything worth telling the rider about the match. */
data class Prepared(
    val route: Route,
    val quality: MatchQuality?,
    val warnings: List<String>,
) {
    val bundle: String get() = BundleWriter.write(route, quality, warnings)
}

/**
 * Komoot GPX in, navigation bundle out -- the job `tools/prep.py` does, on the
 * phone.
 *
 * Ported from `tools/navi/pipeline.py`, including its most important property:
 * **map matching is best-effort**. If Valhalla is unreachable, or matches the
 * track badly, a route still comes out -- just without turn cues and street
 * names. The app treats those as optional, so a degraded route is still a
 * usable one rather than a failure, which matters when the wifi drops halfway
 * through preparing tomorrow's ride.
 */
class RoutePreparer(
    private val client: Valhalla,
    private val onStage: (PrepStage) -> Unit = {},
) {

    /**
     * Above this, the trace is thinned before being sent.
     *
     * Komoot's planned routes are decimated already (606 points on the reference
     * route), but the app also imports raw recordings -- a two-hour ride at 1 Hz
     * is 7000 points and would be refused by the service. Only the *trace* is
     * thinned: the route keeps every point, since the geometry is what gets
     * navigated.
     */
    private val maxTracePoints = MAX_TRACE_POINTS

    fun prepare(route: Route): Prepared {
        val warnings = ArrayList<String>()

        val forward = match(route.points, route.cumDistM, route.activity, warnings)
        val reverse = matchReverse(route, warnings)

        val prepared = route.copy(
            maneuvers = forward.maneuvers,
            reverseManeuvers = reverse,
            surfaces = forward.spans,
        )
        onStage(PrepStage.DONE)
        return Prepared(prepared, forward.quality, warnings)
    }

    private data class MatchResult(
        val quality: MatchQuality?,
        val maneuvers: List<Maneuver>,
        val spans: List<de.kettenblatt.data.SurfaceSpan>,
    )

    /**
     * Map-match a track, falling back to a more permissive costing if needed.
     *
     * Komoot routes over tracks and paths that bicycle costing penalises
     * heavily. Where that makes the match unusable, pedestrian costing
     * traverses almost anything and recovers the route, at the cost of slightly
     * less apt phrasing.
     */
    private fun match(
        points: List<TrackPoint>,
        cumDistM: DoubleArray,
        activity: String?,
        warnings: MutableList<String>,
        stage: PrepStage = PrepStage.MATCHING,
    ): MatchResult {
        val trace = Trace.of(points, maxTracePoints)
        val originalLengthM = cumDistM.lastOrNull() ?: 0.0
        val primary = Costing.forActivity(activity)

        onStage(stage)
        var attempt = try {
            attempt(trace, points, cumDistM, originalLengthM, primary)
        } catch (e: ValhallaException) {
            warnings.add("map matching unavailable: ${e.message}")
            return MatchResult(null, emptyList(), emptyList())
        }

        if (!attempt.quality.isUsable && primary.name != Costing.PEDESTRIAN.name) {
            warnings.add(
                "${primary.name} costing matched poorly " +
                    "(${percent(attempt.quality.lengthDeviation)} length error); " +
                    "retried with ${Costing.PEDESTRIAN.name}"
            )
            onStage(PrepStage.RETRYING)
            runCatching {
                attempt(trace, points, cumDistM, originalLengthM, Costing.PEDESTRIAN)
            }.onSuccess { fallback ->
                if (abs(fallback.quality.lengthDeviation) < abs(attempt.quality.lengthDeviation)) {
                    attempt = fallback
                }
            }.onFailure { e ->
                warnings.add("fallback costing failed: ${e.message}")
            }
        }

        val spans = Maneuvers.attributeSpans(
            attempt.traceAttributes,
            pointCount = points.size,
            sentToOriginal = trace::originalIndex,
            sentCount = trace.points.size,
        )

        if (!attempt.quality.isUsable) {
            warnings.add(
                "the matched route differs from this one by " +
                    "${percent(attempt.quality.lengthDeviation)}; turn cues omitted " +
                    "because they would describe a different path"
            )
            return MatchResult(attempt.quality, emptyList(), spans)
        }

        return MatchResult(
            quality = attempt.quality,
            maneuvers = Maneuvers.mapManeuvers(attempt.traceRoute, points, cumDistM),
            spans = spans,
        )
    }

    private data class Attempt(
        val traceRoute: JsonObject,
        val traceAttributes: JsonObject,
        val quality: MatchQuality,
    )

    private fun attempt(
        trace: Trace,
        points: List<TrackPoint>,
        cumDistM: DoubleArray,
        originalLengthM: Double,
        costing: Costing,
    ): Attempt {
        val traceRoute = client.traceRoute(trace.points, costing)
        val traceAttributes = client.traceAttributes(trace.points, costing)
        return Attempt(
            traceRoute,
            traceAttributes,
            Maneuvers.assessMatch(
                traceAttributes, traceRoute, costing.name, originalLengthM, points.size,
            ),
        )
    }

    /**
     * Turn cues for the route ridden backwards.
     *
     * The indices are in **reversed order** -- index 0 is the original finish --
     * so [Route.reversed] can apply them as-is after flipping the geometry and
     * neither side has to translate between index spaces.
     *
     * Mirroring the forward cues cannot work: ridden the other way you meet each
     * junction from a different arm, turn the other way, and join a different
     * street. Only a fresh match knows that.
     */
    private fun matchReverse(route: Route, warnings: MutableList<String>): List<Maneuver> {
        val flipped = route.points.reversed()
        val cum = RouteMath.cumulativeDistances(flipped)
        val reverseWarnings = ArrayList<String>()

        val result = match(flipped, cum, route.activity, reverseWarnings, PrepStage.MATCHING_REVERSE)

        // Only the forward direction's problems are worth interrupting for; a
        // failed reverse pass costs guidance in a direction nobody has asked to
        // ride yet, and the preview already says whether it exists.
        if (result.maneuvers.isEmpty() && reverseWarnings.isNotEmpty()) {
            warnings.add("no cues for riding this backwards: ${reverseWarnings.first()}")
        }
        return result.maneuvers
    }

    private fun percent(v: Double) = "%+.1f%%".format(v * 100)

    companion object {
        const val MAX_TRACE_POINTS = 2_000
    }
}

/**
 * The points actually sent for matching, and how to get back.
 *
 * `matched_points` in a `trace_attributes` response is 1:1 with what was sent,
 * so anything derived from it has to be translated back to the full track when
 * the trace was thinned.
 */
class Trace private constructor(
    val points: List<TrackPoint>,
    private val sourceIndices: IntArray,
) {
    /** Position in the original track of the [i]th point that was sent. */
    fun originalIndex(i: Int): Int =
        if (sourceIndices.isEmpty()) i else sourceIndices[i.coerceIn(sourceIndices.indices)]

    val isThinned: Boolean get() = sourceIndices.isNotEmpty()

    companion object {
        fun of(points: List<TrackPoint>, maxPoints: Int): Trace {
            if (points.size <= maxPoints) return Trace(points, IntArray(0))

            // Evenly spaced by index rather than by distance: the endpoints must
            // survive, and the matcher cares about coverage rather than an even
            // metre spacing.
            val step = (points.size - 1).toDouble() / (maxPoints - 1)
            val indices = IntArray(maxPoints) { i ->
                max(0, minOf(points.size - 1, Math.round(i * step).toInt()))
            }
            return Trace(indices.map { points[it] }, indices)
        }
    }
}
