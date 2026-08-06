package de.kettenblatt.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.kettenblatt.nav.NavState
import kotlinx.coroutines.delay

/**
 * Screen dimming between turns.
 *
 * On a long straight there is nothing on screen worth looking at, and the panel
 * is the single biggest power draw on a bar-mounted phone. This blacks the map
 * out and drops the backlight to its minimum until something needs attention.
 *
 * **What this is not:** an app cannot switch the panel off. Doing that needs
 * device-admin rights, and waking it again afterwards is unreliable across
 * manufacturers. What it does instead is draw full black and set the backlight to
 * minimum, which on the Pixel's OLED means almost every pixel is genuinely off --
 * visually and, near enough, electrically. The advantage over letting the system
 * time out is that waking is instant and entirely under the app's control, so a
 * turn can bring the screen back with no keyguard in the way.
 */
object ScreenDim {
    /** Quiet for this long with nothing ahead, and the screen goes dark. */
    const val IDLE_BEFORE_DIM_MS = 12_000L

    /** A maneuver this close counts as needing attention. */
    const val WAKE_AHEAD_M = 300.0
}

/**
 * True when the rider should be looking at the screen.
 *
 * A waypoint counts as much as a turn: missing the cafe you deliberately routed
 * past is exactly the kind of thing a dark screen would cause.
 */
fun NavState?.needsAttention(wakeAheadM: Double = ScreenDim.WAKE_AHEAD_M): Boolean {
    if (this == null) return true
    if (offRoute || wrongDirection || finished) return true
    val nearest = listOfNotNull(distanceToManeuverM, distanceToWaypointM).minOrNull()
        ?: return false
    return nearest <= wakeAheadM
}

/**
 * Blacks out [content] once the ride has been uneventful for a while.
 *
 * Returns nothing: the dim state is internal, because everything that should
 * cancel it is either derived from [state] or is a tap on the overlay itself.
 */
@Composable
fun AutoDim(
    enabled: Boolean,
    state: NavState?,
    idleBeforeDimMs: Long = ScreenDim.IDLE_BEFORE_DIM_MS,
    wakeAheadM: Double = ScreenDim.WAKE_AHEAD_M,
    /**
     * Set while the rider is being asked something.
     *
     * A dialog draws above the overlay so it stays legible, but the backlight
     * still drops to minimum -- unreadable in daylight, while waiting for an
     * answer the rider has to give.
     */
    suppressed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var dimmed by remember { mutableStateOf(false) }
    var lastAttentionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val attention = state.needsAttention(wakeAheadM) || suppressed

    // Anything worth seeing wakes the screen and restarts the clock.
    LaunchedEffect(attention, enabled) {
        if (attention || !enabled) {
            dimmed = false
            lastAttentionMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(enabled, attention, lastAttentionMs, idleBeforeDimMs) {
        if (!enabled || attention) return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - lastAttentionMs
        val remaining = idleBeforeDimMs - elapsed
        if (remaining > 0) delay(remaining)
        dimmed = true
    }

    // Restore the system brightness on the way out, or the next screen inherits
    // a black panel.
    DisposableEffect(dimmed) {
        context.setScreenBrightness(if (dimmed) MINIMUM_BRIGHTNESS else RESTORE_BRIGHTNESS)
        onDispose { context.setScreenBrightness(RESTORE_BRIGHTNESS) }
    }

    Box(Modifier.fillMaxSize()) {
        content()

        // A plain conditional, not AnimatedVisibility: its exit transition keeps
        // the overlay composed and on top of the controls for the length of the
        // fade, which is long enough to swallow the tap that follows waking.
        if (dimmed) {
            // Swallows taps as well as covering the map, so waking the screen
            // never also presses whatever was underneath.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        dimmed = false
                        lastAttentionMs = System.currentTimeMillis()
                    }
            )
        }
    }
}

private const val MINIMUM_BRIGHTNESS = 0.01f
private const val RESTORE_BRIGHTNESS = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

private fun android.content.Context.setScreenBrightness(value: Float) {
    val window = (this as? Activity)?.window ?: return
    window.attributes = window.attributes.apply { screenBrightness = value }
}
