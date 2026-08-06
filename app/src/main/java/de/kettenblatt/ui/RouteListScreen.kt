package de.kettenblatt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kettenblatt.data.Ride
import de.kettenblatt.data.RouteMeta
import de.kettenblatt.data.Units

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListScreen(
    routes: List<RouteMeta>,
    error: String?,
    busy: Boolean,
    units: Units,
    onImport: () -> Unit,
    onOpen: (RouteMeta) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    resumable: Ride?,
    onResume: (Ride) -> Unit,
    onDiscardResumable: () -> Unit,
    onAttachTiles: (RouteMeta) -> Unit,
    onRename: (RouteMeta, String) -> Unit,
    onToggleFavourite: (RouteMeta) -> Unit,
    onDelete: (RouteMeta) -> Unit,
    onDismissError: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RouteMeta?>(null) }
    var pendingRename by remember { mutableStateOf<RouteMeta?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Routes", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "Rides")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!busy) onImport() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Import") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Importing can take a moment on a cloud-backed file, so say so
            // rather than looking like the tap did nothing.
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            resumable?.let { ride ->
                ResumeBanner(
                    ride = ride,
                    units = units,
                    onResume = { onResume(ride) },
                    onDiscard = onDiscardResumable,
                )
            }

            if (routes.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(routes, key = { it.id }) { route ->
                        RouteCard(
                            route = route,
                            enabled = !busy,
                            units = units,
                            onOpen = { onOpen(route) },
                            onAttachTiles = { onAttachTiles(route) },
                            onRename = { pendingRename = route },
                            onToggleFavourite = { onToggleFavourite(route) },
                            onDelete = { pendingDelete = route },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete route?") },
            text = {
                Text(
                    buildString {
                        append(route.name)
                        // Worth saying: the tile pack is megabytes and had to be
                        // transferred by hand.
                        if (route.tilesFileName != null) {
                            append("\n\nIts offline map will be deleted too.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(route); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    pendingRename?.let { route ->
        RenameDialog(
            initial = route.name,
            onDismiss = { pendingRename = null },
            onConfirm = { onRename(route, it); pendingRename = null },
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Could not import") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("OK") } },
        )
    }
}

@Composable
private fun RouteCard(
    route: RouteMeta,
    enabled: Boolean,
    units: Units,
    onOpen: () -> Unit,
    onAttachTiles: () -> Unit,
    onRename: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        // Shadow only. M3 tonal elevation tints a surface toward the primary
        // colour, which on a card this large reads as "slightly blue" rather
        // than as depth.
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onOpen),
    ) {
        Column(Modifier.padding(start = 18.dp, top = 12.dp, end = 6.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (route.favourite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Favourite",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp).size(18.dp),
                    )
                }
                Text(
                    route.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 6.dp),
                )
                // Management lives behind an explicit menu rather than a long
                // press, which nothing on screen advertises.
                RouteMenu(
                    route = route,
                    enabled = enabled,
                    onRename = onRename,
                    onToggleFavourite = onToggleFavourite,
                    onAttachTiles = onAttachTiles,
                    onDelete = onDelete,
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric(Icons.Default.Route, formatDistance(route.distanceM, units))
                Metric(Icons.Default.ArrowUpward, formatAscent(route.ascentM, units))
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Whether a route carries guidance is the main thing worth
                // knowing before setting off, so it is stated rather than implied.
                if (route.hasGuidance) {
                    Chip(
                        text = "${route.maneuverCount} turns",
                        icon = Icons.Default.Directions,
                        background = MaterialTheme.colorScheme.primaryContainer,
                        foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Chip(
                        text = "No turn cues",
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (route.tilesFileName != null) {
                    Chip(
                        text = "Offline",
                        icon = Icons.Default.CloudOff,
                        background = MaterialTheme.colorScheme.tertiaryContainer,
                        foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else {
                    Chip(
                        text = "+ Offline map",
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = if (enabled) onAttachTiles else null,
                    )
                }
            }
        }
    }
}

/**
 * Offered when the app died mid-ride.
 *
 * Deliberately prominent and at the top: the alternative is silently losing the
 * ride, and the rider is most likely reopening the app *because* it stopped.
 */
@Composable
private fun ResumeBanner(
    ride: Ride,
    units: Units,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Ride in progress",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${ride.routeName} — ${formatDistance(ride.distanceM, units)} ridden",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("Resume") }
                TextButton(onClick = onDiscard) { Text("Finish it") }
            }
        }
    }
}

/** Per-route management: rename, favourite, offline map, delete. */
@Composable
private fun RouteMenu(
    route: RouteMeta,
    enabled: Boolean,
    onRename: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAttachTiles: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Options for ${route.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (route.favourite) "Remove favourite" else "Add to favourites") },
                leadingIcon = {
                    Icon(
                        if (route.favourite) Icons.Default.StarBorder else Icons.Default.Star,
                        contentDescription = null,
                    )
                },
                onClick = { open = false; onToggleFavourite() },
            )
            DropdownMenuItem(
                text = { Text("Rename…") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(if (route.tilesFileName != null) "Replace offline map…" else "Add offline map…") },
                leadingIcon = { Icon(Icons.Default.CloudOff, contentDescription = null) },
                onClick = { open = false; onAttachTiles() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename route") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                isError = !valid,
                supportingText = if (!valid) {
                    { Text("A route needs a name") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Metric(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Chip(
    text: String,
    background: Color,
    foreground: Color,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(background)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("No routes yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Import a Komoot GPX. Turn cues, street names and an offline " +
                    "map are added here, over wifi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
