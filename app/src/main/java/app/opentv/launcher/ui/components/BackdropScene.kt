package app.opentv.launcher.ui.components

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.ui.home.NavigationPolicy
import app.opentv.launcher.ui.theme.LocalReducedMotion
import app.opentv.launcher.wallpaper.BackdropImages

internal enum class MaterialQuality { Refractive, Frosted }

internal fun materialQuality(sdk: Int, lowRam: Boolean, lowCost: Boolean, reduced: Boolean): MaterialQuality =
    if (sdk >= 33 && !lowRam && !lowCost && !reduced) MaterialQuality.Refractive else MaterialQuality.Frosted

internal sealed interface SceneFrame {
    data object Empty : SceneFrame
    data class Ready(val base: ImageBitmap, val diffused: ImageBitmap, val dimming: Float) : SceneFrame
}

internal sealed interface SceneSource {
    data object Empty : SceneSource
    data class Wallpaper(val images: BackdropImages) : SceneSource
    data class Ambient(val image: ImageBitmap) : SceneSource

    fun frame(progress: Float): SceneFrame = when (this) {
        Empty -> SceneFrame.Empty
        is Wallpaper -> if (progress >= 0.5f) SceneFrame.Ready(images.grid, images.grid, 0f)
            else SceneFrame.Ready(images.clear, images.diffused, 0f)
        is Ambient -> SceneFrame.Ready(image, image, progress.coerceIn(0f, 1f) * 0.5f)
    }
}

internal data class SceneViewport(val origin: Offset = Offset.Zero, val size: IntSize = IntSize.Zero)
internal data class SurfacePlacement(
    val origin: Offset = Offset.Zero,
    val axisX: Offset = Offset(1f, 0f),
    val axisY: Offset = Offset(0f, 1f),
) {
    fun imagePoint(point: Offset, viewport: SceneViewport, image: IntSize): Offset {
        val root = origin + axisX * point.x + axisY * point.y - viewport.origin
        return Offset(root.x * image.width / viewport.size.width.coerceAtLeast(1), root.y * image.height / viewport.size.height.coerceAtLeast(1))
    }
}

internal class BackdropScene(
    val quality: MaterialQuality,
    val frame: () -> SceneFrame,
    val viewport: () -> SceneViewport,
)

internal val LocalBackdropScene = staticCompositionLocalOf {
    BackdropScene(MaterialQuality.Frosted, { SceneFrame.Empty }, { SceneViewport() })
}

@Composable
internal fun BackdropSceneHost(
    images: BackdropImages?,
    ambient: ImageBitmap?,
    scroll: () -> Float,
    collapse: Float,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val lowRam = remember(context) { (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true }
    val quality = materialQuality(Build.VERSION.SDK_INT, lowRam, OpenTvSettings.instantNavigation, LocalReducedMotion.current)
    val source = remember(images, ambient) {
        images?.let(SceneSource::Wallpaper) ?: ambient?.let(SceneSource::Ambient) ?: SceneSource.Empty
    }
    val currentScroll by rememberUpdatedState(scroll)
    val currentCollapse by rememberUpdatedState(collapse)
    var viewport by remember { mutableStateOf(SceneViewport()) }
    val scene = remember(source, quality) {
        BackdropScene(quality, { source.frame(NavigationPolicy.backgroundProgress(currentScroll(), currentCollapse)) }, { viewport })
    }
    CompositionLocalProvider(LocalBackdropScene provides scene) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = SceneViewport(it.positionInRoot(), it.size) }, content = content)
    }
}
