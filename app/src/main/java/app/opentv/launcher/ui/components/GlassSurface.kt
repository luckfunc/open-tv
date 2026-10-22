package app.opentv.launcher.ui.components

import android.os.Build
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private sealed interface GlassBackend {
    val optics: GlassOptics
    data class Optical(override val optics: GlassOptics) : GlassBackend
    data class Frosted(override val optics: GlassOptics = FrostedGlass()) : GlassBackend
}

private fun glassBackend(quality: MaterialQuality): GlassBackend {
    if (quality != MaterialQuality.Refractive || Build.VERSION.SDK_INT < 33) return GlassBackend.Frosted()
    return try { GlassBackend.Optical(RefractiveGlass()) } catch (failure: RuntimeException) {
        Log.w("OpenTVGlass", "Optical backend unavailable", failure)
        GlassBackend.Frosted()
    }
}

/** A bounded material sampling the same scene and viewport as the background renderer. */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val scene = LocalBackdropScene.current
    var backend by remember(scene.quality) { mutableStateOf(glassBackend(scene.quality)) }
    var placement by remember { mutableStateOf(SurfacePlacement()) }
    val rim = remember(focused) { Brush.linearGradient(if (focused)
        listOf(Color.White, Color.White.copy(alpha = 0.55f))
    else listOf(Color.White.copy(alpha = 0.52f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.22f))) }

    Box(modifier.onGloballyPositioned {
        val origin = it.localToRoot(Offset.Zero)
        placement = SurfacePlacement(origin, it.localToRoot(Offset(1f, 0f)) - origin, it.localToRoot(Offset(0f, 1f)) - origin)
    }.clip(shape).drawBehind {
        if (focused) {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.97f), Color(0xFFF0F2F5))))
            return@drawBehind
        }
        val frame = scene.frame()
        val viewport = scene.viewport()
        val determinant = placement.axisX.x * placement.axisY.y - placement.axisX.y * placement.axisY.x
        if (frame is SceneFrame.Ready && viewport.size.width > 0 && viewport.size.height > 0 && abs(determinant) > 0.000001f) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: size.minDimension / 2f
            var material = backend
            if (material is GlassBackend.Optical) {
                try {
                    material.optics.update(frame, viewport, placement, size, radius, density)
                    drawRect(material.optics.brush)
                    return@drawBehind
                } catch (failure: RuntimeException) {
                    Log.w("OpenTVGlass", "Optical draw failed; retaining frosted backend", failure)
                    material = GlassBackend.Frosted()
                    backend = material
                }
            }
            material.optics.update(frame, viewport, placement, size, radius, density)
            drawRect(material.optics.brush)
            if (frame.dimming > 0f) drawRect(Color.Black.copy(alpha = frame.dimming))
        }
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f))))
    }.border(0.65.dp, rim, shape), content = content)
}
