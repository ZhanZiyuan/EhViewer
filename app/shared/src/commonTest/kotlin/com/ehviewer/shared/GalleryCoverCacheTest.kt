package com.ehviewer.shared

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class GalleryCoverCacheTest {
    private fun cover(key: String) = GalleryCoverRequest(1, "0123456789", key)

    @Test fun sameImageSharesBytesAndLruEvictsWithinTheActualPixelBudget() = runTest {
        var loads = 0
        val cache = GalleryCoverCache<String>(32) {
            loads++
            PreviewBitmap(it.key, 2, 2)
        }
        assertEquals("a", cache.get(cover("a")).value)
        assertEquals("a", cache.get(cover("a").copy(gid = 2)).value)
        cache.get(cover("b"))
        cache.get(cover("a"))
        cache.get(cover("c"))
        assertEquals(3, loads)
        cache.get(cover("b"))
        assertEquals(4, loads)
        assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { cache.get(cover("b").copy(gid = 0)) }.failure)
        assertEquals(4, loads)
    }

    @Test fun cancelledVisibleRequestReleasesAdmissionAndNeverPublishesAStaleImage() = runTest {
        val started = CompletableDeferred<Unit>()
        var block = true
        var loads = 0
        val cache = GalleryCoverCache<String>(16) {
            loads++
            if (block) {
                started.complete(Unit)
                awaitCancellation()
            }
            PreviewBitmap("fresh", 1, 1)
        }
        val request = async { cache.get(cover("a")) }
        started.await()
        request.cancelAndJoin()
        block = false
        assertEquals("fresh", cache.get(cover("a")).value)
        assertEquals(2, loads)
    }

    @Test fun oversizedAndInvalidDecodesAreNotCachedAndAnExplicitRetryCanRecover() = runTest {
        for ((width, failure) in listOf(0 to GalleryFailure.INVALID_RESPONSE, 3 to GalleryFailure.RESPONSE_TOO_LARGE)) {
            var reject = true
            var loads = 0
            val cache = GalleryCoverCache<String>(16) {
                loads++
                PreviewBitmap("owned", if (reject) width else 1, if (reject) 3 else 1)
            }
            assertEquals(failure, assertFailsWith<GalleryRequestException> { cache.get(cover("a")) }.failure)
            reject = false
            assertEquals("owned", cache.get(cover("a")).value)
            assertEquals(2, loads)
        }
    }
}
