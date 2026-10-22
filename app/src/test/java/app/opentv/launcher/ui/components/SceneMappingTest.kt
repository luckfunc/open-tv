package app.opentv.launcher.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneMappingTest {
    @Test fun samplingUsesTheSceneViewportRatherThanThePhysicalWindow() {
        val viewport = SceneViewport(Offset(20f, 30f), IntSize(960, 540))
        val surface = SurfacePlacement(Offset(116f, 84f))
        assertEquals(Offset(240f, 162f), surface.imagePoint(Offset(24f, 27f), viewport, IntSize(1920, 1080)))
    }
    @Test fun offscreenOriginsAreNotClampedToTheWindowBoundary() {
        val surface = SurfacePlacement(Offset(-40f, -60f), Offset(2f, 0f), Offset(0f, 2f))
        assertEquals(Offset(-20f, -20f), surface.imagePoint(Offset(10f, 20f), SceneViewport(size = IntSize(960, 540)), IntSize(960, 540)))
    }
    @Test fun samplingHonoursBothTransformAxes() {
        val surface = SurfacePlacement(Offset(100f, 100f), Offset(0f, 1f), Offset(-1f, 0f))
        assertEquals(Offset(80f, 110f), surface.imagePoint(Offset(10f, 20f), SceneViewport(size = IntSize(200, 200)), IntSize(200, 200)))
    }
    @Test fun legacyLowRamAndReducedEffectsUseTheFrostedBackend() {
        assertEquals(MaterialQuality.Frosted, materialQuality(21, false, false, false))
        assertEquals(MaterialQuality.Frosted, materialQuality(34, true, false, false))
        assertEquals(MaterialQuality.Frosted, materialQuality(34, false, true, false))
        assertEquals(MaterialQuality.Frosted, materialQuality(34, false, false, true))
        assertEquals(MaterialQuality.Refractive, materialQuality(34, false, false, false))
    }
}
