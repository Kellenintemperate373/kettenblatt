package de.kettenblatt.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import de.kettenblatt.ui.theme.NaviColors

/**
 * Map markers, drawn rather than shipped as assets.
 *
 * osmdroid's stock pins are dated and sized for a phone from 2012, and the
 * position marker was a stand-in borrowed from the platform's presence icons.
 * Drawing them here keeps every marker on one visual system -- a solid shape with
 * a white keyline and a soft shadow, which stays legible over both the pale
 * greens of OpenTopoMap and the dense grey of a town centre.
 *
 * Everything is built at the device's pixel density and cached per context, so
 * this costs one allocation per marker type per session.
 */
object Markers {

    private val cache = HashMap<String, Drawable>()

    /** The rider: a chevron, so direction of travel is readable at a glance. */
    fun position(context: Context): Drawable = cached(context, "position") {
        // Bigger than the endpoint dots on purpose: this is the one marker the
        // rider looks for at a glance while moving.
        val size = dp(context, 36f)
        draw(size, size) { canvas, w, h ->
            val cx = w / 2f
            val cy = h / 2f
            val r = w * 0.42f

            shadow(canvas, cx, cy + h * 0.02f, r)

            // White disc first: the keyline is what separates the marker from a
            // dark map, and a ring drawn as a stroke disappears at this size.
            canvas.drawCircle(cx, cy, r, fill(0xFFFFFFFF.toInt()))
            canvas.drawCircle(cx, cy, r * 0.82f, fill(NaviColors.Route.toArgb()))

            // Chevron pointing up; the marker itself is rotated to the heading.
            val chevron = Path().apply {
                moveTo(cx, cy - r * 0.46f)
                lineTo(cx + r * 0.40f, cy + r * 0.44f)
                lineTo(cx, cy + r * 0.18f)
                lineTo(cx - r * 0.40f, cy + r * 0.44f)
                close()
            }
            canvas.drawPath(chevron, fill(0xFFFFFFFF.toInt()))
        }
    }

    /**
     * A small arrowhead for the route line itself.
     *
     * Drawn white with a thin dark edge rather than as another coloured shape:
     * it sits directly on the blue line, and on the slate of ground already
     * ridden, and has to read on both without competing with the rider's own
     * chevron. No shadow -- twenty of these with shadows turns the line grubby.
     */
    fun directionArrow(context: Context): Drawable = cached(context, "arrow") {
        val size = dp(context, 13f)
        draw(size, size) { canvas, w, h ->
            val cx = w / 2f
            val cy = h / 2f
            val r = w * 0.5f

            // A plain triangle, not a notched chevron: at the width of a route
            // line a notch reads as a nick taken out of the line rather than as
            // an arrowhead. Slightly taller than wide so the point is obvious.
            val head = Path().apply {
                moveTo(cx, cy - r * 0.70f)
                lineTo(cx + r * 0.46f, cy + r * 0.58f)
                lineTo(cx - r * 0.46f, cy + r * 0.58f)
                close()
            }

            // A whisper of an edge, only for where the arrow overhangs the line
            // onto pale map. Any heavier and twenty of these dirty the route.
            canvas.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = w * 0.10f
                strokeJoin = Paint.Join.ROUND
                color = 0x33000000
            })
            canvas.drawPath(head, fill(0xFFFFFFFF.toInt()))
        }
    }

    /** Where the route begins. */
    fun start(context: Context): Drawable = dot(context, "start", NaviColors.Start.toArgb())

    /** Where it ends -- deliberately darker rather than another bright colour. */
    fun finish(context: Context): Drawable = dot(context, "finish", NaviColors.Finish.toArgb())

    /** A Komoot waypoint: a pin, because it marks a place rather than a position. */
    fun waypoint(context: Context): Drawable = cached(context, "waypoint") {
        val w = dp(context, 22f)
        val h = dp(context, 30f)
        draw(w, h) { canvas, width, height ->
            val cx = width / 2f
            val r = width * 0.40f
            val cy = r + width * 0.06f

            shadow(canvas, cx, height * 0.94f, width * 0.22f)

            val pin = Path().apply {
                moveTo(cx, height * 0.98f)
                cubicTo(cx - r * 1.1f, cy + r * 0.9f, cx - r * 1.25f, cy, cx, cy)
                cubicTo(cx + r * 1.25f, cy, cx + r * 1.1f, cy + r * 0.9f, cx, height * 0.98f)
                close()
            }
            canvas.drawPath(pin, fill(0xFFFFFFFF.toInt()))
            canvas.drawCircle(cx, cy, r, fill(0xFFFFFFFF.toInt()))
            canvas.drawCircle(cx, cy, r * 0.78f, fill(NaviColors.Waypoint.toArgb()))
            canvas.drawCircle(cx, cy, r * 0.30f, fill(0xFFFFFFFF.toInt()))
        }
    }

    private fun dot(context: Context, key: String, color: Int): Drawable =
        cached(context, key) {
            val size = dp(context, 20f)
            draw(size, size) { canvas, w, h ->
                val cx = w / 2f
                val cy = h / 2f
                val r = w * 0.40f
                shadow(canvas, cx, cy + h * 0.03f, r)
                canvas.drawCircle(cx, cy, r, fill(0xFFFFFFFF.toInt()))
                canvas.drawCircle(cx, cy, r * 0.68f, fill(color))
            }
        }

    // --- drawing helpers --------------------------------------------------

    private inline fun cached(context: Context, key: String, build: () -> Drawable): Drawable =
        cache.getOrPut(key) { build() }.let {
            // BitmapDrawables carry density; re-wrap so osmdroid sizes correctly.
            it.apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }
        }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private inline fun draw(
        width: Int,
        height: Int,
        block: (Canvas, Float, Float) -> Unit,
    ): Drawable {
        val bitmap = createBitmap(width, height)
        block(Canvas(bitmap), width.toFloat(), height.toFloat())
        return BitmapDrawable(null, bitmap)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    /** A soft drop shadow, so markers read as sitting above the map. */
    private fun shadow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius * 1.06f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33000000
            style = Paint.Style.FILL
            maskFilter = android.graphics.BlurMaskFilter(
                radius * 0.35f, android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        })
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
        android.graphics.Color.argb(
            (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
        )
}
