package app.opentv.launcher.ui.home

import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    @Test fun bothModesEmphasizeTheCardNotATravellingFrame() {
        for (instant in listOf(false, true)) {
            assertEquals(1f, NavigationPolicy.scale(instant, false, false, false), 0f)
            val selected = NavigationPolicy.scale(instant, false, false, true)
            assertTrue(selected > 1f)
            assertTrue(NavigationPolicy.scale(instant, false, true, true) < selected)
            assertEquals(1f, NavigationPolicy.scale(instant, true, false, true), 0f)
        }
    }

    @Test fun fullMotionUsesReferenceScale() {
        assertEquals(1.18f, NavigationPolicy.scale(false, false, false, true), 0f)
    }

    @Test fun backgroundAlwaysSelectsExactlyOneImage() {
        for (scroll in -10..210) assertEquals(if (scroll >= 50) 1f else 0f,
            NavigationPolicy.backgroundProgress(scroll.toFloat(), 100f), 0f)
        assertEquals(0f, NavigationPolicy.backgroundProgress(100f, 0f), 0f)
    }

    @Test fun horizontalNavigationNeverWrapsRowsEvenWhileReordering() {
        assertNull(NavigationPolicy.neighbor(0, 14, GridDirection.Left))
        assertNull(NavigationPolicy.neighbor(5, 14, GridDirection.Right))
        assertNull(NavigationPolicy.neighbor(6, 14, GridDirection.Left))
        assertNull(NavigationPolicy.neighbor(13, 14, GridDirection.Right))
        assertEquals(7, NavigationPolicy.neighbor(6, 14, GridDirection.Right))
        assertEquals(12, NavigationPolicy.neighbor(13, 14, GridDirection.Left))
    }

    @Test fun verticalNavigationHandlesPartialRowsAndEdges() {
        assertNull(NavigationPolicy.neighbor(3, 14, GridDirection.Up))
        assertEquals(13, NavigationPolicy.neighbor(11, 14, GridDirection.Down))
        assertEquals(7, NavigationPolicy.neighbor(13, 14, GridDirection.Up))
        assertNull(NavigationPolicy.neighbor(12, 14, GridDirection.Down))
        assertNull(NavigationPolicy.neighbor(0, 0, GridDirection.Down))
        assertNull(NavigationPolicy.neighbor(-1, 14, GridDirection.Right))
    }
}
