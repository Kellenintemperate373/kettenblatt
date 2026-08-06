package de.kettenblatt.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kettenblatt.data.Route
import de.kettenblatt.data.Settings
import de.kettenblatt.data.Units
import de.kettenblatt.map.MapMode
import de.kettenblatt.map.RouteMapView
import de.kettenblatt.nav.NavState
import de.kettenblatt.ui.theme.accents
import java.io.File

@Composable
fun NavigationScreen(
    route: Route,
    state: NavState?,
    offlineTiles: File?,
    settings: Settings,
    onStop: () -> Unit,
    onReverse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val units = settings.units
    var mode by remember { mutableStateOf(MapMode.NAVIGATION) }
    // Panning by hand parks the map where the rider put it, until they either
    // recentre or switch mode.
    var panned by remember { mutableStateOf(false) }
    var showElevation by remember { mutableStateOf(route.ascentM > 0) }
    var autoDim by remember(settings.autoDimEnabled) { mutableStateOf(settings.autoDimEnabled) }
    var confirmingStop by remember { mutableStateOf(false) }
    var confirmingReverse by remember { mutableStateOf(false) }
    val now = remember(state) { System.currentTimeMillis() }

    // Dimming is only safe when there are turn cues to wake for. Without them
    // nothing would ever bring the screen back, and on a route with no guidance
    // the map is the only thing telling you where to go -- exactly what must not
    // be blacked out.
    val canAutoDim = route.hasGuidance

    AutoDim(
        enabled = autoDim && canAutoDim,
        state = state,
        idleBeforeDimMs = settings.autoDimDelayMs,
        wakeAheadM = settings.autoDimWakeAheadM,
        suppressed = confirmingStop || confirmingReverse,
    ) {
    Box(modifier.fillMaxSize()) {
        RouteMapView(
            route = route,
            state = state,
            mode = mode,
            follow = !panned,
            offlineTiles = offlineTiles,
            onUserPan = { panned = true },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) {
                when {
                    state == null -> StatusBanner("Waiting for GPS…", MaterialTheme.colorScheme.inverseSurface)
                    state.finished -> StatusBanner("Arrived", MaterialTheme.accents.arrived)
                    state.offRoute -> OffRouteBanner(state, units)
                    state.wrongDirection -> StatusBanner("Wrong direction", MaterialTheme.accents.caution)
                    route.hasGuidance -> state.nextManeuver?.let { m ->
                        TurnBanner(
                            distance = state.distanceToManeuverM,
                            label = maneuverLabel(m.type, m.street, m.instruction),
                            units = units,
                        )
                    }
                    else -> Unit
                }
            }

            Spacer(Modifier.width(8.dp))

            RideMenu(
                reversed = route.isReversed,
                autoDim = autoDim,
                canAutoDim = canAutoDim,
                onReverse = { confirmingReverse = true },
                onToggleAutoDim = { autoDim = !autoDim },
            )
        }

        // Secondary cues sit under the banner rather than replacing it: a turn
        // always outranks a cafe or a change of surface.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 88.dp, start = 12.dp, end = 68.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            state?.nextWaypoint?.let { wp ->
                val distance = state.distanceToWaypointM
                if (distance != null && distance <= WAYPOINT_CHIP_M) {
                    CueChip(
                        icon = Icons.Default.Place,
                        text = "${wp.label} — ${formatShortDistance(distance, units)}",
                        background = MaterialTheme.colorScheme.tertiaryContainer,
                        foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            state?.distanceToUnpavedM?.let { ahead ->
                val length = state.unpavedLengthM ?: 0.0
                if (ahead <= UNPAVED_CHIP_M && length > 0) {
                    val label = state.surface?.takeIf { ahead <= 1.0 }?.let(::surfaceLabel)
                        ?: "Unpaved"
                    CueChip(
                        icon = Icons.Default.Terrain,
                        text = if (ahead <= 1.0) {
                            "$label — ${formatDistance(length, units)} to go"
                        } else {
                            "$label ${formatDistance(length, units)} — in ${formatShortDistance(ahead, units)}"
                        },
                        background = MaterialTheme.colorScheme.secondaryContainer,
                        foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        MapControls(
            mode = mode,
            panned = panned,
            showElevation = showElevation,
            hasElevation = route.ascentM > 0,
            onRecentre = { panned = false; if (!mode.followsRider) mode = MapMode.NAVIGATION },
            onCycleMode = { mode = mode.next(); panned = false },
            onToggleElevation = { showElevation = !showElevation },
            modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
        )

        BottomPanel(
            route = route,
            state = state,
            nowMs = now,
            showElevation = showElevation,
            units = units,
            onStop = { confirmingStop = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }

    // A stray tap on a bar-mounted phone should not end the ride silently.
    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text("End navigation?") },
            text = { Text("${formatDistance(state?.distanceRemainingM ?: route.distanceM, units)} still to ride.") },
            confirmButton = {
                TextButton(onClick = { confirmingStop = false; onStop() }) { Text("End") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingStop = false }) { Text("Keep going") }
            },
        )
    }

    if (confirmingReverse) {
        AlertDialog(
            onDismissRequest = { confirmingReverse = false },
            title = { Text(if (route.isReversed) "Ride forwards?" else "Ride in reverse?") },
            text = {
                Text(
                    buildString {
                        append("The route flips end to end and progress restarts.\n\n")
                        if (route.hasReverseGuidance) {
                            append(
                                "Turn cues for this direction were matched separately " +
                                    "when the route was prepared, so guidance carries over."
                            )
                        } else {
                            // Only reachable for a bundle prepared before both
                            // directions were matched, or a plain GPX import.
                            append(
                                "This route has no cues for the other direction. " +
                                    "Add turn cues again to match this way round too."
                            )
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingReverse = false; onReverse() }) { Text("Reverse") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReverse = false }) { Text("Cancel") }
            },
        )
    }
}

/** Overflow menu for things you set once, not while steering. */
@Composable
private fun RideMenu(
    reversed: Boolean,
    autoDim: Boolean,
    canAutoDim: Boolean,
    onReverse: () -> Unit,
    onToggleAutoDim: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
        ) {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Ride options")
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (reversed) "Ride forwards" else "Reverse direction") },
                leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null) },
                onClick = { open = false; onReverse() },
            )
            DropdownMenuItem(
                enabled = canAutoDim,
                text = {
                    Text(
                        when {
                            !canAutoDim -> "Auto screen dim: needs turn cues"
                            autoDim -> "Auto screen dim: on"
                            else -> "Auto screen dim: off"
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        if (autoDim && canAutoDim) Icons.Default.Brightness2 else Icons.Default.BrightnessHigh,
                        contentDescription = null,
                    )
                },
                onClick = { open = false; onToggleAutoDim() },
            )
        }
    }
}

// --- banners --------------------------------------------------------------

@Composable
private fun TurnBanner(distance: Double?, label: String, units: Units) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (distance != null) {
                Text(
                    formatShortDistance(distance, units),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OffRouteBanner(state: NavState, units: Units) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.accents.offRoute,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.accents.onAlert,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "Off route — ${formatShortDistance(state.crossTrackM, units)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.accents.onAlert,
                )
                state.bearingToRouteDeg?.let {
                    Text(
                        "Head ${compassPoint(it)} to rejoin",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.accents.onAlert.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/** A small secondary cue: a waypoint coming up, or a change of surface. */
@Composable
private fun CueChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    background: Color,
    foreground: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = background,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusBanner(text: String, background: Color) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = background,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.accents.onAlert,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

// --- controls -------------------------------------------------------------

@Composable
private fun MapControls(
    mode: MapMode,
    panned: Boolean,
    showElevation: Boolean,
    hasElevation: Boolean,
    onRecentre: () -> Unit,
    onCycleMode: () -> Unit,
    onToggleElevation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // Recentring is only meaningful once the map has been dragged away from
        // the rider, so the button stays out of the way until then.
        AnimatedVisibility(panned, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(
                onClick = onRecentre,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Recentre")
            }
        }

        if (hasElevation) {
            // Filled when on, plain when off. A container-vs-surface pair reads
            // as almost the same colour in the dark theme, which leaves you
            // unable to tell which state the toggle is in.
            SmallFloatingActionButton(
                onClick = onToggleElevation,
                containerColor = if (showElevation) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (showElevation) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Icon(
                    Icons.Default.Terrain,
                    contentDescription = if (showElevation) "Hide elevation" else "Show elevation",
                )
            }
        }

        // One button, three stops. The icon shows what the *next* tap gives, so
        // the control reads as "press for closer / press for the whole route".
        FloatingActionButton(
            onClick = onCycleMode,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            when (mode) {
                MapMode.OVERVIEW ->
                    Icon(Icons.Default.Navigation, contentDescription = "Follow me")

                MapMode.NAVIGATION ->
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in closer")

                MapMode.NAVIGATION_CLOSE ->
                    Icon(Icons.Default.ZoomOutMap, contentDescription = "Show whole route")
            }
        }
    }
}

// --- bottom panel ---------------------------------------------------------

@Composable
private fun BottomPanel(
    route: Route,
    state: NavState?,
    nowMs: Long,
    showElevation: Boolean,
    units: Units,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            AnimatedVisibility(
                visible = showElevation,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ElevationProfile(
                    route = route,
                    progress = state?.progress ?: 0.0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Stat("REMAINING", formatDistance(state?.distanceRemainingM ?: route.distanceM, units))
                    Stat("ASCENT", formatAscent(state?.ascentRemainingM ?: route.ascentM, units))
                    Stat("SPEED", formatSpeed(state?.speedMps, units))
                    Stat("ETA", formatEta(state?.etaSeconds, nowMs))
                }

                Spacer(Modifier.width(12.dp))

                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Stop navigation")
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Show a waypoint from far enough out to react; alert fires closer, at 200 m. */
private const val WAYPOINT_CHIP_M = 600.0

/** Warn about a surface change with enough room to pick a line. */
private const val UNPAVED_CHIP_M = 500.0

/** Bearings are easier to act on as a compass point than as degrees. */
internal fun compassPoint(bearing: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((bearing % 360) + 360) % 360 / 45.0).toInt() % 8]
}
