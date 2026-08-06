package de.kettenblatt.data

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Recorded rides on disk.
 *
 * One file per ride, so an in-progress ride can be rewritten every few seconds
 * without touching the others, and a corrupt file costs one ride rather than the
 * whole history.
 *
 * Takes a directory rather than a Context, following [RouteIndex], so the whole
 * thing is testable on the JVM.
 */
class RideStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Finished rides, newest first. */
    fun list(): List<Ride> = read().filter { it.isFinished }.sortedByDescending { it.startedAtMs }

    fun find(id: String): Ride? = read().firstOrNull { it.id == id }

    /**
     * The ride still in progress, if any.
     *
     * At most one exists: a ride is finished or abandoned before another starts.
     */
    fun active(): Ride? = read().firstOrNull { !it.isFinished }

    fun save(ride: Ride) {
        dir.mkdirs()
        file(ride.id).writeText(json.encodeToString(Ride.serializer(), ride))
    }

    fun delete(id: String) {
        file(id).delete()
    }

    /**
     * Close an interrupted ride off into history, or discard it if nothing was
     * recorded -- an accidental start should not litter the list.
     */
    fun finaliseAbandoned(nowMs: Long, minimumPoints: Int = 10): Ride? {
        val ride = active() ?: return null
        if (ride.trail.size < minimumPoints) {
            delete(ride.id)
            return null
        }
        val finished = ride.copy(endedAtMs = ride.trail.last().timeMs.coerceAtMost(nowMs))
        save(finished)
        return finished
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun read(): List<Ride> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(Ride.serializer(), f.readText()) }.getOrNull()
            }
            .orEmpty()
}
