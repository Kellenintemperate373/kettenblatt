package de.kettenblatt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kettenblatt.data.Route
import de.kettenblatt.data.Units
import de.kettenblatt.map.MapMode
import de.kettenblatt.map.RouteMapView
import de.kettenblatt.ui.theme.NaviColors
import java.io.File

/**
 * What the route looks like before committing to it.
 *
 * Tapping a card used to start the foreground service and GPS immediately, which
 * meant deciding whether a route was the right one while it was already
 * navigating. This is also where direction belongs: both cue sets are in the
 * bundle, so choosing before setting off costs nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePreviewScreen(
    route: Route,
    reversed: Boolean,
    offlineTiles: File?,
    units: Units,
    prep: PrepState,
    onSetReversed: (Boolean) -> Unit,
    onPrepare: () -> Unit,
    onDownloadTiles: () -> Unit,
    onCancelPrep: () -> Unit,
    onDismissPrepMessage: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val shown = remember(route, reversed) { if (reversed) route.reversed() else route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        route.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
            ) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start ride", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Box(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.small)) {
                        // No fix yet, so the map frames the whole route.
                        RouteMapView(
                            route = shown,
                            state = null,
                            mode = MapMode.OVERVIEW,
                            follow = false,
                            offlineTiles = offlineTiles,
                            onUserPan = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            item {
                PreparationCard(
                    route = route,
                    offlineTiles = offlineTiles,
                    prep = prep,
                    onPrepare = onPrepare,
                    onDownloadTiles = onDownloadTiles,
                    onCancel = onCancelPrep,
                    onDismissMessage = onDismissPrepMessage,
                )
            }

            item {
                Card {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Metric(Icons.Default.Route, "Distance", formatDistance(shown.distanceM, units))
                        Metric(Icons.Default.ArrowUpward, "Ascent", formatAscent(shown.ascentM, units))
                        Metric(
                            Icons.Default.Directions,
                            "Turns",
                            if (shown.hasGuidance) "${shown.maneuvers.size}" else "—",
                        )
                    }
                }
            }

            item {
                Card {
                    Label("DIRECTION")
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !reversed,
                            onClick = { onSetReversed(false) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Forwards") }
                        SegmentedButton(
                            selected = reversed,
                            onClick = { onSetReversed(true) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Reverse") }
                    }
                    // Say it plainly when a direction has no guidance, rather
                    // than letting the rider discover it on the road.
                    if (!shown.hasGuidance && route.hasGuidance) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No cues for the reverse direction. Add turn cues " +
                                "again to match this way round too.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (shown.ascentM > 0) {
                item {
                    Card {
                        ElevationProfile(
                            route = shown,
                            progress = 0.0,
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                        )
                    }
                }
            }

            if (shown.surfaces.isNotEmpty()) {
                item { Card { SurfaceBreakdown(shown, units) } }
            }

            if (shown.waypointsAlongRoute.isNotEmpty()) {
                item {
                    Card {
                        Label("WAYPOINTS")
                        Spacer(Modifier.height(4.dp))
                        shown.waypointsAlongRoute.forEach { wp ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    contentDescription = null,
                                    tint = NaviColors.Waypoint,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    wp.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatDistance(wp.distanceAlongM, units),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * How much of the ride is off tarmac, and where the ferries are.
 *
 * The bar is proportional to distance rather than to point count, since Komoot's
 * geometry is far denser through towns than across open country.
 */
@Composable
private fun SurfaceBreakdown(route: Route, units: Units) {
    val total = route.distanceM.coerceAtLeast(1.0)
    val unpavedM = remember(route) {
        route.unpavedSpans.sumOf { route.cumDistM[it.to] - route.cumDistM[it.from] }
    }
    val ferries = remember(route) { route.surfaces.count { it.isFerry } }

    Label("SURFACE")
    Spacer(Modifier.height(10.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val pavedFraction = ((total - unpavedM) / total).toFloat().coerceIn(0f, 1f)
        if (pavedFraction > 0f) {
            Box(Modifier.weight(pavedFraction).fillMaxSize().background(MaterialTheme.colorScheme.primary))
        }
        if (pavedFraction < 1f) {
            Box(
                Modifier.weight(1f - pavedFraction).fillMaxSize()
                    .background(NaviColors.Waypoint)
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatch(MaterialTheme.colorScheme.primary)
        Text(
            "  Paved ${formatDistance(total - unpavedM, units)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(16.dp))
        Swatch(NaviColors.Waypoint)
        Text(
            "  Unpaved ${formatDistance(unpavedM, units)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (ferries > 0) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DirectionsBoat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (ferries == 1) "1 ferry crossing" else "$ferries ferry crossings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(10.dp).clip(MaterialTheme.shapes.extraSmall).background(color))
}

@Composable
internal fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Metric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
