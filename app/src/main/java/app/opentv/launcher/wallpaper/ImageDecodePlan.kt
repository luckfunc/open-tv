package app.opentv.launcher.wallpaper

import kotlin.math.max
import kotlin.math.roundToInt

/** Parsed image metadata and allocation limits, shared by imports and existing-file reads. */
internal class ImageDecodePlan private constructor(
    val sampleSize: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    val peakPixels: Long get() = decodedWidth.toLong() * decodedHeight + outputWidth.toLong() * outputHeight

    companion object {
        const val MAX_INPUT_BYTES = 32L * 1024 * 1024
        const val MAX_SOURCE_PIXELS = 100_000_000L
        const val MAX_DECODED_PIXELS = 4_194_304L
        const val MAX_OUTPUT_PIXELS = 8_388_608L
        const val MAX_DECODED_EDGE = 4096
        private const val MAX_SOURCE_EDGE = 65535
        private const val PHOTO_EDGE = 2560
        private val types = setOf("image/jpeg", "image/png", "image/webp")

        fun photo(width: Int, height: Int, mime: String?): ImageDecodePlan? =
            parse(width, height, mime, PHOTO_EDGE, PHOTO_EDGE, photo = true)

        fun cover(width: Int, height: Int, mime: String?, targetWidth: Int, targetHeight: Int): ImageDecodePlan? =
            parse(width, height, mime, targetWidth, targetHeight, photo = false)

        private fun parse(width: Int, height: Int, mime: String?, w: Int, h: Int, photo: Boolean): ImageDecodePlan? {
            if (mime !in types || width !in 1..MAX_SOURCE_EDGE || height !in 1..MAX_SOURCE_EDGE ||
                width.toLong() * height > MAX_SOURCE_PIXELS || w !in 1..MAX_DECODED_EDGE ||
                h !in 1..MAX_DECODED_EDGE || w.toLong() * h > MAX_OUTPUT_PIXELS) return null

            var sample = 1
            fun ceilSample(n: Int, s: Int) = (n + s - 1) / s
            while (if (photo) max(width, height) / (sample * 2) >= PHOTO_EDGE
                else width / (sample * 2) >= w && height / (sample * 2) >= h) sample *= 2
            while (ceilSample(width, sample) > MAX_DECODED_EDGE || ceilSample(height, sample) > MAX_DECODED_EDGE ||
                ceilSample(width, sample).toLong() * ceilSample(height, sample) > MAX_DECODED_PIXELS) sample *= 2
            val dw = ceilSample(width, sample)
            val dh = ceilSample(height, sample)
            val scale = if (photo) minOf(1.0, PHOTO_EDGE.toDouble() / max(dw, dh)) else 1.0
            val ow = if (photo) (dw * scale).roundToInt().coerceAtLeast(1) else w
            val oh = if (photo) (dh * scale).roundToInt().coerceAtLeast(1) else h
            return ImageDecodePlan(sample, dw, dh, ow, oh)
        }
    }
}
