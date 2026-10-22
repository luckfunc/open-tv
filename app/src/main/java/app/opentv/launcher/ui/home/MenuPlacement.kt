package app.opentv.launcher.ui.home

internal data class MenuPlacement(val x: Int, val y: Int)

internal fun placeAnchoredMenu(
    viewportWidth: Int,
    viewportHeight: Int,
    menuWidth: Int,
    menuHeight: Int,
    anchorLeft: Int,
    anchorTop: Int,
    anchorBottom: Int,
    safeInset: Int,
    gap: Int,
): MenuPlacement {
    val minX = safeInset.coerceAtMost((viewportWidth - menuWidth).coerceAtLeast(0))
    val minY = safeInset.coerceAtMost((viewportHeight - menuHeight).coerceAtLeast(0))
    val maxX = (viewportWidth - safeInset - menuWidth).coerceAtLeast(minX)
    val maxY = (viewportHeight - safeInset - menuHeight).coerceAtLeast(minY)
    val below = anchorBottom + gap
    val above = anchorTop - gap - menuHeight
    val opensBelow = when {
        below <= maxY -> true
        above >= minY -> false
        else -> viewportHeight - anchorBottom >= anchorTop
    }
    return MenuPlacement(
        x = anchorLeft.coerceIn(minX, maxX),
        y = (if (opensBelow) below else above).coerceIn(minY, maxY),
    )
}
