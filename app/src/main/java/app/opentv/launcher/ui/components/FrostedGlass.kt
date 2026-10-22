package app.opentv.launcher.ui.components

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize

internal class FrostedGlass : GlassOptics {
    private var image: ImageBitmap? = null
    private lateinit var shader: BitmapShader
    private lateinit var imageBrush: Brush
    override val brush get() = imageBrush
    private val forward = Matrix()
    private val inverse = Matrix()

    override fun update(frame: SceneFrame.Ready, viewport: SceneViewport, placement: SurfacePlacement, size: Size, radius: Float, density: Float) {
        if (image !== frame.diffused) {
            shader = BitmapShader(frame.diffused.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            imageBrush = ShaderBrush(shader)
            image = frame.diffused
        }
        val pixels = IntSize(frame.diffused.width, frame.diffused.height)
        val origin = placement.imagePoint(Offset.Zero, viewport, pixels)
        val x = placement.imagePoint(Offset(1f, 0f), viewport, pixels) - origin
        val y = placement.imagePoint(Offset(0f, 1f), viewport, pixels) - origin
        forward.setValues(floatArrayOf(x.x, y.x, origin.x, x.y, y.y, origin.y, 0f, 0f, 1f))
        check(forward.invert(inverse)) { "Non-invertible glass placement" }
        shader.setLocalMatrix(inverse)
    }
}
