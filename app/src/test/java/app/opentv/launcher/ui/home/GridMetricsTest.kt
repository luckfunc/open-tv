package app.opentv.launcher.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class GridMetricsTest {
    private fun metrics(oneRow: Boolean = false) = GridMetrics(960.dp, 540.dp, oneRow)

    @Test fun sixFiveByThreeCardsShareOneDock() {
        val m = metrics()
        assertEquals(6, COLUMNS)
        assertEquals(124f, m.tileW.value, 0.001f)
        assertEquals(74.4f, m.tileH.value, 0.001f)
        assertEquals(413.1f, m.topRowY.value, 0.001f)
        assertTrue(m.dockTop < m.topRowY)
        assertTrue(m.dockTop + m.dockHeight > m.topRowY + m.tileH)
        for (index in 0 until COLUMNS) {
            val (x, _) = m.slot(index)
            assertTrue(x > m.dockX)
            assertTrue(x + m.tileW < m.dockX + m.dockWidth)
        }
    }

    @Test fun immersiveOptionDoesNotChangeFocusGeometry() {
        val a = metrics(); val b = metrics(true)
        assertTrue(b.hideNextRow)
        assertFalse(a.hideNextRow)
        for (index in 0..40) assertEquals(a.slot(index), b.slot(index))
        for (row in 0..6) assertEquals(a.scrollFor(row, 7), b.scrollFor(row, 7))
    }

    @Test fun partialRowsScrollIntoViewWithRoomForLabels() {
        for (count in listOf(7, 11, 14, 23, 47)) {
            val m = metrics()
            val rows = (count + COLUMNS - 1) / COLUMNS
            var lastScroll = 0f
            for (row in 0 until rows) {
                val scroll = m.scrollFor(row, rows).value
                assertTrue(scroll >= lastScroll)
                lastScroll = scroll
            }
            val y = (m.slot(count - 1).second - m.scrollFor(rows - 1, rows)).value
            assertTrue(y >= m.restY.value - 0.001f)
            assertTrue(y + m.tileH.value + 36f <= 540.001f)
        }
    }

    @Test fun enteringGridLeavesThePreviousRowFullyBelowTheHeader() {
        val m = metrics()
        val previousTop = m.slot(0).second - m.scrollFor(1, 4)
        assertTrue(previousTop >= 64.dp)
        assertTrue(previousTop + m.tileH < m.restY)
    }

    @Test fun enteringGridUsesSharedRestingPosition() {
        val m = metrics()
        assertEquals(0f, m.scrollFor(0, 3).value, 0f)
        assertEquals(m.restY.value, (m.slot(COLUMNS).second - m.scrollFor(1, 3)).value, 0.001f)
    }
}
