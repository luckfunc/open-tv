package app.opentv.launcher.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import app.opentv.launcher.wallpaper.BackdropImages
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [HostRuntimeShader::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassOpticsTest {
    @Test fun hostAdapterSupportsUniformShaderEvaluation() {
        android.graphics.RuntimeShader("uniform shader inputImage; half4 main(float2 p) { return inputImage.eval(p); }")
    }

    @Test fun clearAndGridMaterialsUseTheSamePublishedSceneAsTheBackground() {
        val clear = image(Color.RED)
        val soft = image(Color.GREEN)
        val grid = image(Color.BLUE)
        val source = SceneSource.Wallpaper(BackdropImages(clear, soft, grid))
        val top = source.frame(0f) as SceneFrame.Ready
        val scrolled = source.frame(1f) as SceneFrame.Ready
        assertSame(clear, top.base)
        assertSame(soft, top.diffused)
        assertSame(grid, scrolled.base)
        assertSame(grid, scrolled.diffused)
    }

    @Test fun ambientSceneAndMaterialShareTheSameDimming() {
        val ambient = image(Color.RED)
        val frame = SceneSource.Ambient(ambient).frame(0.8f) as SceneFrame.Ready
        assertSame(ambient, frame.base)
        assertSame(ambient, frame.diffused)
        assertEquals(0.4f, frame.dimming, 0.0001f)
    }

    @Test fun opticalMathRendersAndRespondsToSceneChangesOnTheHost() {
        val optical = RefractiveGlass()
        val red = image(Color.RED)
        val blue = image(Color.BLUE)
        val first = render(optical, SceneFrame.Ready(red, red, 0f))
        val second = render(optical, SceneFrame.Ready(blue, blue, 0f))
        assertTrue(Color.red(first.getPixel(40, 40)) > Color.blue(first.getPixel(40, 40)))
        assertTrue(Color.blue(second.getPixel(40, 40)) > Color.red(second.getPixel(40, 40)))
    }

    @Test fun opticalAmbientDimmingIsNotLostAtTheMaterialBoundary() {
        val optical = RefractiveGlass()
        val red = image(Color.RED)
        val bright = render(optical, SceneFrame.Ready(red, red, 0f)).getPixel(40, 40)
        val dark = render(optical, SceneFrame.Ready(red, red, 0.5f)).getPixel(40, 40)
        assertTrue(Color.red(dark) < Color.red(bright) * 0.7f)
    }

    @Test fun frostedSamplingAlignsWithTranslatedAndScaledSurfaces() {
        val pixels = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(pixels).apply {
            drawColor(Color.RED)
            drawRect(100f, 0f, 200f, 100f, Paint().apply { color = Color.BLUE })
        }
        val image = pixels.asImageBitmap()
        val frame = SceneFrame.Ready(image, image, 0f)
        val viewport = SceneViewport(Offset(50f, 20f), IntSize(200, 100))
        val placement = SurfacePlacement(Offset(150f, 20f), Offset(2f, 0f), Offset(0f, 1f))
        val sampled = render(FrostedGlass(), frame, viewport, placement)
        val color = sampled.getPixel(20, 40)
        assertTrue(Color.blue(color) > Color.red(color))
    }

    private fun image(color: Int): ImageBitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
    private fun render(
        optics: GlassOptics,
        frame: SceneFrame.Ready,
        viewport: SceneViewport = SceneViewport(size = IntSize(200, 100)),
        placement: SurfacePlacement = SurfacePlacement(),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        optics.update(frame, viewport, placement, Size(80f, 80f), 12f, 1f)
        val shader = (optics.brush as ShaderBrush).createShader(Size(80f, 80f))
        val canvas = Canvas(bitmap)
        // The host evaluates shader math on CPU, not Android's GPU-only canvas contract.
        org.robolectric.util.ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        canvas.drawRect(0f, 0f, 80f, 80f, Paint().apply { this.shader = shader })
        return bitmap
    }
}
