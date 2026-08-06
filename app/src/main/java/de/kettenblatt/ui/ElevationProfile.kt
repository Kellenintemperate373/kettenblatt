package de.kettenblatt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.kettenblatt.data.Route
import kotlin.math.roundToInt

/**
 * Elevation against distance, with the ridden portion shaded and a marker at the
 * current position.
 *
 * The track is bucketed rather than sampled: keeping the min and max of each
 * bucket preserves peaks and troughs that plain decimation would drop, which is
 * the difference between a profile that shows the climbs and one that flattens
 * them.
 */
@Composable
fun ElevationProfile(
    route: Route,
    progress: Double,
    modifier: Modifier = Modifier,
    buckets: Int = 200,
) {
    val profile = remember(route, buckets) { buildProfile(route, buckets) }
    if (profile.isEmpty()) return

    val minEle = profile.minOf { it.low }
    val maxEle = profile.maxOf { it.high }
    val range = (maxEle - minEle).coerceAtLeast(1.0)

    val accent = MaterialTheme.colorScheme.primary
    val covered = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        // Naming the range turns a decorative squiggle into a readable chart.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "ELEVATION",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Text(
                "${minEle.roundToInt()}–${maxEle.roundToInt()} m",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }

        Spacer(Modifier.height(6.dp))

        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            fun x(fraction: Double) = (fraction * size.width).toFloat()
            fun y(ele: Double) = (size.height * (1.0 - (ele - minEle) / range)).toFloat()

            val outline = Path().apply {
                moveTo(x(profile.first().at), y(profile.first().high))
                profile.forEach { lineTo(x(it.at), y(it.high)) }
            }
            val filled = Path().apply {
                addPath(outline)
                lineTo(x(profile.last().at), size.height)
                lineTo(x(profile.first().at), size.height)
                close()
            }

            drawPath(filled, accent.copy(alpha = 0.16f))
            drawPath(outline, accent, style = Stroke(width = 2.5f))

            // Everything already ridden recedes, matching the map's split line.
            if (progress > 0) {
                drawRect(
                    color = covered.copy(alpha = 0.22f),
                    size = androidx.compose.ui.geometry.Size(x(progress), size.height),
                )
                val here = x(progress)
                drawLine(
                    color = accent,
                    start = Offset(here, 0f),
                    end = Offset(here, size.height),
                    strokeWidth = 2f,
                )
                drawCircle(color = accent, radius = 4.5f, center = Offset(here, y(elevationAt(profile, progress))))
            }
        }
    }
}

/** Elevation at a fraction along the route, for placing the position dot. */
private fun elevationAt(profile: List<Bucket>, progress: Double): Double {
    val i = (progress * (profile.size - 1)).toInt().coerceIn(0, profile.size - 1)
    return profile[i].high
}

private data class Bucket(val at: Double, val low: Double, val high: Double)

private fun buildProfile(route: Route, buckets: Int): List<Bucket> {
    val total = route.distanceM
    if (total <= 0 || route.points.size < 2) return emptyList()

    val lows = DoubleArray(buckets) { Double.MAX_VALUE }
    val highs = DoubleArray(buckets) { -Double.MAX_VALUE }
    var any = false

    route.points.forEachIndexed { i, p ->
        if (p.ele.isNaN()) return@forEachIndexed
        val b = ((route.cumDistM[i] / total) * (buckets - 1)).toInt().coerceIn(0, buckets - 1)
        lows[b] = minOf(lows[b], p.ele)
        highs[b] = maxOf(highs[b], p.ele)
        any = true
    }
    if (!any) return emptyList()

    // Carry the previous value across buckets no point landed in, so the line
    // stays continuous on sparsely sampled stretches.
    val out = ArrayList<Bucket>(buckets)
    var lastLow = lows.first { it != Double.MAX_VALUE }
    var lastHigh = highs.first { it != -Double.MAX_VALUE }
    for (b in 0 until buckets) {
        if (lows[b] != Double.MAX_VALUE) {
            lastLow = lows[b]
            lastHigh = highs[b]
        }
        out.add(Bucket(b.toDouble() / (buckets - 1), lastLow, lastHigh))
    }
    return out
}
