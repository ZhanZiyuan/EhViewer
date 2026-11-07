package com.ehviewer.shared

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private const val BORDER_WHITE = -1
private const val BORDER_BLACK = -0x1000000
private val borderGreen = 0xff00ff00.toInt()

private class BorderTestPixels(val width: Int, val height: Int, val color: (Int, Int) -> Int) : ReaderBorderPixels {
    var reads = 0
    var sampledPixels = 0L
    var maximumBuffer = 0
    override fun read(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        check(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= this.width && y + height <= this.height)
        check(width == 1 || height == 1)
        check(pixels.size >= width * height)
        reads++
        sampledPixels += width.toLong() * height
        maximumBuffer = maxOf(maximumBuffer, pixels.size)
        for (row in 0 until height) {
            for (column in 0 until width) pixels[row * width + column] = color(x + column, y + row)
        }
    }
}

private fun borderFixture(width: Int, height: Int, content: IntRect, background: Int) = BorderTestPixels(width, height) { x, y ->
    if (x in content.left until content.right && y in content.top until content.bottom) borderGreen else background
}

class GalleryReaderBorderTest {
    @Test fun whiteAndBlackBordersKeepTheOriginalInteriorAndCoordinates() = runTest {
        val content = IntRect(4, 8, 74, 88)
        for (background in listOf(BORDER_WHITE, BORDER_BLACK)) {
            assertEquals(content, readerBorderBounds(80, 96, borderFixture(80, 96, content, background)))
        }
    }

    @Test fun fullBleedMixedEdgesArePreserved() = runTest {
        val source = BorderTestPixels(80, 96) { x, y -> if ((x / 2 + y / 2) % 2 == 0) BORDER_BLACK else BORDER_WHITE }
        assertEquals(IntRect(0, 0, 80, 96), readerBorderBounds(80, 96, source))
    }

    @Test fun longStripsAreNeitherScannedNorCropped() = runTest {
        for ((width, height) in listOf(80 to 160, 160 to 80, 1 to 32768)) {
            val source = BorderTestPixels(width, height) { _, _ -> error("Long strips must retain the original exclusion") }
            assertEquals(IntRect(0, 0, width, height), readerBorderBounds(width, height, source))
            assertEquals(0, source.reads)
        }
    }

    @Test fun croppingExactlyOneQuarterIsRejected() = runTest {
        val excessive = IntRect(10, 4, 70, 76)
        assertEquals(IntRect(0, 0, 80, 80), readerBorderBounds(80, 80, borderFixture(80, 80, excessive, BORDER_WHITE)))
        val permitted = IntRect(10, 4, 71, 76)
        assertEquals(permitted, readerBorderBounds(80, 80, borderFixture(80, 80, permitted, BORDER_WHITE)))
    }

    @Test fun sparseInkUsesTheOriginalFilledLineThreshold() = runTest {
        for (ink in listOf(2, 3)) {
            val source = BorderTestPixels(1600, 1600) { x, y ->
                when {
                    y == 0 && x in (100 until 100 + ink * 4 step 4) -> BORDER_BLACK
                    x in 4 until 1596 && y in 4 until 1596 -> borderGreen
                    else -> BORDER_WHITE
                }
            }
            val expected = IntRect(4, if (ink == 2) 4 else 0, 1596, 1596)
            assertEquals(expected, readerBorderBounds(1600, 1600, source))
        }
    }

    @Test fun transparentMarginsUseTheOriginalPremultipliedLuma() = runTest {
        val source = BorderTestPixels(80, 80) { x, y -> if (x in 4 until 76 && y in 4 until 76) BORDER_WHITE else 0x00ffffff }
        assertEquals(IntRect(4, 4, 76, 76), readerBorderBounds(80, 80, source))
    }

    @Test fun theExistingMidGrayThresholdOrderingIsRetained() = runTest {
        val source = BorderTestPixels(80, 80) { _, _ -> 0xff646464.toInt() }
        assertEquals(IntRect(1, 1, 79, 79), readerBorderBounds(80, 80, source))
    }

    @Test fun aMaximumBlankImageUsesOneLineAndAtMostAQuarterScan() = runTest {
        val source = BorderTestPixels(4096, 4096) { _, _ -> BORDER_WHITE }
        assertEquals(IntRect(0, 0, 4096, 4096), readerBorderBounds(4096, 4096, source))
        assertTrue(source.maximumBuffer <= 4096)
        assertTrue(source.sampledPixels <= 4096L * 4096 / 4)
    }

    @Test fun invalidDimensionsCannotReachThePixelReader() = runTest {
        var reads = 0
        val source = ReaderBorderPixels { _, _, _, _, _ -> reads++ }
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 10, 32769 to 1, 8192 to 8192, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { readerBorderBounds(width, height, source) }
        }
        assertEquals(0, reads)
    }

    @Test fun cancellationStopsBeforeReadingAnotherLine() = runTest {
        var reads = 0
        lateinit var worker: Job
        worker = launch {
            readerBorderBounds(80, 80) { _, _, _, _, pixels ->
                reads++
                pixels.fill(BORDER_WHITE)
                worker.cancel()
            }
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertEquals(1, reads)
    }

    @Test fun aFailedPixelReadCannotBecomeInventedBounds() = runTest {
        val failure = IllegalStateException("Owned pixel-read failure")
        assertSame(failure, assertFailsWith<IllegalStateException> { readerBorderBounds(80, 80) { _, _, _, _, _ -> throw failure } })
    }
}
