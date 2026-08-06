package de.kettenblatt.ui

import androidx.compose.runtime.compositionLocalOf
import de.kettenblatt.data.Units
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Display formatting.
 *
 * Numbers deliberately follow the device locale, so a phone set to German shows
 * "1,2 km" rather than "1.2 km". The locale is passed explicitly everywhere
 * instead of relying on the default, so that intent is on the page rather than
 * implied.
 *
 * Units are a separate axis from locale: a German phone may well want miles, and
 * a US phone kilometres. They come from settings, not from the region.
 */
private fun locale(): Locale = Locale.getDefault()

private const val METRES_PER_MILE = 1609.344
private const val METRES_PER_FOOT = 0.3048
private const val METRES_PER_YARD = 0.9144

/** Units for composables that are not handed them explicitly. */
val LocalUnits = compositionLocalOf { Units.METRIC }

/** Distances read at a glance on a moving bike: short units close in, long beyond. */
fun formatDistance(metres: Double, units: Units = Units.METRIC): String = when (units) {
    Units.METRIC -> when {
        metres < 1_000 -> "${(metres / 10).roundToInt() * 10} m"
        metres < 10_000 -> String.format(locale(), "%.1f km", metres / 1000)
        else -> "${(metres / 1000).roundToInt()} km"
    }

    Units.IMPERIAL -> {
        val miles = metres / METRES_PER_MILE
        when {
            miles < 0.2 -> "${(metres / METRES_PER_YARD / 10).roundToInt() * 10} yd"
            miles < 10 -> String.format(locale(), "%.1f mi", miles)
            else -> "${miles.roundToInt()} mi"
        }
    }
}

/** Short distances for turn cues, where fine granularity matters. */
fun formatShortDistance(metres: Double, units: Units = Units.METRIC): String = when (units) {
    Units.METRIC -> when {
        metres < 100 -> "${(metres / 10).roundToInt() * 10} m"
        metres < 1_000 -> "${(metres / 50).roundToInt() * 50} m"
        else -> String.format(locale(), "%.1f km", metres / 1000)
    }

    Units.IMPERIAL -> {
        val yards = metres / METRES_PER_YARD
        when {
            yards < 100 -> "${(yards / 10).roundToInt() * 10} yd"
            yards < 880 -> "${(yards / 50).roundToInt() * 50} yd"
            else -> String.format(locale(), "%.1f mi", metres / METRES_PER_MILE)
        }
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun formatSpeed(mps: Double?, units: Units = Units.METRIC): String = when {
    mps == null -> "—"
    units == Units.METRIC -> String.format(locale(), "%.1f km/h", mps * 3.6)
    else -> String.format(locale(), "%.1f mph", mps * 3600 / METRES_PER_MILE)
}

fun formatAscent(metres: Double, units: Units = Units.METRIC): String = when (units) {
    Units.METRIC -> "${metres.roundToInt()} m"
    Units.IMPERIAL -> "${(metres / METRES_PER_FOOT).roundToInt()} ft"
}

/**
 * Clock time of arrival, which is more useful than a duration when deciding
 * whether the light will hold.
 */
fun formatEta(seconds: Long?, nowMs: Long): String {
    if (seconds == null) return "—"
    val arrival = java.util.Calendar.getInstance().apply {
        timeInMillis = nowMs + seconds * 1000
    }
    return String.format(
        locale(),
        "%02d:%02d",
        arrival.get(java.util.Calendar.HOUR_OF_DAY),
        arrival.get(java.util.Calendar.MINUTE),
    )
}

/** Human-readable turn text, preferring Valhalla's phrasing when present. */
fun maneuverLabel(type: String, street: String?, instruction: String): String = when {
    instruction.isNotBlank() -> instruction
    street != null -> "${type.replace('_', ' ')} onto $street"
    else -> type.replace('_', ' ')
}

/** Sentence-case a raw OSM surface value: "paved_smooth" -> "Paved smooth". */
fun surfaceLabel(surface: String): String =
    surface.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** "1 route", "2 routes" — small, but "1 routes" reads like a bug. */
fun plural(count: Int, singular: String): String =
    "$count $singular" + if (count == 1) "" else "s"
