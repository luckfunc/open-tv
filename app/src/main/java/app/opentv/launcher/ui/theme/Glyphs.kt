package app.opentv.launcher.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small set of line glyphs drawn in the spirit of SF Symbols: one stroke weight, round caps,
 * optically centered in a square box. Drawn, not bundled, so they stay crisp at every size.
 */
enum class Glyph { Wifi, Offline, Gear, Input, Speaker, SpeakerMuted, Switches, EyeSlash, AppsDrawer, Exit, Move, Info, Trash, ChevronRight, ChevronLeft, Check, Hdmi, Antenna, Av, Sun, Photo, Play, Bag, CloudSun, Calendar, Cast, Shield, Globe, Moon, Cloud, CloudRain, CloudSnow, CloudFog, CloudBolt, Paintbrush }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp, modifier: Modifier = Modifier, level: Float = 1f) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color, level) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color, level: Float = 1f) {
    val s = size.minDimension
    val w = s * 0.065f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: Color = color) =
        drawLine(c, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Round)
    // A filled cloud, raised by [lift] to leave room for rain, snow or fog under it.
    fun cloud(lift: Float = 0f) = drawPath(
        Path().apply {
            addOval(Rect(p(0.33f, 0.62f - lift), 0.17f * s))
            addOval(Rect(p(0.52f, 0.55f - lift), 0.21f * s))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0.1f * s, (0.6f - lift) * s, 0.84f * s, (0.86f - lift) * s, CornerRadius(0.13f * s)))
        },
        color,
    )

    when (glyph) {
        Glyph.Wifi -> {
            val center = p(0.5f, 0.78f)
            val radii = floatArrayOf(0.2f, 0.38f, 0.56f)
            radii.forEachIndexed { i, r ->
                val on = level >= (i + 1) / 3f - 0.01f
                drawArc(
                    color = if (on) color else color.copy(alpha = color.alpha * 0.3f),
                    startAngle = -135f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(center.x - r * s, center.y - r * s), size = Size(2 * r * s, 2 * r * s),
                    style = stroke,
                )
            }
            drawCircle(color, radius = w * 0.95f, center = center)
        }
        Glyph.Offline -> {
            drawGlyph(Glyph.Wifi, color.copy(alpha = color.alpha * 0.35f), 1f)
            line(0.18f, 0.16f, 0.82f, 0.86f)
        }
        Glyph.Paintbrush -> {
            val handle = Path().apply {
                moveTo(0.37f * s, 0.53f * s); lineTo(0.75f * s, 0.12f * s)
                quadraticTo(0.83f * s, 0.04f * s, 0.91f * s, 0.12f * s)
                quadraticTo(0.99f * s, 0.20f * s, 0.91f * s, 0.28f * s)
                lineTo(0.50f * s, 0.66f * s); close()
            }
            drawPath(handle, color, style = stroke)
            val bristles = Path().apply {
                moveTo(0.41f * s, 0.59f * s)
                cubicTo(0.12f * s, 0.53f * s, 0.31f * s, 0.84f * s, 0.08f * s, 0.91f * s)
                cubicTo(0.39f * s, 1.00f * s, 0.58f * s, 0.83f * s, 0.51f * s, 0.67f * s)
                close()
            }
            drawPath(bristles, color)
        }
        Glyph.Gear -> {
            val c = p(0.5f, 0.5f)
            val body = Path()
            val teeth = 8
            val rOuter = 0.46f * s
            val rInner = 0.35f * s
            // Trapezoid teeth around a disc, then a hole through the middle.
            for (i in 0 until teeth * 2) {
                val a0 = (i * 180f / teeth - 90f) * (Math.PI / 180).toFloat()
                val a1 = ((i + 1) * 180f / teeth - 90f) * (Math.PI / 180).toFloat()
                val r = if (i % 2 == 0) rOuter else rInner
                val shrink = if (i % 2 == 0) 0.06f else -0.06f
                val x0 = c.x + r * cos(a0 + shrink); val y0 = c.y + r * sin(a0 + shrink)
                val x1 = c.x + r * cos(a1 - shrink); val y1 = c.y + r * sin(a1 - shrink)
                if (i == 0) body.moveTo(x0, y0) else body.lineTo(x0, y0)
                body.lineTo(x1, y1)
            }
            body.close()
            val hole = Path().apply { addOval(Rect(c, 0.15f * s)) }
            drawPath(Path.combine(PathOperation.Difference, body, hole), color)
        }
        Glyph.Input -> {
            // A screen with an arrow entering it from the left.
            drawRoundRect(color, p(0.3f, 0.18f), Size(0.6f * s, 0.5f * s), CornerRadius(0.08f * s), style = stroke)
            line(0.08f, 0.43f, 0.56f, 0.43f)
            line(0.44f, 0.31f, 0.56f, 0.43f)
            line(0.44f, 0.55f, 0.56f, 0.43f)
            line(0.45f, 0.84f, 0.75f, 0.84f)
        }
        Glyph.Speaker, Glyph.SpeakerMuted -> {
            val body = Path().apply {
                moveTo(0.1f * s, 0.38f * s); lineTo(0.26f * s, 0.38f * s); lineTo(0.46f * s, 0.2f * s)
                lineTo(0.46f * s, 0.8f * s); lineTo(0.26f * s, 0.62f * s); lineTo(0.1f * s, 0.62f * s); close()
            }
            drawPath(body, color)
            if (glyph == Glyph.SpeakerMuted) {
                line(0.62f, 0.38f, 0.86f, 0.62f)
                line(0.86f, 0.38f, 0.62f, 0.62f)
            } else {
                val c = p(0.46f, 0.5f)
                listOf(0.16f, 0.3f, 0.44f).forEachIndexed { i, r ->
                    val on = level > i / 3f + 0.01f
                    drawArc(
                        color = if (on) color else color.copy(alpha = color.alpha * 0.3f),
                        startAngle = -45f, sweepAngle = 90f, useCenter = false,
                        topLeft = Offset(c.x - r * s, c.y - r * s), size = Size(2 * r * s, 2 * r * s), style = stroke,
                    )
                }
            }
        }
        Glyph.Switches -> {
            // Two toggles, the Control Center glyph.
            val h = 0.3f * s
            drawRoundRect(color, p(0.06f, 0.14f), Size(0.88f * s, h), CornerRadius(h / 2), style = stroke)
            drawCircle(color, radius = h * 0.32f, center = p(0.06f + 0.15f, 0.29f))
            drawRoundRect(color, p(0.06f, 0.56f), Size(0.88f * s, h), CornerRadius(h / 2), style = stroke)
            drawCircle(color, radius = h * 0.32f, center = p(0.94f - 0.15f, 0.71f))
        }
        Glyph.EyeSlash -> {
            val eye = Path().apply {
                moveTo(0.06f * s, 0.5f * s)
                quadraticTo(0.5f * s, 0.04f * s, 0.94f * s, 0.5f * s)
                quadraticTo(0.5f * s, 0.96f * s, 0.06f * s, 0.5f * s)
                close()
            }
            drawPath(eye, color, style = stroke)
            drawCircle(color, radius = 0.12f * s, center = p(0.5f, 0.5f))
            line(0.14f, 0.12f, 0.86f, 0.88f)
        }
        Glyph.AppsDrawer -> {
            for (x in listOf(0.2f, 0.58f)) for (y in listOf(0.12f, 0.46f)) {
                drawRoundRect(color, p(x, y), Size(0.22f * s, 0.22f * s), CornerRadius(0.04f * s), style = stroke)
            }
            val tray = Path().apply {
                moveTo(0.1f * s, 0.72f * s)
                lineTo(0.1f * s, 0.87f * s)
                quadraticTo(0.1f * s, 0.92f * s, 0.15f * s, 0.92f * s)
                lineTo(0.85f * s, 0.92f * s)
                quadraticTo(0.9f * s, 0.92f * s, 0.9f * s, 0.87f * s)
                lineTo(0.9f * s, 0.72f * s)
            }
            drawPath(tray, color, style = stroke)
        }
        Glyph.Exit -> {
            line(0.48f, 0.15f, 0.16f, 0.15f)
            line(0.16f, 0.15f, 0.16f, 0.85f)
            line(0.16f, 0.85f, 0.48f, 0.85f)
            line(0.40f, 0.5f, 0.88f, 0.5f)
            line(0.68f, 0.30f, 0.88f, 0.5f)
            line(0.88f, 0.5f, 0.68f, 0.70f)
        }
        Glyph.Move -> {
            line(0.5f, 0.08f, 0.5f, 0.92f); line(0.08f, 0.5f, 0.92f, 0.5f)
            line(0.38f, 0.2f, 0.5f, 0.08f); line(0.62f, 0.2f, 0.5f, 0.08f)
            line(0.38f, 0.8f, 0.5f, 0.92f); line(0.62f, 0.8f, 0.5f, 0.92f)
            line(0.2f, 0.38f, 0.08f, 0.5f); line(0.2f, 0.62f, 0.08f, 0.5f)
            line(0.8f, 0.38f, 0.92f, 0.5f); line(0.8f, 0.62f, 0.92f, 0.5f)
        }
        Glyph.Info -> {
            drawCircle(color, radius = 0.42f * s, center = p(0.5f, 0.5f), style = stroke)
            drawCircle(color, radius = w * 0.85f, center = p(0.5f, 0.3f))
            line(0.5f, 0.46f, 0.5f, 0.72f)
        }
        Glyph.Trash -> {
            line(0.14f, 0.24f, 0.86f, 0.24f)
            line(0.38f, 0.24f, 0.4f, 0.1f); line(0.4f, 0.1f, 0.6f, 0.1f); line(0.6f, 0.1f, 0.62f, 0.24f)
            val body = Path().apply {
                moveTo(0.22f * s, 0.24f * s); lineTo(0.27f * s, 0.9f * s); lineTo(0.73f * s, 0.9f * s); lineTo(0.78f * s, 0.24f * s)
            }
            drawPath(body, color, style = stroke)
            line(0.43f, 0.4f, 0.44f, 0.74f); line(0.57f, 0.4f, 0.56f, 0.74f)
        }
        Glyph.ChevronRight -> { line(0.36f, 0.16f, 0.66f, 0.5f); line(0.66f, 0.5f, 0.36f, 0.84f) }
        Glyph.ChevronLeft -> { line(0.64f, 0.16f, 0.34f, 0.5f); line(0.34f, 0.5f, 0.64f, 0.84f) }
        Glyph.Check -> { line(0.16f, 0.52f, 0.4f, 0.76f); line(0.4f, 0.76f, 0.86f, 0.24f) }
        Glyph.Hdmi -> {
            // The HDMI port silhouette: a wide plug with chamfered bottom corners.
            val port = Path().apply {
                moveTo(0.06f * s, 0.32f * s); lineTo(0.94f * s, 0.32f * s); lineTo(0.94f * s, 0.52f * s)
                lineTo(0.8f * s, 0.7f * s); lineTo(0.2f * s, 0.7f * s); lineTo(0.06f * s, 0.52f * s); close()
            }
            drawPath(port, color, style = stroke)
            line(0.3f, 0.47f, 0.7f, 0.47f)
        }
        Glyph.Antenna -> {
            drawRoundRect(color, p(0.1f, 0.38f), Size(0.8f * s, 0.5f * s), CornerRadius(0.08f * s), style = stroke)
            line(0.5f, 0.38f, 0.3f, 0.1f); line(0.5f, 0.38f, 0.72f, 0.12f)
        }
        Glyph.Sun -> {
            drawCircle(color, radius = 0.17f * s, center = p(0.5f, 0.5f))
            for (i in 0 until 8) {
                val a = (i * 45f) * (Math.PI / 180).toFloat()
                line(0.5f + 0.29f * cos(a), 0.5f + 0.29f * sin(a), 0.5f + 0.41f * cos(a), 0.5f + 0.41f * sin(a))
            }
        }
        Glyph.Photo -> {
            drawRoundRect(color, p(0.08f, 0.16f), Size(0.84f * s, 0.68f * s), CornerRadius(0.1f * s), style = stroke)
            val hills = Path().apply {
                moveTo(0.14f * s, 0.76f * s); lineTo(0.38f * s, 0.48f * s); lineTo(0.56f * s, 0.66f * s)
                lineTo(0.68f * s, 0.54f * s); lineTo(0.86f * s, 0.76f * s); close()
            }
            drawPath(hills, color)
            drawCircle(color, radius = 0.07f * s, center = p(0.7f, 0.34f))
        }
        Glyph.Play -> {
            val tri = Path().apply {
                moveTo(0.26f * s, 0.14f * s); lineTo(0.86f * s, 0.5f * s); lineTo(0.26f * s, 0.86f * s); close()
            }
            drawPath(tri, color)
        }
        Glyph.Av -> {
            drawCircle(color, radius = 0.3f * s, center = p(0.5f, 0.5f), style = stroke)
            drawCircle(color, radius = 0.09f * s, center = p(0.5f, 0.5f))
        }
        Glyph.Bag -> {
            // A shopping bag, the app store.
            drawRoundRect(color, p(0.14f, 0.34f), Size(0.72f * s, 0.56f * s), CornerRadius(0.09f * s), style = stroke)
            drawArc(color, 180f, 180f, false, p(0.33f, 0.1f), Size(0.34f * s, 0.4f * s), style = stroke)
        }
        Glyph.CloudSun -> {
            // The sun behind a cloud: rays first, the cloud filled over them.
            val sun = p(0.66f, 0.32f)
            drawCircle(color, radius = 0.12f * s, center = sun, style = stroke)
            for (i in 0 until 8) {
                val a = (i * 45f) * (Math.PI / 180).toFloat()
                line(0.66f + 0.19f * cos(a), 0.32f + 0.19f * sin(a), 0.66f + 0.26f * cos(a), 0.32f + 0.26f * sin(a))
            }
            cloud()
        }
        Glyph.Moon -> {
            // A crescent: a disc with a smaller one taken out of its upper right.
            val disc = Path().apply { addOval(Rect(p(0.48f, 0.52f), 0.34f * s)) }
            val bite = Path().apply { addOval(Rect(p(0.66f, 0.36f), 0.28f * s)) }
            drawPath(Path.combine(PathOperation.Difference, disc, bite), color)
        }
        Glyph.Cloud -> cloud(0.1f)
        Glyph.CloudRain -> {
            cloud(0.2f)
            line(0.36f, 0.76f, 0.3f, 0.9f); line(0.54f, 0.76f, 0.48f, 0.9f); line(0.72f, 0.76f, 0.66f, 0.9f)
        }
        Glyph.CloudSnow -> {
            cloud(0.2f)
            for ((x, y) in listOf(0.32f to 0.8f, 0.52f to 0.8f, 0.72f to 0.8f, 0.42f to 0.92f, 0.62f to 0.92f)) {
                drawCircle(color, radius = w * 0.7f, center = p(x, y))
            }
        }
        Glyph.CloudFog -> {
            cloud(0.2f)
            line(0.16f, 0.8f, 0.84f, 0.8f); line(0.26f, 0.92f, 0.74f, 0.92f)
        }
        Glyph.CloudBolt -> {
            cloud(0.2f)
            val bolt = Path().apply {
                moveTo(0.56f * s, 0.64f * s); lineTo(0.4f * s, 0.84f * s); lineTo(0.5f * s, 0.84f * s)
                lineTo(0.44f * s, 0.98f * s); lineTo(0.64f * s, 0.76f * s); lineTo(0.54f * s, 0.76f * s); close()
            }
            drawPath(bolt, color)
        }
        Glyph.Calendar -> {
            drawRoundRect(color, p(0.1f, 0.18f), Size(0.8f * s, 0.7f * s), CornerRadius(0.1f * s), style = stroke)
            line(0.1f, 0.38f, 0.9f, 0.38f)
            line(0.32f, 0.08f, 0.32f, 0.24f); line(0.68f, 0.08f, 0.68f, 0.24f)
            for (x in listOf(0.3f, 0.5f, 0.7f)) for (y in listOf(0.54f, 0.72f)) drawCircle(color, radius = w * 0.75f, center = p(x, y))
        }
        Glyph.Cast -> {
            // A screen with a triangle rising into a gap in its lower edge, as AirPlay draws it.
            val screen = Path().apply {
                moveTo(0.34f * s, 0.7f * s); lineTo(0.2f * s, 0.7f * s)
                quadraticTo(0.08f * s, 0.7f * s, 0.08f * s, 0.58f * s); lineTo(0.08f * s, 0.24f * s)
                quadraticTo(0.08f * s, 0.12f * s, 0.2f * s, 0.12f * s); lineTo(0.8f * s, 0.12f * s)
                quadraticTo(0.92f * s, 0.12f * s, 0.92f * s, 0.24f * s); lineTo(0.92f * s, 0.58f * s)
                quadraticTo(0.92f * s, 0.7f * s, 0.8f * s, 0.7f * s); lineTo(0.66f * s, 0.7f * s)
            }
            drawPath(screen, color, style = stroke)
            val tri = Path().apply {
                moveTo(0.5f * s, 0.6f * s); lineTo(0.72f * s, 0.9f * s); lineTo(0.28f * s, 0.9f * s); close()
            }
            drawPath(tri, color)
        }
        Glyph.Shield -> {
            val shield = Path().apply {
                moveTo(0.5f * s, 0.07f * s); lineTo(0.85f * s, 0.2f * s); lineTo(0.85f * s, 0.46f * s)
                cubicTo(0.85f * s, 0.7f * s, 0.68f * s, 0.85f * s, 0.5f * s, 0.93f * s)
                cubicTo(0.32f * s, 0.85f * s, 0.15f * s, 0.7f * s, 0.15f * s, 0.46f * s)
                lineTo(0.15f * s, 0.2f * s); close()
            }
            drawPath(shield, color, style = stroke)
            line(0.35f, 0.5f, 0.46f, 0.62f); line(0.46f, 0.62f, 0.66f, 0.38f)
        }
        Glyph.Globe -> {
            drawCircle(color, radius = 0.42f * s, center = p(0.5f, 0.5f), style = stroke)
            drawOval(color, p(0.31f, 0.08f), Size(0.38f * s, 0.84f * s), style = stroke)
            line(0.08f, 0.5f, 0.92f, 0.5f)
            line(0.16f, 0.29f, 0.84f, 0.29f); line(0.16f, 0.71f, 0.84f, 0.71f)
        }
    }
}
