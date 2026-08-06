package de.kettenblatt.nav

import de.kettenblatt.data.Ride
import de.kettenblatt.data.RideStore
import de.kettenblatt.data.TrailPoint

/**
 * Keeps a record of the ride as it happens.
 *
 * Points accumulate in memory and are written out periodically rather than on
 * every fix: a ride is thousands of points and rewriting the file each second
 * would be pointless I/O. The flush interval is the most that can be lost if the
 * process dies, which is also what makes resuming possible at all.
 */
class RideRecorder(
    private val store: RideStore,
    private var ride: Ride,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
) {
    private val trail = ArrayList<TrailPoint>(ride.trail)
    private var lastFlushMs = 0L

    val rideId: String get() = ride.id

    /** Coverage this recorder was resumed with, for seeding a fresh tracker. */
    val restoredRuns: List<List<Int>> = ride.coveredRuns

    /** Record one accepted fix, flushing if enough time has passed. */
    fun record(
        lat: Double,
        lon: Double,
        ele: Double?,
        timeMs: Long,
        covered: CoveredSegments,
    ) {
        trail.add(TrailPoint(lat, lon, ele, timeMs))
        if (timeMs - lastFlushMs >= flushIntervalMs) {
            lastFlushMs = timeMs
            flush(covered)
        }
    }

    /** Write the ride out as it currently stands, still unfinished. */
    fun flush(covered: CoveredSegments) {
        ride = ride.copy(trail = ArrayList(trail), coveredRuns = covered.toRuns())
        store.save(ride)
    }

    /** Close the ride and write it into history. */
    fun finish(covered: CoveredSegments, endedAtMs: Long): Ride {
        ride = ride.copy(
            trail = ArrayList(trail),
            coveredRuns = covered.toRuns(),
            endedAtMs = endedAtMs,
        )
        store.save(ride)
        return ride
    }

    /** Throw away a ride nobody actually rode. */
    fun discardIfEmpty(minimumPoints: Int = MINIMUM_POINTS): Boolean {
        if (trail.size >= minimumPoints) return false
        store.delete(ride.id)
        return true
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 30_000L

        /** Fewer points than this and the ride was a mis-tap, not a ride. */
        const val MINIMUM_POINTS = 10
    }
}

/** Covered segments as serialisable index pairs. */
fun CoveredSegments.toRuns(): List<List<Int>> = runs().map { listOf(it.first, it.last) }
