package app.opentv.launcher.wallpaper

import androidx.compose.ui.graphics.ImageBitmap

/** One publication keeps the visible image and both prepared variants in the same generation. */
data class BackdropImages(val clear: ImageBitmap, val diffused: ImageBitmap, val grid: ImageBitmap)
