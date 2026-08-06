package de.kettenblatt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.kettenblatt.data.Settings
import de.kettenblatt.data.Units
import de.kettenblatt.prep.TileSource
import de.kettenblatt.prep.Valhalla
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Units") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Units.entries.forEachIndexed { i, u ->
                            SegmentedButton(
                                selected = settings.units == u,
                                onClick = { onChange { it.copy(units = u) } },
                                shape = SegmentedButtonDefaults.itemShape(i, Units.entries.size),
                            ) {
                                Text(if (u == Units.METRIC) "Kilometres" else "Miles")
                            }
                        }
                    }
                }
            }

            item {
                Section("Off route") {
                    Explanation(
                        "The alert starts once you are further than the first distance " +
                            "from the route, and clears again inside the second. The gap " +
                            "between them is what stops it flapping under tree cover."
                    )
                    Spacer(Modifier.height(8.dp))
                    MetreSlider(
                        label = "Alert beyond",
                        value = settings.offRouteEnterM,
                        range = 20f..120f,
                        units = settings.units,
                    ) { v -> onChange { it.copy(offRouteEnterM = v) } }
                    MetreSlider(
                        label = "Clear within",
                        value = settings.offRouteExitM,
                        // Held below the entry distance; an inverted pair would
                        // latch the alarm on with no way back.
                        range = 10f..(settings.offRouteEnterM.toFloat() - 5f).coerceAtLeast(15f),
                        units = settings.units,
                    ) { v -> onChange { it.copy(offRouteExitM = v) } }
                }
            }

            item {
                Section("Screen") {
                    SwitchRow(
                        label = "Keep screen on while riding",
                        checked = settings.keepScreenOn,
                    ) { v -> onChange { it.copy(keepScreenOn = v) } }
                    SwitchRow(
                        label = "Dim between turns",
                        checked = settings.autoDimEnabled,
                    ) { v -> onChange { it.copy(autoDimEnabled = v) } }

                    if (settings.autoDimEnabled) {
                        Explanation(
                            "Blacks the map out when nothing needs attention, and brings " +
                                "it back for the next turn. Routes without turn cues never dim."
                        )
                        Spacer(Modifier.height(8.dp))
                        ValueSlider(
                            label = "Dim after",
                            display = "${settings.autoDimDelayMs / 1000} s",
                            value = (settings.autoDimDelayMs / 1000).toFloat(),
                            range = 5f..60f,
                        ) { v -> onChange { it.copy(autoDimDelayMs = v.roundToInt() * 1000L) } }
                        MetreSlider(
                            label = "Wake for turns within",
                            value = settings.autoDimWakeAheadM,
                            range = 100f..800f,
                            units = settings.units,
                        ) { v -> onChange { it.copy(autoDimWakeAheadM = v) } }
                    }
                }
            }

            item {
                Section("Map zoom") {
                    Explanation(
                        "How close the two following modes sit. A sideloaded tile pack " +
                            "still caps how far in the map stays sharp."
                    )
                    Spacer(Modifier.height(8.dp))
                    ValueSlider(
                        label = "Navigation",
                        display = "z${settings.navigationZoom.roundToInt()}",
                        value = settings.navigationZoom.toFloat(),
                        range = 13f..18f,
                    ) { v -> onChange { it.copy(navigationZoom = v.roundToInt().toDouble()) } }
                    ValueSlider(
                        label = "Close",
                        display = "z${settings.closeZoom.roundToInt()}",
                        value = settings.closeZoom.toFloat(),
                        range = 15f..19f,
                    ) { v -> onChange { it.copy(closeZoom = v.roundToInt().toDouble()) } }
                }
            }

            item {
                Section("Preparing routes") {
                    Explanation(
                        "Turn cues come from map-matching a route against OpenStreetMap. " +
                            "That needs a Valhalla server, and happens once per route over " +
                            "wifi — never while riding."
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.valhallaUrl,
                        onValueChange = { v -> onChange { it.copy(valhallaUrl = v.trim()) } },
                        label = { Text("Valhalla server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Explanation(
                        if (settings.valhallaUrl == Valhalla.DEFAULT_BASE_URL) {
                            "FOSSGIS's public instance: whole planet, no key, refreshed daily."
                        } else {
                            "Leave empty to return to the public instance."
                        }
                    )
                }
            }

            item {
                Section("Offline maps") {
                    Explanation(
                        "Zoom levels quadruple the tile count each step, and the corridor " +
                            "is how wide a strip either side of the route gets downloaded."
                    )
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TileSource.ALL.forEachIndexed { i, source ->
                            SegmentedButton(
                                selected = settings.tileSource == source.key,
                                onClick = { onChange { it.copy(tileSource = source.key) } },
                                shape = SegmentedButtonDefaults.itemShape(i, TileSource.ALL.size),
                            ) {
                                Text(source.name.substringBefore(' '), maxLines = 1)
                            }
                        }
                    }

                    val source = TileSource.byKey(settings.tileSource)
                    if (source.needsKey) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = settings.thunderforestKey,
                            onValueChange = { v -> onChange { it.copy(thunderforestKey = v.trim()) } },
                            label = { Text("Thunderforest API key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    ValueSlider(
                        label = "Deepest zoom",
                        display = "z${settings.tileZoomMax}",
                        value = settings.tileZoomMax.toFloat(),
                        range = 13f..source.maxZoom.toFloat(),
                    ) { v -> onChange { it.copy(tileZoomMax = v.roundToInt()) } }
                    MetreSlider(
                        label = "Corridor",
                        value = settings.tileBufferM,
                        range = 200f..2_000f,
                        units = settings.units,
                    ) { v -> onChange { it.copy(tileBufferM = v) } }
                }
            }

            item {
                TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset to defaults")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScopeAlias.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun Explanation(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MetreSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    units: Units,
    onChange: (Double) -> Unit,
) = ValueSlider(
    label = label,
    display = formatShortDistance(value, units),
    value = value.toFloat().coerceIn(range),
    range = range,
) { onChange(it.toDouble()) }

@Composable
private fun ValueSlider(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}
