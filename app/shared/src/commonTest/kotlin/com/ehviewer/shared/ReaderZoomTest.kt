package com.ehviewer.shared

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderZoomTest {
    @Test fun zoomKeepsThePointerAnchorAndDoesNotPanAcrossLetterboxing() {
        val viewport = IntSize(200, 200)
        assertEquals(ReaderZoom(2f, Offset(-50f, 0f)), readerZoom(ReaderZoom(), viewport, IntSize(400, 200), 2f, Offset(150f, 100f)))
        assertEquals(ReaderZoom(2f, Offset(0f, -50f)), readerZoom(ReaderZoom(), viewport, IntSize(200, 400), 2f, Offset(100f, 150f)))
        assertEquals(ReaderZoom(2f, Offset(100f, 0f)), readerZoom(ReaderZoom(2f), viewport, IntSize(400, 200), 1f, delta = Offset(10_000f, -10_000f)))
    }

    @Test fun zoomLimitsAndFitResetKeepTheDecodedImageInsideItsRealBounds() {
        val viewport = IntSize(200, 200)
        val image = IntSize(400, 200)
        val maximum = readerZoom(ReaderZoom(2f, Offset(50f, 0f)), viewport, image, 100f, delta = Offset(10_000f, 10_000f))
        assertEquals(ReaderZoom(5f, Offset(400f, 150f)), maximum)
        assertEquals(ReaderZoom(), readerZoom(maximum, viewport, image, 0.01f))
        assertEquals(ReaderZoom(2f, Offset(0f, 100f)), readerZoom(ReaderZoom(2f, Offset(100f, 100f)), viewport, IntSize(100, 800), 1f))
    }

    @Test fun unreadyImagesAndInvalidDeviceDeltasCannotIntroduceNonFiniteTransforms() {
        val original = ReaderZoom(2f, Offset(10f, 0f))
        val viewport = IntSize(200, 200)
        for (factor in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(original, readerZoom(original, viewport, viewport, factor))
        assertEquals(original, readerZoom(original, IntSize.Zero, viewport, 2f))
        assertEquals(original, readerZoom(original, viewport, IntSize.Zero, 2f))
        assertEquals(original, readerZoom(original, viewport, viewport, 2f, delta = Offset(Float.NaN, 0f)))
    }
}
