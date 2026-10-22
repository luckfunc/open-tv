package app.opentv.launcher.wallpaper

import org.junit.Assert.*
import org.junit.Test

class ImageDecodePlanTest {
    @Test fun portraitAndPanoramaMetadataHaveBoundedDecodeAndOutput() {
        for ((w, h) in listOf(1000 to 30000, 30000 to 1000, 1 to 65535, 65535 to 1, 10000 to 10000)) {
            val photo = requireNotNull(ImageDecodePlan.photo(w, h, "image/jpeg"))
            val icon = requireNotNull(ImageDecodePlan.cover(w, h, "image/png", 1280, 768))
            for (plan in listOf(photo, icon)) {
                assertTrue(plan.decodedWidth <= 4096)
                assertTrue(plan.decodedHeight <= 4096)
                assertTrue(plan.decodedWidth.toLong() * plan.decodedHeight <= 4_194_304)
                assertTrue(plan.outputWidth > 0 && plan.outputHeight > 0)
                assertTrue(plan.peakPixels <= 8_388_608)
            }
            assertTrue(photo.outputWidth <= 2560 && photo.outputHeight <= 2560)
            assertEquals(1280, icon.outputWidth)
            assertEquals(768, icon.outputHeight)
        }
    }

    @Test fun metadataAndOutputBudgetsAreValidatedBeforeAllocation() {
        for ((w, h) in listOf(0 to 10, 10 to -1, Int.MAX_VALUE to Int.MAX_VALUE, 10001 to 10000, 1 to 65536)) {
            assertNull(ImageDecodePlan.photo(w, h, "image/jpeg"))
        }
        for (mime in listOf(null, "image/gif", "image/svg+xml", "application/octet-stream")) {
            assertNull(ImageDecodePlan.photo(100, 100, mime))
        }
        assertNull(ImageDecodePlan.cover(100, 100, "image/jpeg", 0, 768))
        assertNull(ImageDecodePlan.cover(100, 100, "image/jpeg", Int.MAX_VALUE, 768))
        assertNull(ImageDecodePlan.cover(100, 100, "image/jpeg", 4096, 4096))
    }

    @Test fun fourKCoverIncludesTheDestinationInItsPeakBudget() {
        val plan = requireNotNull(ImageDecodePlan.cover(8000, 4500, "image/webp", 3840, 2160))
        assertEquals(3840, plan.outputWidth)
        assertEquals(2160, plan.outputHeight)
        assertTrue(plan.peakPixels <= 4_194_304L + 3840L * 2160)
    }

    @Test fun smallPhotosAreNotUpscaled() {
        val plan = requireNotNull(ImageDecodePlan.photo(640, 480, "image/jpeg"))
        assertEquals(1, plan.sampleSize)
        assertEquals(640, plan.outputWidth)
        assertEquals(480, plan.outputHeight)
    }
}
