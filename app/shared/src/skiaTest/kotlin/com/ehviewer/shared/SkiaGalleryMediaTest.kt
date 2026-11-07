package com.ehviewer.shared

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

class SkiaGalleryMediaTest {
    private inline fun <T> Image.withImage(block: (Image) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun assertColor(image: Image, red: Boolean) {
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        val color = image.toComposeImageBitmap().toPixelMap()[0, 0]
        assertTrue(if (red) color.red > 0.99f && color.blue < 0.01f else color.blue > 0.99f && color.red < 0.01f)
    }

    @Test fun gifFramesUseRealNativePixelsDurationsAndInfiniteLoopMetadata() {
        SkiaGalleryFrames(GalleryAnimationFixtures.gif).use { decoder ->
            assertEquals(2, decoder.count)
            assertEquals(-1, decoder.repetitions)
            assertEquals(120, decoder.duration(0))
            assertEquals(180, decoder.duration(1))
            decoder.frame(0).withImage { assertColor(it, true) }
            decoder.frame(1).withImage { assertColor(it, false) }
        }
    }

    @Test fun webpFramesUseRealNativePixelsDurationsAndInfiniteLoopMetadata() {
        SkiaGalleryFrames(GalleryAnimationFixtures.webp).use { decoder ->
            assertEquals(2, decoder.count)
            assertEquals(-1, decoder.repetitions)
            assertEquals(120, decoder.duration(0))
            assertEquals(180, decoder.duration(1))
            decoder.frame(0).withImage { assertColor(it, true) }
            decoder.frame(1).withImage { assertColor(it, false) }
        }
    }

    @Test fun finiteLoopMetadataDoesNotBecomeInfinite() {
        SkiaGalleryFrames(GalleryAnimationFixtures.finiteGif).use { assertEquals(1, it.repetitions) }
    }

    @Test fun partialFramesAndRestorePreviousDisposalProduceTheCorrectComposite() {
        SkiaGalleryFrames(GalleryAnimationFixtures.disposalGif).use { decoder ->
            assertEquals(3, decoder.count)
            decoder.frame(1).withImage { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                assertTrue(pixels[0, 0].blue > 0.99f && pixels[1, 0].red > 0.99f)
            }
            decoder.frame(2).withImage { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                assertTrue(pixels[0, 0].red > 0.99f && pixels[0, 0].blue < 0.01f && pixels[1, 0].green > 0.99f)
            }
        }
    }

    @Test fun publishedSnapshotsRemainImmutableAfterOtherFramesAndCodecClose() {
        val decoder = SkiaGalleryFrames(GalleryAnimationFixtures.gif)
        val first = decoder.frame(0)
        val second = decoder.frame(1)
        try {
            decoder.close()
            decoder.close()
            assertColor(first, true)
            assertColor(second, false)
            assertFailsWith<IllegalStateException> { decoder.frame(0) }
        } finally {
            first.close()
            second.close()
            decoder.close()
        }
    }

    @Test fun repeatedOpenDecodeCloseAndFrameRewindPreserveActualPixels() {
        assertEquals(2, verifyAnimatedGalleryProduct())
        repeat(50) {
            SkiaGalleryFrames(if (it % 2 == 0) GalleryAnimationFixtures.gif else GalleryAnimationFixtures.webp).use { decoder ->
                for (index in listOf(0, 1, 0, 1)) decoder.frame(index).withImage { image -> assertColor(image, index == 0) }
                assertFailsWith<IllegalStateException> { decoder.frame(2) }
            }
        }
    }

    @Test fun malformedOrStaticInputCannotCreateAnAnimationOwner() {
        for (bytes in listOf(byteArrayOf(), GalleryAnimationFixtures.gif.copyOf(20), GalleryAnimationFixtures.webp.copyOf(25))) {
            assertFailsWith<Exception> { SkiaGalleryFrames(bytes) }
        }
    }
}
