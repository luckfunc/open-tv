package app.opentv.launcher.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap

/**
 * OpenTV's own wallpapers: soft fields of coloured light, generated on the TV rather than shipped
 * as pictures. Each is a dark base with a few large glows, blurred until no edge is left.
 */
class GradientPreset(
    val key: String,
    val nameZh: String,
    val nameEn: String,
    val base: Int,
    /** (colour, centre x, centre y, radius) with positions and radius as fractions of the width. */
    val glows: List<Glow>,
)

class Glow(val color: Int, val x: Float, val y: Float, val r: Float)

object Gradients {
    val presets = listOf(
        GradientPreset("aurora", "极光", "Aurora", 0xFF070B1E.toInt(), listOf(
            Glow(0xFF1FD1A5.toInt(), 0.18f, 0.32f, 0.42f), Glow(0xFF5B3FD9.toInt(), 0.74f, 0.22f, 0.48f), Glow(0xFF0E6FFF.toInt(), 0.52f, 0.86f, 0.5f),
        )),
        GradientPreset("sunset", "日落", "Sunset", 0xFF17081A.toInt(), listOf(
            Glow(0xFFFF6B3D.toInt(), 0.24f, 0.62f, 0.46f), Glow(0xFFFF2E7E.toInt(), 0.7f, 0.3f, 0.44f), Glow(0xFFFFC15E.toInt(), 0.5f, 0.98f, 0.4f),
        )),
        GradientPreset("ocean", "深海", "Deep Sea", 0xFF020F1E.toInt(), listOf(
            Glow(0xFF0A5BD8.toInt(), 0.3f, 0.25f, 0.5f), Glow(0xFF00B3C6.toInt(), 0.78f, 0.7f, 0.42f), Glow(0xFF213E9A.toInt(), 0.1f, 0.9f, 0.4f),
        )),
        GradientPreset("violet", "紫晶", "Amethyst", 0xFF0E0824.toInt(), listOf(
            Glow(0xFF8E5CFF.toInt(), 0.28f, 0.3f, 0.46f), Glow(0xFFFF5CAA.toInt(), 0.82f, 0.55f, 0.38f), Glow(0xFF3D2DB3.toInt(), 0.55f, 0.95f, 0.5f),
        )),
        GradientPreset("forest", "森林", "Forest", 0xFF031109.toInt(), listOf(
            Glow(0xFF1F8A5B.toInt(), 0.22f, 0.4f, 0.46f), Glow(0xFF9BD15A.toInt(), 0.76f, 0.2f, 0.34f), Glow(0xFF0E4D3C.toInt(), 0.62f, 0.85f, 0.5f),
        )),
        GradientPreset("dune", "沙丘", "Dune", 0xFF1A110A.toInt(), listOf(
            Glow(0xFFE3A35C.toInt(), 0.3f, 0.3f, 0.44f), Glow(0xFFC8643B.toInt(), 0.78f, 0.62f, 0.44f), Glow(0xFFF2D3A0.toInt(), 0.12f, 0.92f, 0.34f),
        )),
        GradientPreset("rose", "玫瑰", "Rose", 0xFF1A0C10.toInt(), listOf(
            Glow(0xFFF2A0A8.toInt(), 0.7f, 0.28f, 0.42f), Glow(0xFFD46A7E.toInt(), 0.25f, 0.6f, 0.46f), Glow(0xFFF7D4C2.toInt(), 0.55f, 0.95f, 0.3f),
        )),
        GradientPreset("graphite", "石墨", "Graphite", 0xFF0B0B0D.toInt(), listOf(
            Glow(0xFF3A3A44.toInt(), 0.25f, 0.25f, 0.5f), Glow(0xFF5E5E6A.toInt(), 0.8f, 0.5f, 0.42f), Glow(0xFF23232C.toInt(), 0.5f, 0.95f, 0.5f),
        )),
    )

    fun byKey(key: String) = presets.firstOrNull { it.key == key }

    /** Renders a preset. Small and blurred first, then scaled up: the light has no edges to show. */
    fun render(preset: GradientPreset, width: Int, height: Int): Bitmap {
        val w = 240
        val h = 135
        val small = createBitmap(w, h)
        val canvas = Canvas(small)
        canvas.drawColor(preset.base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (g in preset.glows) {
            val r = g.r * w
            paint.shader = RadialGradient(g.x * w, g.y * h, r, g.color, g.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(g.x * w, g.y * h, r, paint)
        }
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { Images.boxBlur(px, w, h, 6) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        val out = createBitmap(width, height)
        Canvas(out).drawBitmap(small, null, android.graphics.Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
        Images.dither(out)
        return out
    }
}
