package app.opentv.launcher.wallpaper

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.opentv.launcher.apps.CustomIcons
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["app.opentv.launcher.apps", "app.opentv.launcher.wallpaper"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ImageImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun narrowPortraitAndPanoramaDecodeToBoundedPhotosAndCroppedIcons() {
        val cache = temporary.newFolder()
        val photos = temporary.newFolder()
        for ((w, h) in listOf(1000 to 30000, 30000 to 1000, 1 to 30000, 30000 to 1)) {
            val bytes = png(w, h)
            val photo = requireNotNull(ImageImport.photo(bytes.inputStream(), cache, photos))
            val decoded = requireNotNull(BitmapFactory.decodeFile(photo.path))
            try {
                assertTrue(decoded.width in 1..2560 && decoded.height in 1..2560)
                val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
                assertTrue(Color.red(center) >= 250 && Color.green(center) <= 4 && Color.blue(center) <= 4)
            } finally {
                decoded.recycle()
            }
            val icon = File(temporary.newFolder(), "icon.png")
            assertTrue(ImageImport.icon(bytes.inputStream(), cache, icon, 1280, 768))
            val cropped = requireNotNull(BitmapFactory.decodeFile(icon.path))
            try {
                assertEquals(1280, cropped.width)
                assertEquals(768, cropped.height)
                assertEquals(Color.RED, cropped.getPixel(640, 384))
            } finally {
                cropped.recycle()
            }
        }
        assertTrue(cache.listFiles()!!.isEmpty())
        assertEquals(4, photos.listFiles()!!.size)
    }

    @Test fun jpegPngAndWebpUseTheSameImportContract() {
        val cache = temporary.newFolder()
        val photos = temporary.newFolder()
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            val result = requireNotNull(ImageImport.photo(image(48, 32, format).inputStream(), cache, photos))
            val decoded = requireNotNull(BitmapFactory.decodeFile(result.path))
            try {
                assertEquals(48, decoded.width)
                assertEquals(32, decoded.height)
            } finally {
                decoded.recycle()
            }
        }
        assertEquals(3, photos.listFiles()!!.size)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun corruptAndOversizeInputsDoNotPublishOrChangeThePreviousIcon() {
        val cache = temporary.newFolder()
        val photos = temporary.newFolder()
        val icon = File(temporary.newFolder(), "previous.png")
        assertTrue(ImageImport.icon(image(32, 32).inputStream(), cache, icon, 1280, 768))
        val previous = icon.readBytes()
        for (bytes in listOf("not an image".toByteArray(), png(12000, 12000, pixels = false), png(1000, 30000, pixels = false))) {
            assertNull(ImageImport.photo(bytes.inputStream(), cache, photos))
            assertFalse(ImageImport.icon(bytes.inputStream(), cache, icon, 1280, 768))
            assertArrayEquals(previous, icon.readBytes())
            assertTrue(photos.listFiles()!!.isEmpty())
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test fun byteBudgetAndReadFailuresLeaveNoPublishedPhotoOrTemporaryInput() {
        val cache = temporary.newFolder()
        val photos = temporary.newFolder()
        val tooLarge = object : InputStream() {
            var consumed = 0L
            override fun read(): Int = 1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                return length
            }
        }
        assertNull(ImageImport.photo(tooLarge, cache, photos))
        assertTrue(tooLarge.consumed <= 32L * 1024 * 1024 + 8192)
        val failedRead = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic read failure")
        }
        assertNull(ImageImport.photo(failedRead, cache, photos))
        assertTrue(photos.listFiles()!!.isEmpty())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun publicationFailureReturnsFailureAndPreservesThePreviousDestination() {
        val cache = temporary.newFolder()
        val target = File(temporary.newFolder(), "blocked.png").apply { mkdir() }
        val previous = File(target, "previous").apply { writeText("previous") }
        assertFalse(ImageImport.icon(image(32, 32).inputStream(), cache, target, 1280, 768))
        assertEquals("previous", previous.readText())
        assertEquals(listOf(target), target.parentFile!!.listFiles()!!.toList())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun concurrentPhotoImportsDoNotOverwriteEachOtherOrLegacyNames() {
        val cache = temporary.newFolder()
        val photos = temporary.newFolder()
        val legacy = File(photos, "loyw3v28.jpg").apply { writeText("existing user photo") }
        val bytes = image(32, 32)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 12).map {
                pool.submit(Callable { requireNotNull(ImageImport.photo(bytes.inputStream(), cache, photos)) })
            }
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(12, results.map { it.name }.toSet().size)
            results.forEach { file ->
                val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
                try {
                    assertEquals(32, bitmap.width)
                    assertEquals(32, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            }
            assertEquals("existing user photo", legacy.readText())
            assertEquals(13, photos.listFiles()!!.size)
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun failedWallpaperImportKeepsItsPreviousSelectionAndPhotoList() {
        val context = isolatedContext()
        val photos = File(context.filesDir, "photos").apply { mkdir() }
        val legacy = File(photos, "loyw3v28.jpg").apply { writeBytes(image(48, 32, Bitmap.CompressFormat.JPEG)) }
        val legacyBytes = legacy.readBytes()
        File(photos, ".image-interrupted.pending").writeText("partial output")
        File(photos, "not-a-photo.jpg").mkdir()
        Wallpapers.init(context)
        assertTrue(Wallpapers.items.any { it.id == "photo:loyw3v28" })
        assertEquals(1, Wallpapers.items.count { it.kind == WallpaperKind.Photo })
        val id = runBlocking { Wallpapers.addPhoto(image(32, 32).inputStream()) }
        assertNotNull(id)
        assertEquals(id, Wallpapers.selectedId)
        val before = Wallpapers.items
        for (bytes in listOf("corrupt".toByteArray(), png(12000, 12000, pixels = false))) {
            assertNull(runBlocking { Wallpapers.addPhoto(bytes.inputStream()) })
            assertEquals(id, Wallpapers.selectedId)
            assertEquals(before, Wallpapers.items)
        }
        assertArrayEquals(legacyBytes, legacy.readBytes())
        Wallpapers.select("photo:../../invalid")
        assertEquals(id, Wallpapers.selectedId)
        assertEquals(id, context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).getString("selected", null))
    }

    @Test fun customIconPublicSaveLoadAndResetKeepTheSameFilenameAndFailureContract() {
        val context = isolatedContext()
        CustomIcons.init(context)
        val key = "app.opentv.synthetic_image_test"
        val file = File(context.filesDir, "icons/$key.png").apply { writeBytes(image(160, 90)) }
        val legacy = file.readBytes()
        requireNotNull(CustomIcons.load(key)).recycle()
        assertArrayEquals(legacy, file.readBytes())
        assertTrue(CustomIcons.save(key, image(32, 32).inputStream(), context.cacheDir))
        assertTrue(file.isFile)
        val previous = file.readBytes()
        assertFalse(CustomIcons.save(key, "corrupt".byteInputStream(), context.cacheDir))
        assertArrayEquals(previous, file.readBytes())
        val loaded = requireNotNull(CustomIcons.load(key))
        try {
            assertEquals(1280, loaded.width)
            assertEquals(768, loaded.height)
        } finally {
            loaded.recycle()
        }
        assertFalse(CustomIcons.save("../invalid", image(32, 32).inputStream(), context.cacheDir))
        CustomIcons.clear(key)
        assertFalse(CustomIcons.has(key))
        assertNull(CustomIcons.load(key))
    }

    private fun isolatedContext(): Context {
        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
    }

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            return ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(format, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun png(width: Int, height: Int, pixels: Boolean = true): ByteArray = ByteArrayOutputStream().use { bytes ->
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size)
            output.write(name)
            output.write(data)
            output.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        }
        val header = ByteArrayOutputStream().use { data ->
            DataOutputStream(data).apply {
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(8, 2, 0, 0, 0))
            }
            data.toByteArray()
        }
        chunk("IHDR", header)
        if (pixels) {
            val compressed = ByteArrayOutputStream().use { data ->
                val row = ByteArray(1 + width * 3)
                for (x in 0 until width) row[1 + x * 3] = 255.toByte()
                DeflaterOutputStream(data).use { zip -> repeat(height) { zip.write(row) } }
                data.toByteArray()
            }
            chunk("IDAT", compressed)
        }
        chunk("IEND", byteArrayOf())
        bytes.toByteArray()
    }
}
