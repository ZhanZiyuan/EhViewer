package com.ehviewer.shared

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenPixelsTest {
    private fun image() = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB).apply {
        for (y in 6..14) for (x in 8..19) setRGB(x, y, 0xff405080.toInt())
    }

    @Test
    fun equalNativePixelsPassWithoutAnyTolerance() {
        val result = compareGoldenPixels(image(), image(), Rectangle(0, 0, 32, 24))
        assertTrue(result.passed)
        assertEquals(768, result.compared)
    }

    @Test
    fun aSinglePixelLayoutShiftFailsAndLocatesItsActualBounds() {
        val shifted = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        for (y in 6..14) for (x in 9..20) shifted.setRGB(x, y, 0xff405080.toInt())
        val result = compareGoldenPixels(image(), shifted, Rectangle(0, 0, 32, 24))
        assertFalse(result.passed)
        assertEquals(18, result.different)
        assertEquals(Rectangle(8, 6, 13, 9), result.bounds)
    }

    @Test
    fun evenOneChannelLevelCannotSilentlyPassAsCloseEnough() {
        val actual = image().apply { setRGB(12, 9, 0xff405081.toInt()) }
        assertEquals(1, compareGoldenPixels(image(), actual, Rectangle(0, 0, 32, 24)).different)
        assertTrue(compareGoldenPixels(image(), actual, Rectangle(0, 0, 8, 24)).passed)
    }

    @Test
    fun viewportMismatchAndOutOfBoundsRegionsAreRejectedRatherThanRescaled() {
        assertFailsWith<IllegalArgumentException> { compareGoldenPixels(image(), BufferedImage(33, 24, BufferedImage.TYPE_INT_RGB), Rectangle(0, 0, 32, 24)) }
        assertFailsWith<IllegalArgumentException> { compareGoldenPixels(image(), image(), Rectangle(1, 0, 32, 24)) }
        assertFailsWith<IllegalArgumentException> { compareGoldenPixels(image(), image(), Rectangle(0, 0, 0, 24)) }
    }
}
