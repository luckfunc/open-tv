package app.opentv.launcher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPlacementTest {
    @Test fun shortMenuOpensBelowWhileTallMenuUsesSpaceAbove() {
        val short = placeAnchoredMenu(960, 540, 250, 100, 300, 260, 342, 24, 14)
        val tall = placeAnchoredMenu(960, 540, 250, 220, 300, 260, 342, 24, 14)
        assertEquals(356, short.y)
        assertEquals(26, tall.y)
    }

    @Test fun everyEdgeKeepsMeasuredPanelInsideSafeArea() {
        for (width in listOf(180, 250, 500)) {
            for (height in listOf(100, 250, 492)) {
                for (left in listOf(-200, 0, 56, 800, 1200)) {
                    for (top in listOf(-200, 0, 240, 450, 700)) {
                        val placement = placeAnchoredMenu(960, 540, width, height, left, top, top + 83, 24, 14)
                        assertTrue(placement.x >= 24)
                        assertTrue(placement.y >= 24)
                        assertTrue(placement.x + width <= 936)
                        assertTrue(placement.y + height <= 516)
                    }
                }
            }
        }
    }

    @Test fun smallViewportNeverCreatesAnInvalidClampRange() {
        val placement = placeAnchoredMenu(40, 30, 0, 0, 100, 100, 120, 24, 14)
        assertTrue(placement.x in 0..40)
        assertTrue(placement.y in 0..30)
        val full = placeAnchoredMenu(40, 30, 40, 30, -20, -20, 20, 24, 14)
        assertEquals(0, full.x)
        assertEquals(0, full.y)
    }

    @Test fun horizontalPositionUsesMeasuredWidth() {
        val narrow = placeAnchoredMenu(960, 540, 180, 100, 850, 100, 183, 24, 14)
        val wide = placeAnchoredMenu(960, 540, 400, 100, 850, 100, 183, 24, 14)
        assertEquals(756, narrow.x)
        assertEquals(536, wide.x)
    }
}
