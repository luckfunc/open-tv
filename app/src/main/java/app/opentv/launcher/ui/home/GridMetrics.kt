package app.opentv.launcher.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val COLUMNS = 6

/** tvOS 26 reference proportions on the 960×540 canvas; all focus/reorder paths share this grid. */
internal class GridMetrics(screenW: Dp, screenH: Dp, oneRow: Boolean) {
    val margin = 48.dp
    val gap = 24.dp
    val tileW = (screenW - margin * 2 - gap * (COLUMNS - 1)) / COLUMNS
    val tileH = tileW * 3f / 5f
    val pitchX = tileW + gap
    val pitchY = tileH + 46.dp
    val topRowY = screenH * 0.765f
    val restY = 200.dp
    val dockX = 20.dp
    val dockTop = topRowY - 19.dp
    val dockHeight = tileH + 38.dp
    val dockWidth = screenW - dockX * 2
    val hideNextRow = oneRow
    private val screenH = screenH

    fun slot(index: Int) = Pair(margin + pitchX * (index % COLUMNS), topRowY + pitchY * (index / COLUMNS))

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row <= 0) return 0.dp
        val wanted = topRowY + pitchY * row - restY
        val bottom = topRowY + pitchY * rows + 36.dp - screenH
        return minOf(wanted, maxOf(bottom, topRowY + pitchY - restY))
    }
}
