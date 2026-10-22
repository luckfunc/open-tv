package app.opentv.launcher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.annotation.RequiresApi
import android.os.Build
import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.opentv.launcher.ui.theme.Glyph
import app.opentv.launcher.ui.theme.drawGlyph
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.opentv.launcher.wallpaper.Images
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a tile draws. tvOS icons are layered so they can shift against each other when focused;
 * Android's adaptive icons happen to be layered the same way, so those keep their two layers here.
 *
 * - [back] always fills the tile: the app's TV banner, the adaptive icon's background layer, or a
 *   plate in the icon's own color.
 * - [fore] is drawn on top at [foreAt] (fractions of the tile), and moves with parallax. It is cut
 *   down to its visible pixels: tiles are drawn every frame, and the TV's GPU is short of fill rate.
 */
class TileArt(
    val back: ImageBitmap,
    val fore: ImageBitmap?,
    val foreAt: androidx.compose.ui.geometry.Rect,
    val tint: Color,
    /**
     * The whole home backdrop for this app, baked small: its blurred artwork as ambient light plus
     * the shading that keeps the clock and grid legible. Scaled up to full screen it costs the GPU
     * one textured draw per frame, which matters on TV chips.
     */
    val backdrop: ImageBitmap,
)

object TileArtFactory {
    /**
     * Pixel size of the artwork: the tile at its focused size on this screen, so the lifted tile is
     * still pixel-sharp. 352×198 on a 1080p UI, twice that on a TV that draws its UI at 4K.
     */
    @Volatile var W = 352; private set
    @Volatile var H = 198; private set

    /** Called with [app.opentv.launcher.ui.theme.canvasDensity] before any tile is built. */
    fun useCanvasDensity(density: Float) {
        // 176dp: the 147dp tile at its 1.15× focus scale, with a little to spare.
        W = (176 * density).roundToInt().coerceIn(352, 704)
        H = W * 9 / 16
    }

    /**
     * Icons drawn in OpenTV's one look ([unify]) rather than in their own colors. Banners are
     * the app's own art for TV and are left as they are.
     */
    @Volatile var unified = true

    /** How large a logo is, as the side of the square of the same area, in tile heights. */
    private const val LOGO = 0.5f

    /** The resolution an icon is sampled at to find its logo. */
    private const val SAMPLE = 256

    private const val GRAPHITE_TOP = 0xFF3A3A3C.toInt()
    private const val GRAPHITE_BOTTOM = 0xFF242426.toInt()

    fun fromBanner(banner: Drawable): TileArt {
        val back = createBitmap(W, H)
        Canvas(back).apply {
            drawColor(GRAPHITE_BOTTOM)
            drawCover(banner, W, H)
        }
        return finish(back, null)
    }

    /**
     * A TV input's tile, in the spirit of an app icon: the connector drawn in white over a gradient
     * that tells the kind apart (HDMI blue, DP violet, tuner teal, AV amber), the input's name under it, and
     * the HDMI-CEC device name (e.g. "Apple TV") as a second line when there is one.
     */
    fun forSource(kind: app.opentv.launcher.system.SourceKind, label: String, device: String?): TileArt {
        val (top, bottom) = when (kind) {
            app.opentv.launcher.system.SourceKind.Hdmi -> 0xFF3B6FD8.toInt() to 0xFF1D3A86.toInt()
            app.opentv.launcher.system.SourceKind.DisplayPort -> 0xFF6E5BD8.toInt() to 0xFF3A2D86.toInt()
            app.opentv.launcher.system.SourceKind.Tuner -> 0xFF1FA88E.toInt() to 0xFF0E5C55.toInt()
            app.opentv.launcher.system.SourceKind.Av -> 0xFFE0912F.toInt() to 0xFF9A4F12.toInt()
            app.opentv.launcher.system.SourceKind.Other -> 0xFF5E6470.toInt() to 0xFF30343C.toInt()
        }
        val back = createBitmap(W, H)
        Canvas(back).apply {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
            // Soft light from the top left, as on OpenTV's own icon.
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = android.graphics.RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = H * 0.035f
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            }
            val two = device != null
            val cx = W / 2f
            val cy = H * (if (two) 0.3f else 0.36f)
            val u = H * 0.1f
            when (kind) {
                app.opentv.launcher.system.SourceKind.Hdmi, app.opentv.launcher.system.SourceKind.DisplayPort -> {
                    // The HDMI plug face (also for DP, whose plug looks much the same): a wide trapezoid with chamfered lower corners.
                    val p = android.graphics.Path().apply {
                        moveTo(cx - 2.2f * u, cy - 0.9f * u)
                        lineTo(cx + 2.2f * u, cy - 0.9f * u)
                        lineTo(cx + 2.2f * u, cy + 0.15f * u)
                        lineTo(cx + 1.5f * u, cy + 0.9f * u)
                        lineTo(cx - 1.5f * u, cy + 0.9f * u)
                        lineTo(cx - 2.2f * u, cy + 0.15f * u)
                        close()
                    }
                    drawPath(p, white)
                    drawLine(cx - 1.3f * u, cy - 0.05f * u, cx + 1.3f * u, cy - 0.05f * u, white)
                }
                app.opentv.launcher.system.SourceKind.Tuner -> {
                    // An antenna over a screen edge.
                    drawLine(cx, cy + 0.9f * u, cx, cy - 0.3f * u, white)
                    drawLine(cx, cy - 0.3f * u, cx - 1.1f * u, cy - 1.1f * u, white)
                    drawLine(cx, cy - 0.3f * u, cx + 1.1f * u, cy - 1.1f * u, white)
                    drawLine(cx - 1.6f * u, cy + 0.9f * u, cx + 1.6f * u, cy + 0.9f * u, white)
                }
                else -> {
                    // RCA jack: a ring with a pin.
                    drawCircle(cx, cy, 0.95f * u, white)
                    drawCircle(cx, cy, 0.25f * u, Paint(white).apply { style = Paint.Style.FILL })
                }
            }
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = H * 0.17f; textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            drawText(label, cx, H * (if (two) 0.66f else 0.74f), title)
            if (device != null) {
                val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = H * 0.12f; textAlign = Paint.Align.CENTER }
                drawText(device, cx, H * 0.84f, sub)
            }
        }
        return finish(back, null)
    }

    /** One of OpenTV's own glyphs in white over [top] to [bottom]; also for [TileRecipe.glyph]. */
    fun glyphTile(top: Int, bottom: Int, glyph: Glyph): TileArt {
        val back = colorTile(top, bottom)
        // The glyph on its own layer, so it shifts against the color when the tile leans.
        val g = H * 0.46f
        val fore = layer {
            translate((W - g) / 2f, (H - g) / 2f)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(this), Size(g, g)) {
                drawGlyph(glyph, Color.White)
            }
        }
        return finish(back, fore)
    }

    /** OpenTV's own tile color: a diagonal gradient with soft light from the top left. */
    private fun colorTile(top: Int, bottom: Int): Bitmap = layer {
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = android.graphics.RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }

    /** Normalize only alpha padding; preserve the complete native composite and its colors. */
    fun fromNativeIcon(icon: Drawable): TileArt {
        val iw = icon.intrinsicWidth.takeIf { it > 0 } ?: H
        val ih = icon.intrinsicHeight.takeIf { it > 0 } ?: H
        val sampleScale = minOf(1f, 512f / max(iw, ih))
        val sw = (iw * sampleScale).roundToInt().coerceAtLeast(1)
        val sh = (ih * sampleScale).roundToInt().coerceAtLeast(1)
        val sample = createBitmap(sw, sh)
        val previous = android.graphics.Rect(icon.bounds)
        try {
            try {
                icon.setBounds(0, 0, sw, sh)
                icon.draw(Canvas(sample))
            } finally {
                icon.bounds = previous
            }
            val pixels = IntArray(sw * sh)
            sample.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            var left = sw; var top = sh; var right = -1; var bottom = -1
            var luminance = 0.0; var count = 0
            for (y in 0 until sh) for (x in 0 until sw) {
                val c = pixels[y * sw + x]
                if (c ushr 24 != 0) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                    luminance += ColorUtils.calculateLuminance(c or 0xFF000000.toInt())
                    count++
                }
            }
            val cw = right - left + 1
            val ch = bottom - top + 1
            fun edgeColor(fraction: Float): Int? {
                if (cw <= 0 || ch <= 0) return null
                val y = (top + ch * fraction).roundToInt().coerceIn(top, bottom)
                val colors = ((left + cw / 4)..(right - cw / 4)).map { pixels[y * sw + it] }
                val opaque = colors.filter { it ushr 24 == 255 }
                if (opaque.isEmpty() || opaque.size < colors.size * 0.8f) return null
                fun channel(shift: Int) = opaque.map { it shr shift and 255 }.sorted()[opaque.size / 2]
                val candidate = android.graphics.Color.rgb(channel(16), channel(8), channel(0))
                val matching = opaque.count { color -> listOf(0, 8, 16).all { shift ->
                    kotlin.math.abs((color shr shift and 255) - (candidate shr shift and 255)) <= 32
                } }
                return candidate.takeIf { matching >= colors.size * 0.8f }
            }
            // Extend a square icon's own plate across the tile, without stretching its symbol.
            val plateTop = edgeColor(0.02f)
            val plateBottom = edgeColor(0.98f)
            val extendPlate = cw > 0 && ch > 0 && kotlin.math.abs(cw - ch) <= max(cw, ch) * 0.15f &&
                plateTop != null && plateBottom != null
            val back = createBitmap(W, H)
            Canvas(back).apply {
                if (extendPlate) {
                    drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                        shader = LinearGradient(0f, H * 0.02f, 0f, H * 0.98f,
                            plateTop!!, plateBottom!!, Shader.TileMode.CLAMP)
                    })
                } else drawColor(if (count > 0 && luminance / count > 0.45) GRAPHITE_BOTTOM else 0xFFF0F0F2.toInt())
                if (right >= left && bottom >= top) {
                    val scale = if (extendPlate) minOf(W.toFloat() / cw, H.toFloat() / ch)
                        else minOf(W * 0.72f / cw, H * 0.64f / ch)
                    val dw = cw * scale; val dh = ch * scale
                    drawBitmap(sample, android.graphics.Rect(left, top, right + 1, bottom + 1),
                        RectF((W - dw) / 2f, (H - dh) / 2f, (W + dw) / 2f, (H + dh) / 2f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
            return finish(back, null)
        } finally {
            sample.recycle()
        }
    }

    fun fromIcon(icon: Drawable, recipe: TileRecipe? = null): TileArt {
        if (recipe?.top == null && Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable && icon.foreground != null) adaptive(icon)?.let { return it }
        return plate(icon, recipe) ?: legacy(icon, recipe)
    }

    /**
     * Null when the foreground ignores the adaptive safe zone (a legacy icon wrapped as adaptive),
     * or when the background has shapes of its own (a disc the logo sits in, Google's Clock):
     * the layers only line up drawn together, so those go on as a whole icon.
     */
    @RequiresApi(26)
    private fun adaptive(icon: AdaptiveIconDrawable): TileArt? {
        val logo = visible(raster(icon.foreground, SAMPLE), SAMPLE) ?: return null
        // A logo inside the 66-unit safe zone of the 108-unit layer spans at most about 0.61.
        if (max(logo.width(), logo.height()) > 0.7f) return null
        val bg = icon.background
        val bgPx = bg?.let { raster(it, 32) }
        if (bgPx != null && !isFlat(bgPx)) return null
        val (fore, at) = placeLogo(icon.foreground, ::visible) ?: return null
        val color = bgPx?.get(bgPx.size / 2)?.takeIf { it ushr 24 > 200 } ?: GRAPHITE_BOTTOM
        if (unified && !multiColor(fore, at, color)) return unify(fore, at, color, color)
        val back = layer {
            drawColor(GRAPHITE_BOTTOM)
            bg?.let {
                // The background layer is a square; cover the wide tile with it.
                it.setBounds(0, (H - W) / 2, W, (H - W) / 2 + W)
                it.draw(this)
            }
        }
        return finish(back, fore)
    }

    /**
     * A legacy icon that is a filled rounded square (most China-market apps ship these: a logo on a
     * white or colored plate). Drawn small in the middle it reads as an icon on a tile, not as the
     * tile. Instead the plate's own color is carried across the whole tile, top edge to bottom
     * edge, and the logo on it is sized like every other logo, the plate fading into the tile
     * around it. Null for any other shape.
     */
    private fun plate(icon: Drawable, recipe: TileRecipe?): TileArt? {
        val n = SAMPLE
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val l = box.left; val t = box.top; val r = box.right - 1; val bt = box.bottom - 1
        val w = box.width()
        val h = box.height()
        // A square at least half the canvas, and nearly all of it solid: a rounded square fills
        // about 96% of its box, a circle 79%.
        var filled = 0
        for (y in t..bt) for (x in l..r) if (solid(px[y * n + x])) filled++
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f || filled < w * h * 0.9f) return null
        // The plate's color along its top and bottom edges, inside the corner rounding: the
        // median, so a badge in a corner (WPS's "TV") or a logo touching the edge does not tint it.
        fun band(fromY: Int, toY: Int): Int? {
            val xs = (l + w / 6)..(r - w / 6)
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (y in fromY..toY) for (x in xs) {
                val c = px[y * n + x]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
            }
            val median = android.graphics.Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
            // Most of the edge has to be that color: a picture running into the edge has no plate.
            var near = 0
            for (y in fromY..toY) for (x in xs) if (distance(px[y * n + x], median) <= 48) near++
            return if (near < rs.size * 0.6f) null else median
        }
        val edge = (h * 0.04f).roundToInt()
        val depth = (h * 0.06f).roundToInt()
        val top = band(t + edge, t + edge + depth) ?: return null
        val bottom = band(bt - edge - depth, bt - edge) ?: return null
        // Two colors (Xiaomi's 视频头条: white over red) are a picture, not a plate to carry across.
        if (distance(top, bottom) > 150) return null

        // The logo: whatever on the plate stands out from the plate's color at that height.
        fun logoOnPlate(px: IntArray, n: Int): RectF? {
            val plate = solidBox(px, n) ?: return null
            val inset = (plate.width() * 0.08f).roundToInt()
            return bounds(px, n, android.graphics.Rect(plate.left + inset, plate.top + inset, plate.right - inset, plate.bottom - inset)) { i, c ->
                solid(c) && distance(c, ColorUtils.blendARGB(top, bottom, (i / n - plate.top) / plate.height().toFloat())) > 72
            } ?: RectF(plate.left / n.toFloat(), plate.top / n.toFloat(), plate.right / n.toFloat(), plate.bottom / n.toFloat())
        }

        val (fore, logo) = placeLogo(icon, ::logoOnPlate) ?: return null
        if (recipe?.top != null && recipe.bottom != null) {
            // The tile gets the recipe's colors, so the plate has to go: only the logo is kept.
            cutOut(fore, logo, top, bottom, white = recipe.white || unified)
            return finish(colorTile(recipe.top, recipe.bottom), fore)
        }
        if (unified && recipe?.keepColors != true && (recipe?.white == true || !multiColor(fore, logo, ColorUtils.blendARGB(top, bottom, 0.5f)))) {
            return unify(fore, logo, top, bottom)
        }
        val back = layer {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
        }
        if (recipe?.white == true) cutOut(fore, logo, top, bottom, white = true) else feather(fore, logo)
        return finish(back, fore)
    }

    /**
     * Keeps only what stands out from the plate (colored [top] to [bottom] across [logo]) in a
     * logo layer, its edges eased by how far each pixel is from the plate color; in white if asked.
     */
    private fun cutOut(layer: Bitmap, logo: RectF, top: Int, bottom: Int, white: Boolean) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        for (y in 0 until H) {
            val plate = ColorUtils.blendARGB(top, bottom, ((y - logo.top) / logo.height()).coerceIn(0f, 1f))
            for (x in 0 until W) {
                val i = y * W + x
                val c = px[i]
                if (c ushr 24 == 0) continue
                val keep = ((distance(c, plate) - 24) / 72f).coerceIn(0f, 1f)
                val a = ((c ushr 24) * keep).roundToInt()
                px[i] = (a shl 24) or (if (white) 0xFFFFFF else c and 0xFFFFFF)
            }
        }
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    private fun legacy(icon: Drawable, recipe: TileRecipe?): TileArt {
        // A legacy icon with no plate to carry across the tile. It sits, sized like every other
        // logo, on a tile in its own hue, the way tvOS draws its system apps. (It used to sit on
        // a blurred copy of itself, which read as a phone icon dropped on a smudge.) Full-square
        // icons (many China-market system apps expect the launcher to mask them) get rounded corners.
        val sample = 192
        val iconBitmap = createBitmap(sample, sample)
        icon.setBounds(0, 0, sample, sample)
        icon.draw(Canvas(iconBitmap))
        val square = opaqueCorners(iconBitmap)
        val tint = dominant(iconBitmap)
        iconBitmap.recycle()
        val (top, bottom) = if (recipe?.top != null && recipe.bottom != null) recipe.top to recipe.bottom else tileFromTint(tint)
        // A white logo on a picture (Z视介's Z on blue facets): a recipe can ask for just the logo,
        // taken by its lightness, on the recipe's colors.
        if (recipe?.white == true && (square || edgeColors(icon) != null)) {
            placeLogo(icon, ::lightPart)?.let { (fore, _) ->
                keepLight(fore)
                return finish(colorTile(top, bottom), fore)
            }
        }
        // A logo with no plate of its own (the icon is just its shape) goes on in white like the
        // rest; a full-square icon is a picture, and stays one.
        if (unified && !square && recipe?.keepColors != true) {
            placeLogo(icon, ::visible)?.let { (fore, at) ->
                if (recipe?.white == true || !multiColor(fore, at, 0)) {
                    whiten(fore)
                    return finish(colorTile(top, bottom), fore)
                }
                fore.recycle()
            }
        }
        // A square icon is a picture with its logo somewhere inside: sized up so the logo reads
        // as large as the others. When its edges are one color each, those colors are carried
        // across the tile and the picture fades into them, so it fills the tile instead of sitting
        // on it (HiFi音乐, 华数TV, 开唛K歌 from 当贝市场). A recipe's colors win.
        if (recipe?.top == null) edgeColors(icon)?.let { (edgeTop, edgeBottom) ->
            placeLogo(icon, ::visible, scale = 1.25f)?.let { (fore, at) ->
                val side = at.width()
                feather(fore, RectF(at.left + side * 0.18f, at.top + side * 0.18f, at.right - side * 0.18f, at.bottom - side * 0.18f), margin = 0f, fade = side * 0.18f)
                val back = layer {
                    drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                        shader = LinearGradient(0f, at.top, 0f, at.bottom, edgeTop, edgeBottom, Shader.TileMode.CLAMP)
                    })
                }
                return finish(back, fore)
            }
        }
        val fore = placeLogo(icon, ::visible, round = square, scale = if (square) 1.25f else 1f)?.first
        return finish(colorTile(top, bottom), fore)
    }

    /**
     * For an icon that is a filled (rounded) square, the colors along its top and bottom edges,
     * just inside them and away from the corners. Null for any other shape, when either edge is
     * not mostly one color (a photo running off the edge), or when the two are far apart (black
     * over orange, 华数南瓜电影), which fades into the tile as a stripe. A gradient (pink into
     * purple) is fine.
     */
    private fun edgeColors(icon: Drawable): Pair<Int, Int>? {
        val n = 96
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val w = box.width(); val h = box.height()
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f) return null
        var filled = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (solid(px[y * n + x])) filled++
        if (filled < w * h * 0.88f) return null
        fun band(y0: Int, y1: Int): Int? {
            val cs = ArrayList<Int>()
            for (y in y0..y1) for (x in box.left + w / 5 until box.right - w / 5) cs += px[y * n + x]
            val median = android.graphics.Color.rgb(
                cs.map { (it shr 16) and 0xFF }.sorted()[cs.size / 2],
                cs.map { (it shr 8) and 0xFF }.sorted()[cs.size / 2],
                cs.map { it and 0xFF }.sorted()[cs.size / 2],
            )
            return if (cs.count { distance(it, median) <= 48 } < cs.size * 0.6f) null else median
        }
        // Close to the edge: a picture icon's subject often reaches within a few percent of it.
        val top = band(box.top + h * 2 / 100, box.top + h * 5 / 100) ?: return null
        val bottom = band(box.bottom - 1 - h * 5 / 100, box.bottom - 1 - h * 2 / 100) ?: return null
        return if (distance(top, bottom) > 260) null else top to bottom
    }

    /**
     * OpenTV's one look for tiles made from icons, as tvOS draws its own apps: the logo in white,
     * on a gradient in the app's color. The plate the icon came on ([top] to [bottom]) gives that
     * color when it has one; a white, black or grey plate does not, and the logo's own color is
     * used instead. A logo that is itself grey on such a plate gets graphite.
     */
    private fun unify(fore: Bitmap, logo: RectF, top: Int, bottom: Int): TileArt {
        val plate = ColorUtils.blendARGB(top, bottom, 0.5f)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(plate, hsl)
        val colored = hsl[1] > 0.25f && hsl[2] in 0.18f..0.85f
        val tint = if (colored) null else logoTint(fore, logo, plate)
        cutOut(fore, logo, top, bottom, white = true)
        val (a, b) = if (colored) tileFromPlate(top, bottom) else tileFromTint(tint!!)
        return finish(colorTile(a, b), fore)
    }

    /**
     * True for a logo of several colors (Geekbench's bars on glasses, a star on a bubble). In
     * white those parts melt into one shape, so such logos keep their colors. Pixels close to
     * [plate] are not part of the logo; pass 0 for a logo with no plate.
     */
    private fun multiColor(layer: Bitmap, logo: RectF, plate: Int): Boolean {
        val l = logo.left.toInt().coerceIn(0, W - 1); val t = logo.top.toInt().coerceIn(0, H - 1)
        val r = logo.right.toInt().coerceIn(l + 1, W); val b = logo.bottom.toInt().coerceIn(t + 1, H)
        val px = IntArray((r - l) * (b - t)).also { layer.getPixels(it, 0, r - l, l, t, r - l, b - t) }
        // Twelve hues, plus light and dark for colors with too little saturation to have one.
        val bins = IntArray(14)
        val hsv = FloatArray(3)
        fun binOf(c: Int): Int {
            android.graphics.Color.colorToHSV(c, hsv)
            return when {
                hsv[1] < 0.25f || hsv[2] < 0.2f -> if (hsv[2] > 0.6f) 12 else 13
                else -> ((hsv[0] + 15f) / 30f).toInt() % 12
            }
        }
        // The plate's own hue inside the logo is its edge blending into the plate (pink around
        // Xiaomi's white "mi" on red), not a second color.
        val plateBin = if (plate != 0) binOf(plate).takeIf { it < 12 } else null
        var count = 0
        for (c in px) {
            if (c ushr 24 < 200 || (plate != 0 && distance(c, plate) < 96)) continue
            val bin = binOf(c)
            if (plateBin != null && (bin == plateBin || bin == (plateBin + 1) % 12 || bin == (plateBin + 11) % 12)) continue
            bins[bin]++; count++
        }
        if (count == 0) return false
        // Neighbouring hues are one color in a gradient (a red that runs into orange).
        val groups = ArrayList<Int>()
        for (i in 0 until 12) {
            val v = bins[i]
            if (v > 0 && v >= bins[(i + 11) % 12] && v >= bins[(i + 1) % 12]) groups += v + bins[(i + 11) % 12] + bins[(i + 1) % 12]
        }
        groups += bins[12]; groups += bins[13]
        return groups.count { it > count * 0.08f } >= 2
    }

    /** The saturation-weighted color of what stands out from [plate] in a logo layer. */
    private fun logoTint(layer: Bitmap, logo: RectF, plate: Int): Int {
        val l = logo.left.toInt().coerceIn(0, W - 1); val t = logo.top.toInt().coerceIn(0, H - 1)
        val r = logo.right.toInt().coerceIn(l + 1, W); val b = logo.bottom.toInt().coerceIn(t + 1, H)
        val px = IntArray((r - l) * (b - t)).also { layer.getPixels(it, 0, r - l, l, t, r - l, b - t) }
        var rr = 0.0; var gg = 0.0; var bb = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (c in px) {
            if (c ushr 24 < 200 || distance(c, plate) < 96) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[2] + 0.02
            rr += ((c shr 16) and 0xFF) * w; gg += ((c shr 8) and 0xFF) * w; bb += (c and 0xFF) * w; total += w
        }
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((rr / total).roundToInt(), (gg / total).roundToInt(), (bb / total).roundToInt())
    }

    /** The plate's colors as a tile: kept, but evened out so no tile is much paler or deeper than the rest. */
    private fun tileFromPlate(top: Int, bottom: Int): Pair<Int, Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(ColorUtils.blendARGB(top, bottom, 0.5f), hsl)
        val s = hsl[1].coerceIn(0.45f, 0.85f)
        val l = hsl[2].coerceIn(0.4f, 0.56f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, l + 0.08f)) to ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, l - 0.08f))
    }

    /** The near-white part of a sample. */
    private fun lightPart(px: IntArray, n: Int): RectF? = bounds(px, n) { _, c -> solid(c) && minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) > 200 }

    /** Only the near-white pixels of a logo layer kept, in white, their edges eased. */
    private fun keepLight(layer: Bitmap) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        for (i in px.indices) {
            val c = px[i]
            val low = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            val keep = ((low - 150) / 70f).coerceIn(0f, 1f)
            px[i] = (((c ushr 24) * keep).roundToInt() shl 24) or 0xFFFFFF
        }
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    /** Every visible pixel of a logo layer turned white, its alpha kept. */
    private fun whiten(layer: Bitmap) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or 0xFFFFFF
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    private fun opaqueCorners(b: Bitmap): Boolean {
        val i = (b.width * 0.03f).roundToInt()
        val e = b.width - 1 - i
        return listOf(b.getPixel(i, i), b.getPixel(e, i), b.getPixel(i, e), b.getPixel(e, e))
            .all { android.graphics.Color.alpha(it) > 200 }
    }

    // ---- Logos: found in a sample of the icon, then drawn sharp at their size on the tile ----

    private inline fun layer(draw: Canvas.() -> Unit): Bitmap = createBitmap(W, H).also { Canvas(it).draw() }

    private fun raster(d: Drawable, n: Int): IntArray {
        val b = createBitmap(n, n)
        d.setBounds(0, 0, n, n)
        d.draw(Canvas(b))
        return IntArray(n * n).also { b.getPixels(it, 0, n, 0, 0, n, n); b.recycle() }
    }

    /**
     * The box around the pixels of the n×n sample that [isLogo] picks (given the pixel's index
     * and color), searched within [within], as fractions of the sample. Null when there are none.
     */
    private inline fun bounds(
        px: IntArray, n: Int,
        within: android.graphics.Rect = android.graphics.Rect(0, 0, n, n),
        isLogo: (Int, Int) -> Boolean,
    ): RectF? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in within.top until within.bottom) for (x in within.left until within.right) {
            val i = y * n + x
            if (isLogo(i, px[i])) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
        }
        return if (r < 0) null else RectF(l / n.toFloat(), t / n.toFloat(), (r + 1) / n.toFloat(), (b + 1) / n.toFloat())
    }

    private fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            kotlin.math.abs((a and 0xFF) - (b and 0xFF))

    /** True for a layer of (nearly) one color. */
    private fun isFlat(px: IntArray): Boolean {
        val first = px[px.size / 2]
        return px.count { distance(it, first) > 24 || kotlin.math.abs((it ushr 24) - (first ushr 24)) > 24 } < px.size / 20
    }

    /**
     * Where the whole icon goes on the tile so that its [logo] (fractions of the icon's box) sits
     * centered at logo size. Logos are sized by area rather than by their box, so a wide wordmark
     * and a round symbol carry the same weight, and all of them stay inside the tile's safe zone,
     * as on tvOS: focus scale and parallax never take a logo near the edge.
     */
    private fun iconOnTile(logo: RectF, scale: Float = 1f): RectF {
        val aspect = logo.width() / logo.height()
        val h = minOf(scale * LOGO * H / kotlin.math.sqrt(aspect), 0.66f * H, 0.62f * W / aspect)
        val box = h / logo.height()
        val left = W / 2f - box * logo.centerX()
        val top = H / 2f - box * logo.centerY()
        return RectF(left, top, left + box, top + box)
    }

    /** The opaque part of a sample. */
    private fun visible(px: IntArray, n: Int): RectF? = bounds(px, n) { _, c -> c ushr 24 > 24 }

    private fun solid(c: Int) = c ushr 24 > 200

    /** The box around the solid pixels of an n×n sample, in its pixels. */
    private fun solidBox(px: IntArray, n: Int): android.graphics.Rect? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in 0 until n) for (x in 0 until n) if (solid(px[y * n + x])) {
            l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r + 1, b + 1)
    }

    /**
     * A tile-sized layer with [d] drawn so that the logo [find] picks out of it sits at
     * [iconOnTile], and where that logo ended up on the tile. Null when [find] finds none.
     *
     * The icon is drawn at the size it will have, so it stays sharp, then measured again: a
     * layer-list of dp-sized shapes does not scale with its bounds (遥遥电视助手 on Xiaomi
     * MiTV-MFTR0), and is placed by what was actually drawn.
     */
    private fun placeLogo(d: Drawable, find: (IntArray, Int) -> RectF?, round: Boolean = false, scale: Float = 1f): Pair<Bitmap, RectF>? {
        val first = find(raster(d, SAMPLE), SAMPLE) ?: return null
        val size = iconOnTile(first, scale).width().roundToInt().coerceIn(16, 4 * H)
        val icon = createBitmap(size, size)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(icon))
        val px = IntArray(size * size).also { icon.getPixels(it, 0, size, 0, 0, size, size) }
        val logo = find(px, size) ?: first
        val at = iconOnTile(logo, scale)
        val out = layer {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (round) {
                val r = at.width() * 0.225f
                drawRoundRect(at, r, r, paint.apply {
                    shader = android.graphics.BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(android.graphics.Matrix().apply {
                            setScale(at.width() / size, at.height() / size)
                            postTranslate(at.left, at.top)
                        })
                    }
                })
            } else {
                drawBitmap(icon, null, at, paint)
            }
        }
        icon.recycle()
        val box = at.width()
        return out to RectF(at.left + box * logo.left, at.top + box * logo.top, at.left + box * logo.right, at.top + box * logo.bottom)
    }

    /** Fades [layer] out around [keep], so a plate's own edges and corners melt into the tile color. */
    private fun feather(layer: Bitmap, keep: RectF, margin: Float = H * 0.04f, fade: Float = H * 0.22f) {
        val c = Canvas(layer)
        fun mask(from: Float, to: Float, horizontal: Boolean) = Paint().apply {
            val span = to - from + 2 * (margin + fade)
            val f = fade / span
            val start = from - margin - fade
            shader = LinearGradient(
                if (horizontal) start else 0f, if (horizontal) 0f else start,
                if (horizontal) start + span else 0f, if (horizontal) 0f else start + span,
                intArrayOf(0, android.graphics.Color.BLACK, android.graphics.Color.BLACK, 0),
                floatArrayOf(0f, f, 1f - f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.left, keep.right, horizontal = true))
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.top, keep.bottom, horizontal = false))
    }

    /** [back] and [fore] are both tile-sized; the foreground is trimmed here. */
    private fun finish(back: Bitmap, fore: Bitmap?): TileArt {
        val composed = if (fore == null) back else back.copy(Bitmap.Config.ARGB_8888, true).also {
            Canvas(it).drawBitmap(fore, 0f, 0f, null)
        }
        val tint = dominant(composed)
        val backdrop = backdropOf(composed)
        if (composed !== back) composed.recycle()
        var foreAt = androidx.compose.ui.geometry.Rect.Zero
        val trimmed = fore?.let { layer ->
            val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
            var l = W; var t = H; var r = -1; var b = -1
            for (y in 0 until H) for (x in 0 until W) if (px[y * W + x] ushr 24 != 0) {
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
            }
            if (r < 0) return@let null
            foreAt = androidx.compose.ui.geometry.Rect(l / W.toFloat(), t / H.toFloat(), (r + 1) / W.toFloat(), (b + 1) / H.toFloat())
            Bitmap.createBitmap(layer, l, t, r - l + 1, b - t + 1).also { if (it !== layer) layer.recycle() }
        }
        return TileArt(
            back = back.asImageBitmap(),
            fore = trimmed?.asImageBitmap(),
            foreAt = foreAt,
            tint = Color(tint),
            backdrop = backdrop.asImageBitmap(),
        )
    }

    /** The tile as one picture, as Home draws it at rest: for the phone page. */
    fun flatten(art: TileArt): Bitmap {
        val out = art.back.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        art.fore?.let { f ->
            val r = art.foreAt
            Canvas(out).drawBitmap(
                f.asAndroidBitmap(), null,
                RectF(r.left * out.width, r.top * out.height, r.right * out.width, r.bottom * out.height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        return out
    }

    private fun Canvas.drawCover(d: Drawable, w: Int, h: Int) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val l = (w - dw) / 2
        val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(this)
    }

    /** Saturation-weighted mean color: the hue a person would name when describing the tile. */
    private fun dominant(b: Bitmap): Int {
        val small = b.scale(24, 14)
        var r = 0.0; var g = 0.0; var bl = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val wgt = hsv[1] * hsv[2] * hsv[2] + 0.04
            r += android.graphics.Color.red(c) * wgt
            g += android.graphics.Color.green(c) * wgt
            bl += android.graphics.Color.blue(c) * wgt
            total += wgt
        }
        if (small !== b) small.recycle()
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((r / total).roundToInt(), (g / total).roundToInt(), (bl / total).roundToInt())
    }

    /**
     * Tile colors, top and bottom, for a logo with no background of its own: its hue, a little
     * deeper than the logo so the logo still stands out. Grey logos get graphite.
     */
    private fun tileFromTint(tint: Int): Pair<Int, Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(tint, hsl)
        if (hsl[1] < 0.18f) return 0xFF6E6E74.toInt() to 0xFF3A3A3C.toInt()
        val s = hsl[1].coerceIn(0.5f, 0.85f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.56f)) to ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.4f))
    }

    private fun backdropOf(src: Bitmap): Bitmap {
        val w = 64
        val h = 36
        val small = src.scale(w, h)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { Images.boxBlur(px, w, h, 4) }
        val blurred = createBitmap(w, h)
        blurred.setPixels(px, 0, w, 0, 0, w, h)
        // Lift saturation and pull brightness down: ambient light, not a picture. Pale artwork
        // (white banners) would spread a grey haze, so it gets dimmed much further.
        val hsv = FloatArray(3)
        var sat = 0f; var value = 0f
        for (c in px) { android.graphics.Color.colorToHSV(c, hsv); sat += hsv[1]; value += hsv[2] }
        sat /= px.size; value /= px.size
        val gain = if (sat < 0.2f) 0.82f - 0.5f * value * (1f - sat / 0.2f) else 0.82f
        val cm = ColorMatrix().apply {
            setSaturation(1.5f)
            postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
        }
        val bw = 192
        val bh = 108
        val out = createBitmap(bw, bh)
        Canvas(out).apply {
            drawBitmap(
                blurred, null, android.graphics.Rect(0, 0, bw, bh),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) },
            )
            // Light kept up top where the shelf is, near-black under the grid.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bh.toFloat(),
                    intArrayOf(black(0.10f), black(0.32f), black(0.80f), black(0.94f)),
                    floatArrayOf(0f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP,
                )
            })
            // The left edge shaded so the clock reads over bright artwork.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, bw * 0.55f, 0f, black(0.38f), 0, Shader.TileMode.CLAMP)
            })
        }
        small.recycle(); blurred.recycle()
        return out
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}
