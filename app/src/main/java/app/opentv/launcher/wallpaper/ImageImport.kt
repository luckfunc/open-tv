package app.opentv.launcher.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Owns untrusted image parsing, bounded bitmap allocations, and all-or-nothing publication. */
internal object ImageImport {
    fun photo(input: InputStream, cacheDir: File, directory: File): File? = attempt {
        uploaded(input, cacheDir) { source ->
            render(source) { w, h, mime -> ImageDecodePlan.photo(w, h, mime) }?.let { bitmap ->
                try {
                    AtomicImageStorage.photo(directory) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    fun icon(input: InputStream, cacheDir: File, target: File, width: Int, height: Int): Boolean = attempt {
        uploaded(input, cacheDir) { source ->
            render(source) { w, h, mime -> ImageDecodePlan.cover(w, h, mime, width, height) }?.let { bitmap ->
                try {
                    AtomicImageStorage.replace(target) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    } != null

    fun decodeCover(source: File, width: Int, height: Int): Bitmap? = attempt {
        if (source.length() > ImageDecodePlan.MAX_INPUT_BYTES) return@attempt null
        render(source) { w, h, mime -> ImageDecodePlan.cover(w, h, mime, width, height) }
    }

    private fun <T> uploaded(input: InputStream, cacheDir: File, use: (File) -> T): T {
        val source = File.createTempFile("image-import-", ".bin", cacheDir)
        try {
            source.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > ImageDecodePlan.MAX_INPUT_BYTES) throw IOException("Image byte budget exceeded")
                    output.write(buffer, 0, count)
                }
            }
            return use(source)
        } finally {
            source.delete()
        }
    }

    private fun render(source: File, parse: (Int, Int, String?) -> ImageDecodePlan?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val plan = parse(bounds.outWidth, bounds.outHeight, bounds.outMimeType) ?: return null
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inSampleSize = plan.sampleSize
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        if (bitmap.width > plan.decodedWidth || bitmap.height > plan.decodedHeight) {
            bitmap.recycle()
            return null
        }
        if (bitmap.width == plan.outputWidth && bitmap.height == plan.outputHeight) return bitmap
        return try {
            Images.cover(bitmap, plan.outputWidth, plan.outputHeight)
        } finally {
            bitmap.recycle()
        }
    }

    private inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
