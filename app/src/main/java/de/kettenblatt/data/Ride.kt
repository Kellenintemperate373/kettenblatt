package de.kettenblatt.data

import de.kettenblatt.geo.Geo
import kotlinx.serialization.Serializable

/** One recorded GPS fix along a ride. */
@Serializable
data class TrailPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMs: Long,
)

/**
 * A ride, whether finished or still in progress.
 *
 * The trail is what actually happened -- every accepted fix, including any
 * detour off the route. [coveredRuns] is the separate question of which parts of
 * the *planned* route were ridden, stored as index ranges because that is the
 * shape `CoveredSegments.runs()` already produces.
 */
@Serializable
data class Ride(
    val id: String,
    val routeId: String,
    val routeName: String,
    val reversed: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val trail: List<TrailPoint> = emptyList(),
    /** Covered stretches as "first-last" index pairs, compact enough to rewrite often. */
    val coveredRuns: List<List<Int>> = emptyList(),
    val routeDistanceM: Double = 0.0,
    val routeSegments: Int = 0,
) {
    val isFinished: Boolean get() = endedAtMs != null

    /** One step of the trail, with the two figures every summary is built from. */
    private data class Leg(val metres: Double, val millis: Long) {
        val speedMps: Double get() = if (millis > 0) metres / (millis / 1000.0) else Double.MAX_VALUE

        /**
         * A leg the rider could actually have ridden.
         *
         * A fix that lands a kilometre away in three seconds is a GPS jump, and
         * counting it turned a 7.7 km ride into a 495 km/h average.
         */
        val isPlausible: Boolean get() = millis > 0 && speedMps <= MAX_PLAUSIBLE_MPS
    }

    private val legs: List<Leg> by lazy {
        trail.zipWithNext().map { (a, b) ->
            Leg(Geo.haversine(a.lat, a.lon, b.lat, b.lon), b.timeMs - a.timeMs)
        }
    }

    /** Distance actually travelled, from the trail rather than from the route. */
    val distanceM: Double by lazy { legs.filter { it.isPlausible }.sumOf { it.metres } }

    val elapsedMs: Long get() = (endedAtMs ?: trail.lastOrNull()?.timeMs ?: startedAtMs) - startedAtMs

    /**
     * Time spent actually moving.
     *
     * Gaps where the rider stood still are dropped, so a long cafe stop does not
     * turn a two-hour ride into a four-hour one.
     */
    val movingMs: Long by lazy {
        legs.filter { it.isPlausible && it.speedMps >= MOVING_THRESHOLD_MPS }.sumOf { it.millis }
    }

    /** Climb over the recorded trail, smoothed and thresholded as elsewhere. */
    val ascentM: Double by lazy {
        val elevations = trail.map { it.ele ?: 0.0 }
        if (elevations.none { it != 0.0 }) 0.0
        else {
            val points = trail.map { TrackPoint(it.lat, it.lon, it.ele ?: 0.0) }
            val cum = RouteMath.cumulativeDistances(points)
            RouteMath.cumulativeAscent(RouteMath.smoothElevation(elevations, cum)).last()
        }
    }

    /** How much of the planned route was ridden, 0..1. */
    val coverage: Double
        get() {
            if (routeSegments <= 0) return 0.0
            val covered = coveredRuns.sumOf { (it.getOrElse(1) { 0 } - it.getOrElse(0) { 0 }) }
            return (covered.toDouble() / routeSegments).coerceIn(0.0, 1.0)
        }

    val averageSpeedMps: Double?
        get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else null

    companion object {
        /** Below this a fix is a pause, not progress -- matches the tracker. */
        const val MOVING_THRESHOLD_MPS = 0.5

        /** Above this a leg is a bad fix, not a sprint -- matches the tracker. */
        const val MAX_PLAUSIBLE_MPS = 25.0
    }
}
