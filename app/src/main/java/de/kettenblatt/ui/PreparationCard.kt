package de.kettenblatt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kettenblatt.data.Route
import de.kettenblatt.prep.PrepStage
import de.kettenblatt.ui.theme.accents
import de.kettenblatt.prep.TileProgress
import java.io.File
import kotlin.math.roundToInt

/** What preparation is doing right now, and what it last had to say. */
data class PrepState(
    val stage: PrepStage? = null,
    val tiles: TileProgress? = null,
    val tilePlanSummary: String? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val done: String? = null,
) {
    val busy: Boolean get() = stage != null || tiles != null
}

/**
 * Preparing a route on the phone: turn cues, and an offline map.
 *
 * Both were desktop jobs until now -- run Valhalla in Docker, run `prep.py`,
 * copy two files across. Neither is much use on the sofa the night before a
 * ride, which is exactly when a route gets planned.
 */
@Composable
fun PreparationCard(
    route: Route,
    offlineTiles: File?,
    prep: PrepState,
    onPrepare: () -> Unit,
    onDownloadTiles: () -> Unit,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Card {
        Label("PREPARE")
        Spacer(Modifier.height(10.dp))

        when {
            prep.tiles != null -> TileProgressRow(prep.tiles, onCancel)
            prep.stage != null -> MatchingRow(prep.stage)
            else -> Actions(route, offlineTiles, prep, onPrepare, onDownloadTiles)
        }

        prep.done?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Warnings are the useful part of a match: "retried with pedestrian
        // costing" explains phrasing that would otherwise look wrong.
        if (prep.warnings.isNotEmpty() || prep.error != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.accents.caution,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    prep.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    prep.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onDismissMessage) { Text("OK") }
            }
        }
    }
}

@Composable
private fun Actions(
    route: Route,
    offlineTiles: File?,
    prep: PrepState,
    onPrepare: () -> Unit,
    onDownloadTiles: () -> Unit,
) {
    Text(
        if (route.hasGuidance) {
            "This route has turn cues and street names."
        } else {
            "Geometry only. Matching it against OpenStreetMap adds turn cues, " +
                "street names and surface — over wifi now, so the ride itself " +
                "needs no connection."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (route.hasGuidance) {
            OutlinedButton(onClick = onPrepare, enabled = !prep.busy) {
                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Re-match")
            }
        } else {
            Button(onClick = onPrepare, enabled = !prep.busy) {
                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add turn cues")
            }
        }

        OutlinedButton(onClick = onDownloadTiles, enabled = !prep.busy) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (offlineTiles == null) "Offline map" else "Update map")
        }
    }

    prep.tilePlanSummary?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchingRow(stage: PrepStage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            when (stage) {
                PrepStage.MATCHING -> "Matching against OpenStreetMap…"
                // Worth naming: it explains why this one takes twice as long.
                PrepStage.RETRYING -> "Poor match — retrying more permissively…"
                PrepStage.MATCHING_REVERSE -> "Matching the reverse direction…"
                PrepStage.DONE -> "Finishing…"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TileProgressRow(tiles: TileProgress, onCancel: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Downloading map — ${tiles.done} of ${tiles.total} tiles",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Stop") }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (tiles.total == 0) 0f else tiles.done.toFloat() / tiles.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // Stopping is cheap, and saying so is what makes it feel safe to.
            "Stopping is safe — what has downloaded is kept, and starting again " +
                "picks up where this left off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Human-readable size for a download that has not happened yet. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "${(bytes / 1024.0 / 1024).roundToInt()} MB"
    else -> "${(bytes / 1024.0).roundToInt()} KB"
}
