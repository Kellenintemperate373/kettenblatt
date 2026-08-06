package de.kettenblatt.nav

import de.kettenblatt.data.Maneuver
import de.kettenblatt.data.Route
import de.kettenblatt.data.RouteWaypoint
import de.kettenblatt.geo.Geo
import de.kettenblatt.geo.LocalPlane
import de.kettenblatt.geo.projectOntoSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Everything the UI and the notification need, recomputed on each fix. */
data class NavState(
    val snappedIndex: Int,
    val crossTrackM: Double,
    val distanceAlongM: Double,
    val distanceRemainingM: Double,
    val ascentRemainingM: Double,
    val progress: Double,
    val offRoute: Boolean,
    val bearingToRouteDeg: Double?,
    val nextManeuver: Maneuver?,
    val distanceToManeuverM: Double?,
    /** The next waypoint ahead, so a planned stop is not ridden past. */
    val nextWaypoint: RouteWaypoint?,
    val distanceToWaypointM: Double?,
    val surface: String?,
    /** Distance to the next unpaved stretch, and how long it runs for. */
    val distanceToUnpavedM: Double?,
    val unpavedLengthM: Double?,
    /** Direction the route runs at this point, for orienting the map. */
    val routeBearingDeg: Double?,
    val speedMps: Double?,
    val etaSeconds: Long?,
    val wrongDirection: Boolean,
    val finished: Boolean,
    /**
     * Which stretches of the route have genuinely been ridden.
     *
     * Distinct from [distanceAlongM], which only says where you are now. A
     * section skipped by cutting a corner, or bypassed during an off-route
     * detour, stays uncovered -- so the map can show what was actually missed
     * rather than assuming everything behind you was ridden.
     */
    val covered: CoveredSegments,
)

/**
 * A record of which route segments have been ridden.
 *
 * Segment `i` spans points `i` to `i+1`. Immutable snapshots are published with
 * each state, so the UI can diff cheaply and never sees a half-updated array.
 */
class CoveredSegments(private val flags: BooleanArray) {
    val size: Int get() = flags.size

    operator fun get(segment: Int): Boolean =
        segment in flags.indices && flags[segment]

    /** Contiguous runs of covered segments, as point-index ranges. */
    fun runs(): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in flags.indices) {
            if (flags[i]) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out.add(start..i)
                start = -1
            }
        }
        if (start >= 0) out.add(start..flags.size)
        return out
    }

    val coveredCount: Int get() = flags.count { it }

    internal fun copy() = CoveredSegments(flags.copyOf())

    internal fun flags() = flags.copyOf()

    companion object {
        /** Nothing ridden yet -- useful as a placeholder in tests and previews. */
        fun none() = CoveredSegments(BooleanArray(0))

        /**
         * Rebuild from the "first-last" index pairs a ride was persisted with.
         *
         * Pairs are clamped rather than trusted: the file may have been written
         * against a route that has since been re-imported at a different length.
         */
        fun fromRuns(runs: List<List<Int>>, size: Int): CoveredSegments {
            val flags = BooleanArray(maxOf(0, size))
            runs.forEach { run ->
                val from = run.getOrNull(0) ?: return@forEach
                val to = run.getOrNull(1) ?: return@forEach
                for (i in maxOf(0, from) until minOf(to, flags.size)) flags[i] = true
            }
            return CoveredSegments(flags)
        }
    }
}

/**
 * Follows a rider along a route.
 *
 * Deliberately free of Android imports so the interesting behaviour -- snapping,
 * off-route hysteresis, ETA -- is testable on the JVM.
 *
 * Two details do most of the work:
 *
 * * **Windowed snapping.** The reference route is a loop that revisits 47
 *   coordinates, so the nearest point on the whole track is regularly on the
 *   wrong leg. Searching near the previous match avoids that; a full scan is
 *   the fallback for the first fix and for recovering after going off-route.
 * * **Hysteresis on the off-route test.** A single threshold flaps continuously
 *   under tree cover. Entering and leaving use different thresholds and both
 *   need several consecutive fixes to agree.
 */
class RouteTracker(
    private val route: Route,
    private val offRouteEnterM: Double = 40.0,
    private val offRouteExitM: Double = 25.0,
    private val offRouteImmediateM: Double = 60.0,
    private val enterFixes: Int = 3,
    private val exitFixes: Int = 2,
    private val maxAccuracyM: Double = 30.0,
    private val searchWindow: Int = 300,
    /**
     * Coverage carried over from an interrupted ride.
     *
     * Without this a resumed ride starts with a fully bright line, telling the
     * rider they have ridden none of what they just rode.
     */
    initialCoverage: CoveredSegments? = null,
) {
    private var lastSegment = -1
    private var lastDistanceAlong = Double.NaN
    private var offRoute = false
    private val coveredFlags = BooleanArray(maxOf(0, route.points.size - 1)).also { flags ->
        initialCoverage?.flags()?.let { prior ->
            for (i in 0 until minOf(flags.size, prior.size)) flags[i] = prior[i]
        }
    }
    private var lastCoveredSegment = -1
    private var overCount = 0
    private var underCount = 0

    private val speedWindow = ArrayDeque<Pair<Long, Double>>()
    private val progressWindow = ArrayDeque<Double>()

    /**
     * Reference (time, distance along) for judging movement from progress alone.
     *
     * Held for several seconds rather than compared fix-to-fix: over one second
     * GPS jitter alone can look like walking pace.
     */
    private var movementRef: Pair<Long, Double>? = null
    private var derivedMoving = false

    var state: NavState? = null
        private set

    /**
     * Feed one location fix. Returns the new state, or the previous one if the
     * fix was too inaccurate to use.
     */
    fun update(
        lat: Double,
        lon: Double,
        accuracyM: Double?,
        speedMps: Double?,
        timeMs: Long,
    ): NavState? {
        // A 100 m fix would trip the off-route alarm on its own.
        if (accuracyM != null && accuracyM > maxAccuracyM) return state

        val snap = snap(lat, lon) ?: return state
        val distanceAlong = snap.distanceAlongM

        updateOffRoute(snap.distanceM)
        recordCoverage(snap.segment)
        trackProgress(distanceAlong, timeMs, speedMps)

        val total = route.distanceM
        val remaining = max(0.0, total - distanceAlong)
        val ascentRemaining = max(0.0, route.ascentM - ascentAt(snap))
        val next = nextManeuver(distanceAlong)
        val nextWaypoint = route.waypointsAlongRoute
            .firstOrNull { it.distanceAlongM > distanceAlong }
        // The stretch you are on counts as "ahead" until you are through it, so
        // the chip stays up while you are actually riding the gravel -- including
        // at its very last point, which is still gravel.
        val unpaved = route.unpavedSpans.firstOrNull { route.cumDistM[it.to] >= distanceAlong }

        val newState = NavState(
            snappedIndex = snap.index,
            crossTrackM = snap.distanceM,
            distanceAlongM = distanceAlong,
            distanceRemainingM = remaining,
            ascentRemainingM = ascentRemaining,
            progress = if (total > 0) (distanceAlong / total).coerceIn(0.0, 1.0) else 0.0,
            offRoute = offRoute,
            bearingToRouteDeg = if (offRoute) {
                Geo.bearing(lat, lon, route.points[snap.index].lat, route.points[snap.index].lon)
            } else null,
            nextManeuver = next,
            distanceToManeuverM = next?.let { route.cumDistM[it.idx] - distanceAlong },
            nextWaypoint = nextWaypoint,
            distanceToWaypointM = nextWaypoint?.let { it.distanceAlongM - distanceAlong },
            surface = route.surfaceAt(snap.index),
            distanceToUnpavedM = unpaved?.let {
                (route.cumDistM[it.from] - distanceAlong).coerceAtLeast(0.0)
            },
            unpavedLengthM = unpaved?.let { route.cumDistM[it.to] - route.cumDistM[it.from] },
            routeBearingDeg = routeBearing(snap, distanceAlong),
            speedMps = currentSpeed(),
            etaSeconds = eta(remaining, ascentRemaining),
            wrongDirection = isGoingBackwards(),
            covered = CoveredSegments(coveredFlags).copy(),
            // Only "finished" once the end has genuinely been reached; a loop
            // passes close to its own start early on.
            finished = remaining < FINISH_RADIUS_M && distanceAlong > total * 0.9,
        )
        state = newState
        return newState
    }

    // --- snapping ---------------------------------------------------------

    private data class Snap(
        val index: Int,
        val segment: Int,
        val t: Double,
        val distanceM: Double,
        val distanceAlongM: Double,
    )

    private fun snap(lat: Double, lon: Double): Snap? {
        if (route.points.size < 2) return null
        val plane = LocalPlane(lat, lon)
        val px = plane.x(lon)
        val py = plane.y(lat)

        // Try near the last match first; fall back to the whole track when there
        // is no history, when off-route, or when the local best looks wrong.
        if (lastSegment >= 0 && !offRoute) {
            val lo = max(0, lastSegment - searchWindow)
            val hi = min(route.points.size - 1, lastSegment + searchWindow)
            val local = scan(plane, px, py, lo, hi)
            if (local != null && local.distanceM <= offRouteEnterM) {
                lastSegment = local.segment
                lastDistanceAlong = local.distanceAlongM
                return local
            }
        }

        val full = scan(plane, px, py, 0, route.points.size - 1) ?: return null
        lastSegment = full.segment
        lastDistanceAlong = full.distanceAlongM
        return full
    }

    private fun scan(plane: LocalPlane, px: Double, py: Double, lo: Int, hi: Int): Snap? {
        if (hi <= lo) return null

        var bestDist = Double.MAX_VALUE
        // Where a route doubles back, several segments are genuinely equidistant
        // -- position alone cannot say which leg you are on. Collect every
        // near-equal candidate and settle it by continuity below.
        val candidates = ArrayList<Snap>(4)

        for (i in lo until hi) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val p = projectOntoSegment(
                px, py,
                plane.x(a.lon), plane.y(a.lat),
                plane.x(b.lon), plane.y(b.lat),
            )
            if (p.distanceM > bestDist + TIE_TOLERANCE_M) continue

            if (p.distanceM < bestDist) {
                bestDist = p.distanceM
                // Prune against the *new* best. Dropping the list only on a
                // better-than-tolerance improvement is not enough: where the
                // cross-track distance falls gradually -- which is the normal
                // case on a densely sampled recorded track -- no single step
                // ever clears it, and candidates kilometres away survive to be
                // picked by the continuity tie-break.
                candidates.retainAll { it.distanceM <= bestDist + TIE_TOLERANCE_M }
            }

            val from = route.cumDistM[i]
            val to = route.cumDistM[i + 1]
            candidates.add(
                Snap(
                    index = if (p.t < 0.5) i else i + 1,
                    segment = i,
                    t = p.t,
                    distanceM = p.distanceM,
                    distanceAlongM = from + p.t * (to - from),
                )
            )
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        return disambiguate(candidates)
    }

    /**
     * Choose between segments the rider is equally close to.
     *
     * The reference route runs out to a waypoint and retraces the identical
     * points back, so at the turnaround the outbound and return legs share exact
     * coordinates. Nothing about the current position distinguishes them; only
     * continuity with the previous fix does.
     */
    private fun disambiguate(candidates: List<Snap>): Snap {
        // No history yet. Prefer the earliest position, so beginning a loop whose
        // end overlaps its start is not mistaken for having almost finished it.
        if (lastDistanceAlong.isNaN()) return candidates.minBy { it.distanceAlongM }

        // Ignore anything further along than the rider could plausibly have
        // travelled since the last fix; that guards against a distant coincident
        // leg capturing the match.
        val plausible = candidates.filter {
            abs(it.distanceAlongM - lastDistanceAlong) <= MAX_JUMP_M
        }.ifEmpty { candidates }

        // Between equally-close legs, assume progress. Riding genuinely backwards
        // is still tracked, because then no forward candidate exists at all.
        val forward = plausible.filter { it.distanceAlongM >= lastDistanceAlong }
        return (forward.ifEmpty { plausible }).minBy { abs(it.distanceAlongM - lastDistanceAlong) }
    }

    private fun ascentAt(snap: Snap): Double {
        val a = route.cumAscentM[snap.segment]
        val b = route.cumAscentM[snap.segment + 1]
        return a + snap.t * (b - a)
    }

    // --- off-route --------------------------------------------------------

    private fun updateOffRoute(crossTrackM: Double) {
        if (offRoute) {
            if (crossTrackM < offRouteExitM) {
                if (++underCount >= exitFixes) {
                    offRoute = false
                    underCount = 0
                    overCount = 0
                }
            } else {
                underCount = 0
            }
            return
        }

        if (crossTrackM > offRouteImmediateM) {
            offRoute = true
            overCount = 0
            underCount = 0
        } else if (crossTrackM > offRouteEnterM) {
            if (++overCount >= enterFixes) {
                offRoute = true
                overCount = 0
                underCount = 0
            }
        } else {
            overCount = 0
        }
    }

    // --- coverage ---------------------------------------------------------

    /**
     * Mark the ridden route as covered, filling in the segments between fixes.
     *
     * At 4 s intervals a rider covers 20-odd metres, several segments' worth on
     * a dense track, so marking only the current segment would leave the line
     * striped. Filling the gap is right when the rider genuinely moved along the
     * route, and wrong if they jumped -- which is why the fill is capped, and
     * why nothing is recorded at all while off-route: a detour does not count as
     * riding the part of the route it bypasses.
     */
    private fun recordCoverage(segment: Int) {
        if (coveredFlags.isEmpty()) return
        if (offRoute) {
            lastCoveredSegment = -1
            return
        }

        val current = segment.coerceIn(coveredFlags.indices)
        val previous = lastCoveredSegment
        if (previous >= 0 && previous != current) {
            val from = minOf(previous, current)
            val to = maxOf(previous, current)
            // Bridge the gap between consecutive fixes so the ridden line is
            // continuous rather than striped -- but only over a distance a rider
            // could actually have covered between two fixes. Measuring this in
            // points instead was wrong on both ends: 40 points is 80 m on a dense
            // GPX and nearly 2 km on a sparse one, which quietly marked a
            // genuinely skipped stretch as ridden.
            val bridgeable = to - from <= 1 ||
                route.cumDistM[to] - route.cumDistM[from] <= MAX_COVERAGE_FILL_M
            if (bridgeable) {
                for (i in from..to) coveredFlags[i] = true
            }
        }
        coveredFlags[current] = true
        lastCoveredSegment = current
    }

    // --- progress, speed, ETA --------------------------------------------

    private fun trackProgress(distanceAlong: Double, timeMs: Long, speedMps: Double?) {
        // Stationary fixes would drag the average toward zero and inflate ETA
        // for the rest of the ride, so rest stops are excluded outright.
        //
        // Movement is judged from the rider's own progress when the provider
        // gives no speed, which is normal on the first fixes and permanent on
        // hardware without Doppler speed. Trusting `speedMps` alone meant the
        // window never filled on those devices: speed showed nothing for the
        // whole ride and ETA silently sat on the activity default forever.
        val moving = isMoving(distanceAlong, timeMs, speedMps)

        if (moving) {
            // A fix that lands hundreds of metres along the route -- a jump out
            // of a tunnel, a cold start, a reflected signal -- is not a sprint.
            // Averaging across it would leave Speed and ETA nonsense for the
            // whole window, so the window restarts from the new position.
            val anchor = speedWindow.lastOrNull()
            if (anchor != null && impliedSpeed(anchor, timeMs, distanceAlong) > MAX_PLAUSIBLE_MPS) {
                speedWindow.clear()
            }
            speedWindow.addLast(timeMs to distanceAlong)
            while (speedWindow.size > 1 && timeMs - speedWindow.first().first > SPEED_WINDOW_MS) {
                speedWindow.removeFirst()
            }
        }

        progressWindow.addLast(distanceAlong)
        while (progressWindow.size > DIRECTION_WINDOW) progressWindow.removeFirst()
    }

    /**
     * Whether the rider is under way.
     *
     * Either signal is enough. The provider's speed is the better instantaneous
     * figure when it is right, but it cannot be the only one: some providers
     * report no speed at all, and some report a near-zero speed while the rider
     * is plainly covering ground -- the emulator's fused provider reports 0.18
     * m/s at any pace. Trusting it alone left Speed blank and ETA pinned to the
     * activity default for the whole ride.
     */
    private fun isMoving(distanceAlong: Double, timeMs: Long, speedMps: Double?): Boolean {
        if (speedMps != null && speedMps >= MOVING_THRESHOLD_MPS) return true
        return movingByProgress(distanceAlong, timeMs)
    }

    private fun impliedSpeed(from: Pair<Long, Double>, timeMs: Long, distanceAlong: Double): Double {
        val elapsed = (timeMs - from.first) / 1000.0
        if (elapsed <= 0) return Double.MAX_VALUE
        return abs(distanceAlong - from.second) / elapsed
    }

    /** Movement inferred from progress along the route over a few seconds. */
    private fun movingByProgress(distanceAlong: Double, timeMs: Long): Boolean {
        val ref = movementRef
        if (ref == null || timeMs < ref.first) {
            movementRef = timeMs to distanceAlong
            return false
        }

        val elapsed = (timeMs - ref.first) / 1000.0
        // Too soon to tell; the previous verdict is still the best answer.
        if (elapsed < MOVEMENT_SAMPLE_S) return derivedMoving

        derivedMoving = abs(distanceAlong - ref.second) / elapsed >= MOVING_THRESHOLD_MPS
        movementRef = timeMs to distanceAlong
        return derivedMoving
    }

    private fun currentSpeed(): Double? {
        if (speedWindow.size < 2) return null
        val (t0, d0) = speedWindow.first()
        val (t1, d1) = speedWindow.last()
        val dt = (t1 - t0) / 1000.0
        if (dt <= 0) return null
        val v = (d1 - d0) / dt
        return if (v > 0) v else null
    }

    private fun eta(remainingM: Double, ascentRemainingM: Double): Long? {
        val speed = currentSpeed() ?: defaultSpeedMps()
        if (speed <= 0) return null
        // Naismith: add an hour per 600 m of climb. Without it, the estimate on
        // a long ascent is wildly optimistic.
        val climbSeconds = ascentRemainingM / NAISMITH_M_PER_HOUR * 3600.0
        return (remainingM / speed + climbSeconds).toLong()
    }

    private fun defaultSpeedMps(): Double {
        val a = route.activity?.lowercase() ?: return DEFAULT_CYCLING_MPS
        return when {
            PEDESTRIAN_HINTS.any { it in a } -> DEFAULT_WALKING_MPS
            else -> DEFAULT_CYCLING_MPS
        }
    }

    private fun isGoingBackwards(): Boolean {
        if (progressWindow.size < DIRECTION_WINDOW) return false
        return progressWindow.zipWithNext().all { (a, b) -> b < a - BACKWARDS_TOLERANCE_M }
    }

    // --- maneuvers --------------------------------------------------------

    private fun nextManeuver(distanceAlongM: Double): Maneuver? =
        route.maneuvers.firstOrNull { route.cumDistM[it.idx] > distanceAlongM }

    // --- heading ----------------------------------------------------------

    /**
     * Which way the route runs from here, for pointing the map.
     *
     * Taken by looking ahead along the track rather than from the bearing of the
     * current segment: segments are short (29 m median on the reference route)
     * so a per-segment bearing swings wildly and the map would judder. It is
     * also steadier than GPS course, which is meaningless at walking pace.
     */
    private fun routeBearing(snap: Snap, distanceAlongM: Double): Double? {
        val n = route.points.size
        if (n < 2) return null

        val target = distanceAlongM + HEADING_LOOKAHEAD_M
        var ahead = (snap.segment + 1).coerceAtMost(n - 1)
        while (ahead < n - 1 && route.cumDistM[ahead] < target) ahead++

        val from = route.points[snap.segment]
        val to = route.points[ahead]
        if (Geo.haversine(from.lat, from.lon, to.lat, to.lon) >= MIN_BEARING_BASELINE_M) {
            return Geo.bearing(from.lat, from.lon, to.lat, to.lon)
        }

        // At the very end of the route there is nothing ahead; keep pointing the
        // way the last stretch ran rather than dropping the heading entirely.
        val prev = route.points[(snap.segment - 1).coerceAtLeast(0)]
        return if (Geo.haversine(prev.lat, prev.lon, from.lat, from.lon) < MIN_BEARING_BASELINE_M) null
        else Geo.bearing(prev.lat, prev.lon, from.lat, from.lon)
    }

    companion object {
        /** Two candidate segments this close together are treated as a tie. */
        const val TIE_TOLERANCE_M = 5.0

        /** How far along the route a single fix may plausibly advance. */
        const val MAX_JUMP_M = 250.0

        /**
         * How much route one fix may fill in as covered.
         *
         * Beyond this the rider did not ride the gap -- they skipped it, or the
         * snap moved to a different part of a route that doubles back. Sized for
         * the gap between two fixes with the screen off, not for a shortcut.
         */
        const val MAX_COVERAGE_FILL_M = 150.0
        const val FINISH_RADIUS_M = 30.0
        const val MOVING_THRESHOLD_MPS = 0.5

        /** Long enough that a standing rider's GPS jitter cannot look like riding. */
        const val MOVEMENT_SAMPLE_S = 5.0

        /** 90 km/h: beyond any descent, so anything faster is a bad fix. */
        const val MAX_PLAUSIBLE_MPS = 25.0
        const val SPEED_WINDOW_MS = 5 * 60 * 1000L
        const val NAISMITH_M_PER_HOUR = 600.0
        const val DEFAULT_CYCLING_MPS = 16_000.0 / 3600.0
        const val DEFAULT_WALKING_MPS = 4_500.0 / 3600.0
        const val DIRECTION_WINDOW = 5
        const val BACKWARDS_TOLERANCE_M = 2.0

        /** How far ahead to look when working out which way the route runs. */
        const val HEADING_LOOKAHEAD_M = 60.0
        const val MIN_BEARING_BASELINE_M = 1.0

        val PEDESTRIAN_HINTS = listOf("hike", "jogging", "walk", "mountaineering", "climbing", "nordic")
    }
}
