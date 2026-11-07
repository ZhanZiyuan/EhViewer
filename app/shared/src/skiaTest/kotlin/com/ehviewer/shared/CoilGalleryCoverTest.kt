package com.ehviewer.shared

import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import coil3.BitmapImage
import coil3.Image
import coil3.PlatformContext
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class CoilGalleryCoverTest {
    private val request = GalleryCoverRequest(1, "0123456789", "owned-cover")

    private fun assertRed(image: Image) {
        val pixels = (image as BitmapImage).bitmap.asComposeImageBitmap().toPixelMap()
        val color = pixels[0, 0]
        assertTrue(color.red > 0.99f && color.blue < 0.01f)
    }

    @Test fun actualCoilFetchDecodeAndPageCacheRetainBoundedPixelsWithoutAnotherHiddenCache() = runTest {
        val png = SkiaGalleryCoverTest().ownedPng(2048, 256)
        var loads = 0
        val loader = GalleryCoverLoader(PlatformContext.INSTANCE) {
            assertEquals(request, it)
            loads++
            png
        }
        try {
            val cache = GalleryCoverCache(load = loader::get)
            val first = cache.get(request)
            assertEquals(512 to 64, first.width to first.height)
            val color = (first.value as BitmapImage).bitmap.asComposeImageBitmap().toPixelMap()[0, 0]
            assertTrue(color.red > 0.99f && color.blue > 0.99f && color.green < 0.01f)
            assertSame(first, cache.get(request))
            assertEquals(1, loads)
            loader.get(request)
            assertEquals(2, loads) // Coil must not retain a second cache beside the bounded page LRU.
            loader.close()
            assertEquals(512, first.value.width)
            assertTrue((first.value as BitmapImage).bitmap.asComposeImageBitmap().toPixelMap()[0, 0].red > 0.99f)
            assertFailsWith<CancellationException> { loader.get(request) }
            assertEquals(2, loads)
        } finally {
            loader.close()
        }
    }

    @Test fun coilStillUsesStaticFirstFrameForGifAndWebpCovers() = runTest {
        for (bytes in listOf(GalleryAnimationFixtures.gif, GalleryAnimationFixtures.webp)) {
            val loader = GalleryCoverLoader(PlatformContext.INSTANCE) { bytes }
            try {
                val image = loader.get(request)
                assertEquals(2 to 1, image.width to image.height)
                assertRed(image.value)
            } finally {
                loader.close()
            }
        }
    }

    @Test fun sanitizedTransportAndCorruptDecodeFailuresRequireExplicitRetryAndCanRecover() = runTest {
        var attempts = 0
        val loader = GalleryCoverLoader(PlatformContext.INSTANCE) {
            when (++attempts) {
                1 -> throw GalleryRequestException(GalleryFailure.OFFLINE)
                2 -> byteArrayOf(1, 2, 3)
                else -> GalleryAnimationFixtures.gif
            }
        }
        try {
            val cache = GalleryCoverCache(load = loader::get)
            assertEquals(GalleryFailure.OFFLINE, assertFailsWith<GalleryRequestException> { cache.get(request) }.failure)
            assertEquals(1, attempts)
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { cache.get(request) }.failure)
            assertEquals(2, attempts)
            assertRed(cache.get(request).value)
            assertEquals(3, attempts)
        } finally {
            loader.close()
        }
    }

    @Test fun cancellingAnActualCoilRequestReleasesPageAdmissionAndDoesNotPublishItsImage() = runTest {
        val started = CompletableDeferred<Unit>()
        var attempts = 0
        val loader = GalleryCoverLoader(PlatformContext.INSTANCE) {
            if (++attempts == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
            GalleryAnimationFixtures.gif
        }
        try {
            val cache = GalleryCoverCache(load = loader::get)
            val pending = async { cache.get(request) }
            started.await()
            pending.cancelAndJoin()
            assertRed(cache.get(request).value)
            assertEquals(2, attempts)
        } finally {
            loader.close()
        }
    }

    @Test fun closingTheOwnerCancelsAnInFlightExecuteAndNeverStartsNewTransportWork() = runTest {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var attempts = 0
        val loader = GalleryCoverLoader(PlatformContext.INSTANCE) {
            attempts++
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        }
        val pending = async { loader.get(request) }
        started.await()
        loader.close()
        assertFailsWith<CancellationException> { pending.await() }
        finished.await()
        assertFailsWith<CancellationException> { loader.get(request) }
        assertEquals(1, attempts)
        loader.close()
    }
}
