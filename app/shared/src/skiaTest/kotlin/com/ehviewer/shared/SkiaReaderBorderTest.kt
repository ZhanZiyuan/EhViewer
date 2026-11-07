package com.ehviewer.shared

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

internal fun borderedReaderBitmap(): ImageBitmap = ImageBitmap(80, 96).also { bitmap ->
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { color = Color.White }
    canvas.drawRect(0f, 0f, 80f, 96f, paint)
    paint.color = Color.Green
    canvas.drawRect(4f, 8f, 74f, 88f, paint)
}

class SkiaReaderBorderTest {
    @Test fun nativeBitmapCroppingRetainsOriginalPixelsAndMemoryAccounting() = runTest {
        val bitmap = borderedReaderBitmap()
        val before = IntArray(80 * 96).also(bitmap::readPixels)
        val media = GalleryMedia(bitmap, null, galleryMediaCost(80, 96, 0))
        val cropped = media.withoutBorders()
        assertEquals(IntRect(4, 8, 74, 88), cropped.bounds)
        assertEquals(70, cropped.width)
        assertEquals(80, cropped.height)
        assertEquals(Size(70f, 80f), cropped.posterPainter().intrinsicSize)
        assertSame(bitmap, cropped.poster)
        assertSame(cropped, media.withoutBorders())
        assertEquals(media.bytes, cropped.bytes)
        assertEquals(IntRect(0, 0, 80, 96), media.bounds)
        assertContentEquals(before, IntArray(80 * 96).also(bitmap::readPixels))
    }

    @Test fun actualAnimatedPosterIsNeverCroppedOrConvertedToAStaticPage() = runTest {
        val media = decodeGalleryMedia(GalleryAnimationFixtures.gif)
        assertSame(media, media.withoutBorders())
        assertEquals(IntRect(0, 0, media.poster.width, media.poster.height), media.bounds)
    }
}
