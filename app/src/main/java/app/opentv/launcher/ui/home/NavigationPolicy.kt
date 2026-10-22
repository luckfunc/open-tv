package app.opentv.launcher.ui.home

internal enum class GridDirection { Left, Right, Up, Down }

/** Pure presentation decisions; input ownership and focus traversal never depend on this mode. */
internal object NavigationPolicy {
    fun scale(instant: Boolean, reduced: Boolean, pressed: Boolean, focused: Boolean): Float = when {
        reduced -> 1f
        pressed -> 1.03f
        focused -> if (instant) 1.12f else 1.18f
        else -> 1f
    }

    /** Keep row boundaries explicit; Compose geometry must not choose another row on Left/Right. */
    fun neighbor(index: Int, count: Int, direction: GridDirection): Int? {
        if (index !in 0 until count) return null
        return when (direction) {
            GridDirection.Left -> (index - 1).takeIf { index % COLUMNS > 0 }
            GridDirection.Right -> (index + 1).takeIf { index % COLUMNS < COLUMNS - 1 && it < count }
            GridDirection.Up -> (index - COLUMNS).takeIf { it >= 0 }
            GridDirection.Down -> minOf(index + COLUMNS, count - 1).takeIf { index / COLUMNS < (count - 1) / COLUMNS }
        }
    }

    /** Select one precomposed background; never stack two full-screen images during scroll. */
    fun backgroundProgress(scroll: Float, collapse: Float): Float {
        val progress = if (collapse > 0f) scroll / collapse else 0f
        return if (progress >= 0.5f) 1f else 0f
    }
}
