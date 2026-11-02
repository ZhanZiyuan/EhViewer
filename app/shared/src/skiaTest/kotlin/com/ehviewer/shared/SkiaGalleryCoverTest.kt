package com.ehviewer.shared

import androidx.compose.ui.graphics.toPixelMap
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

class SkiaGalleryCoverTest {
    private fun ownedPng(width: Int, height: Int): ByteArray {
        val surface = checkNotNull(Surface.makeRasterN32Premul(width, height))
        try {
            surface.canvas.clear(0xffff00ff.toInt())
            val image = surface.makeImageSnapshot()
            try {
                val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
                return try {
                    data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }

    @Test fun realPngCoversAreSmallAspectCorrectAndReadableAfterAllTemporaryOwnersClose() = runTest {
        for ((size, expected) in listOf((2048 to 256) to (512 to 64), (256 to 2048) to (64 to 512), (2 to 1) to (2 to 1))) {
            val bitmap = decodeGalleryCover(ownedPng(size.first, size.second))
            assertEquals(expected, bitmap.width to bitmap.height)
            val color = bitmap.toPixelMap()[bitmap.width / 2, bitmap.height / 2]
            assertTrue(color.red > 0.99f && color.blue > 0.99f && color.green < 0.01f)
        }
    }

    @Test fun corruptCoverBytesRemainARecoverableImageFailure() = runTest {
        assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { decodeGalleryCover(byteArrayOf(1, 2, 3)) }.failure)
    }
}
