package de.kettenblatt.nav

import android.content.Context
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Silent alerts: vibration only.
 *
 * Nothing here makes a sound. A tone competes with traffic, with whatever the
 * rider is listening to, and with other people on a shared path -- and it is the
 * one signal you cannot turn down without also silencing the phone. Haptics reach
 * you through a jersey pocket or a bar mount and bother nobody else.
 *
 * The patterns are deliberately unalike, since the whole point is telling them
 * apart without looking:
 *
 * * off route      -- two long pulses, repeating
 * * wrong direction -- three short pulses, repeating
 * * back on route  -- one short pulse, once
 */
class Alerts(context: Context) {

    private val vibrator = context.getSystemService(VibratorManager::class.java)

    private var lastOffRouteAlertMs = 0L
    private var lastWrongWayAlertMs = 0L
    private var wasOffRoute = false
    private var wasWrongDirection = false
    private val announcedWaypoints = HashSet<Int>()

    /** Call on every state update; decides internally whether anything should fire. */
    fun onState(state: NavState, nowMs: Long) {
        if (state.offRoute) {
            val due = nowMs - lastOffRouteAlertMs >= REPEAT_INTERVAL_MS
            if (!wasOffRoute || due) {
                offRoute()
                lastOffRouteAlertMs = nowMs
            }
        } else {
            if (wasOffRoute) backOnRoute()

            // Riding the route backwards is a different mistake from leaving it,
            // and only worth flagging while still on the line -- off-route takes
            // precedence, and feeling both at once tells you nothing.
            if (state.wrongDirection) {
                val due = nowMs - lastWrongWayAlertMs >= REPEAT_INTERVAL_MS
                if (!wasWrongDirection || due) {
                    wrongDirection()
                    lastWrongWayAlertMs = nowMs
                }
            }
        }

        // A waypoint is a place you chose to stop at, so it gets one buzz as it
        // comes up and never nags -- unlike off-route, which repeats because it
        // is a problem rather than an event.
        val waypoint = state.nextWaypoint
        val distance = state.distanceToWaypointM
        if (waypoint != null && distance != null &&
            distance <= WAYPOINT_ALERT_M && announcedWaypoints.add(waypoint.index)
        ) {
            approachingWaypoint()
        }

        wasOffRoute = state.offRoute
        wasWrongDirection = state.wrongDirection && !state.offRoute
    }

    fun offRoute() = vibrate(longArrayOf(0, 280, 160, 280))

    fun wrongDirection() = vibrate(longArrayOf(0, 90, 90, 90, 90, 90))

    fun backOnRoute() = vibrate(longArrayOf(0, 110))

    /** Two quick taps: an event, not a warning. */
    fun approachingWaypoint() = vibrate(longArrayOf(0, 60, 80, 60))

    fun release() = Unit

    /**
     * Best-effort: a missing vibrator, or a device that rejects the effect, is no
     * reason to stop navigating. This runs on the location callback's thread, so
     * an exception here would otherwise take the app down mid-ride.
     */
    private fun vibrate(pattern: LongArray) {
        runCatching {
            vibrator?.vibrate(
                CombinedVibration.createParallel(VibrationEffect.createWaveform(pattern, -1)),
                // Classed as accessibility so it still fires when the phone is
                // set to suppress ordinary notification buzzes.
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY),
            )
        }
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 30_000L

        /** Far enough ahead to stop comfortably, close enough not to forget. */
        const val WAYPOINT_ALERT_M = 200.0
    }
}
