package app.opentv.launcher.wallpaper

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicImageStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun noFinalPhotoExistsUntilEncodingHasSucceeded() {
        val dir = temporary.newFolder()
        val result = AtomicImageStorage.photo(dir) { output ->
            output.write(byteArrayOf(1, 2, 3))
            assertTrue(dir.listFiles()!!.none { it.extension == "jpg" })
            true
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), result.readBytes())
        assertEquals(listOf(result), dir.listFiles()!!.toList())
    }

    @Test fun failedEncodingAndOutputLeaveThePreviousAssetUntouched() {
        val dir = temporary.newFolder()
        val target = File(dir, "previous.png").apply { writeText("previous asset") }
        for (throws in listOf(false, true)) {
            assertThrows(IOException::class.java) {
                AtomicImageStorage.replace(target) { output ->
                    output.write("partial new asset".toByteArray())
                    assertEquals("previous asset", target.readText())
                    if (throws) throw IOException("synthetic disk failure")
                    false
                }
            }
            assertEquals("previous asset", target.readText())
            assertEquals(listOf(target), dir.listFiles()!!.toList())
        }
    }

    @Test fun failedPhotoEncodingNeverListsAPartialPhoto() {
        val dir = temporary.newFolder()
        assertThrows(IOException::class.java) {
            AtomicImageStorage.photo(dir) { output ->
                output.write(1)
                throw IOException("synthetic output failure")
            }
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun failedRenamePreservesTheExistingDestinationAndCleansTheStage() {
        val dir = temporary.newFolder()
        val target = File(dir, "blocked.png").apply { mkdir() }
        val previous = File(target, "previous").apply { writeText("keep") }
        assertThrows(IOException::class.java) {
            AtomicImageStorage.replace(target) { it.write(1); true }
        }
        assertEquals("keep", previous.readText())
        assertEquals(listOf(target), dir.listFiles()!!.toList())
    }

    @Test fun replacementPublishesACompleteAssetWithoutRemovingTheOldOneFirst() {
        val dir = temporary.newFolder()
        val target = File(dir, "app.png").apply { writeText("old") }
        AtomicImageStorage.replace(target) { output ->
            output.write("new".toByteArray())
            assertEquals("old", target.readText())
            true
        }
        assertEquals("new", target.readText())
        assertEquals(listOf(target), dir.listFiles()!!.toList())
    }

    @Test fun simultaneousPhotosHaveIndependentIdentitiesAndPreserveLegacyFiles() {
        val dir = temporary.newFolder()
        val legacy = File(dir, "same-clock.jpg").apply { writeText("legacy") }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 64).map { number ->
                pool.submit(Callable {
                    start.await()
                    AtomicImageStorage.photo(dir) { it.write(number.toString().toByteArray()); true } to number
                })
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(64, results.map { it.first.name }.toSet().size)
            results.forEach { (file, number) -> assertEquals(number.toString(), file.readText()) }
            assertEquals("legacy", legacy.readText())
            assertEquals(65, dir.listFiles()!!.size)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
