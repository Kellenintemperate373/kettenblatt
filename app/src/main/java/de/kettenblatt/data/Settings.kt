package de.kettenblatt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Units { METRIC, IMPERIAL }

/**
 * Everything the rider can change.
 *
 * The defaults are the values arrived at by measurement elsewhere in the app --
 * the off-route hysteresis pair, the dim delay, the navigation zoom -- so leaving
 * this screen untouched gives exactly the behaviour that was tuned.
 */
data class Settings(
    val units: Units = Units.METRIC,
    /** Cross-track distance that starts the off-route timer. */
    val offRouteEnterM: Double = 40.0,
    /** And the closer distance that clears it, giving hysteresis. */
    val offRouteExitM: Double = 25.0,
    val autoDimEnabled: Boolean = true,
    val autoDimDelayMs: Long = 12_000,
    /** A maneuver closer than this wakes the screen. */
    val autoDimWakeAheadM: Double = 300.0,
    val navigationZoom: Double = 16.0,
    val closeZoom: Double = 18.0,
    val keepScreenOn: Boolean = true,

    // --- preparing routes on the phone ---

    /**
     * Where map matching happens. FOSSGIS's public instance by default: a
     * whole-planet graph, no API key, refreshed daily. Point it at a Valhalla
     * of your own -- Docker on the LAN, or a hosted one -- by changing this.
     */
    val valhallaUrl: String = de.kettenblatt.prep.Valhalla.DEFAULT_BASE_URL,
    val tileSource: String = "opentopomap",
    val tileZoomMin: Int = 12,
    val tileZoomMax: Int = 16,
    /** Corridor half-width for an offline pack. */
    val tileBufferM: Double = 500.0,
    val thunderforestKey: String = "",
) {
    /** Kept apart so an edited pair can never invert and latch the alarm on. */
    val isHysteresisSane: Boolean get() = offRouteExitM < offRouteEnterM

    /** Zoom levels grow fourfold each step, so an inverted range is not a typo to shrug at. */
    val isZoomRangeSane: Boolean get() = tileZoomMin in 1..20 && tileZoomMax in tileZoomMin..20
}

/**
 * Settings as plain strings, so the mapping can be tested without a Context.
 *
 * Unknown or malformed values fall back to the default rather than throwing: a
 * settings file should never be able to stop the app opening.
 */
object SettingsCodec {
    const val UNITS = "units"
    const val OFF_ROUTE_ENTER = "offRouteEnterM"
    const val OFF_ROUTE_EXIT = "offRouteExitM"
    const val AUTO_DIM = "autoDimEnabled"
    const val AUTO_DIM_DELAY = "autoDimDelayMs"
    const val AUTO_DIM_WAKE = "autoDimWakeAheadM"
    const val NAV_ZOOM = "navigationZoom"
    const val CLOSE_ZOOM = "closeZoom"
    const val KEEP_SCREEN_ON = "keepScreenOn"
    const val VALHALLA_URL = "valhallaUrl"
    const val TILE_SOURCE = "tileSource"
    const val TILE_ZOOM_MIN = "tileZoomMin"
    const val TILE_ZOOM_MAX = "tileZoomMax"
    const val TILE_BUFFER = "tileBufferM"
    const val THUNDERFOREST_KEY = "thunderforestKey"

    fun decode(get: (String) -> String?): Settings {
        val d = Settings()
        val decoded = Settings(
            units = get(UNITS)?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: d.units,
            offRouteEnterM = get(OFF_ROUTE_ENTER)?.toDoubleOrNull() ?: d.offRouteEnterM,
            offRouteExitM = get(OFF_ROUTE_EXIT)?.toDoubleOrNull() ?: d.offRouteExitM,
            autoDimEnabled = get(AUTO_DIM)?.toBooleanStrictOrNull() ?: d.autoDimEnabled,
            autoDimDelayMs = get(AUTO_DIM_DELAY)?.toLongOrNull() ?: d.autoDimDelayMs,
            autoDimWakeAheadM = get(AUTO_DIM_WAKE)?.toDoubleOrNull() ?: d.autoDimWakeAheadM,
            navigationZoom = get(NAV_ZOOM)?.toDoubleOrNull() ?: d.navigationZoom,
            closeZoom = get(CLOSE_ZOOM)?.toDoubleOrNull() ?: d.closeZoom,
            keepScreenOn = get(KEEP_SCREEN_ON)?.toBooleanStrictOrNull() ?: d.keepScreenOn,
            // An empty or whitespace URL would fail at the worst moment, with a
            // route half prepared; fall back to the working default instead.
            valhallaUrl = get(VALHALLA_URL)?.trim()?.ifEmpty { null } ?: d.valhallaUrl,
            tileSource = get(TILE_SOURCE)?.trim()?.ifEmpty { null } ?: d.tileSource,
            tileZoomMin = get(TILE_ZOOM_MIN)?.toIntOrNull() ?: d.tileZoomMin,
            tileZoomMax = get(TILE_ZOOM_MAX)?.toIntOrNull() ?: d.tileZoomMax,
            tileBufferM = get(TILE_BUFFER)?.toDoubleOrNull() ?: d.tileBufferM,
            thunderforestKey = get(THUNDERFOREST_KEY) ?: d.thunderforestKey,
        )
        // An inverted pair would leave the rider permanently off route with no
        // way back, so refuse it rather than store it.
        val sane = if (decoded.isHysteresisSane) decoded else decoded.copy(
            offRouteEnterM = d.offRouteEnterM,
            offRouteExitM = d.offRouteExitM,
        )
        return if (sane.isZoomRangeSane) sane else sane.copy(
            tileZoomMin = d.tileZoomMin,
            tileZoomMax = d.tileZoomMax,
        )
    }

    fun encode(s: Settings): Map<String, String> = mapOf(
        UNITS to s.units.name,
        OFF_ROUTE_ENTER to s.offRouteEnterM.toString(),
        OFF_ROUTE_EXIT to s.offRouteExitM.toString(),
        AUTO_DIM to s.autoDimEnabled.toString(),
        AUTO_DIM_DELAY to s.autoDimDelayMs.toString(),
        AUTO_DIM_WAKE to s.autoDimWakeAheadM.toString(),
        NAV_ZOOM to s.navigationZoom.toString(),
        CLOSE_ZOOM to s.closeZoom.toString(),
        KEEP_SCREEN_ON to s.keepScreenOn.toString(),
        VALHALLA_URL to s.valhallaUrl,
        TILE_SOURCE to s.tileSource,
        TILE_ZOOM_MIN to s.tileZoomMin.toString(),
        TILE_ZOOM_MAX to s.tileZoomMax.toString(),
        TILE_BUFFER to s.tileBufferM.toString(),
        THUNDERFOREST_KEY to s.thunderforestKey,
    )
}

/**
 * Reads and writes [Settings].
 *
 * Deliberately SharedPreferences rather than DataStore: this is a handful of
 * scalars read once and changed rarely, and the app already initialises
 * PreferenceManager for osmdroid. A new dependency would buy nothing.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("kettenblatt", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SettingsCodec.decode { prefs.getString(it, null) })
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    fun update(change: (Settings) -> Settings) {
        val next = change(_state.value)
            .let { if (it.isHysteresisSane) it else it.copy(offRouteExitM = it.offRouteEnterM * 0.6) }
            .let { if (it.isZoomRangeSane) it else it.copy(tileZoomMax = it.tileZoomMin) }
        prefs.edit().apply {
            SettingsCodec.encode(next).forEach { (k, v) -> putString(k, v) }
        }.apply()
        _state.value = next
    }

    fun reset() = update { Settings() }
}
