package com.ehviewer.shared

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryQueryError
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GalleryBrowsePagesTest {
    private fun page(vararg ids: Long, prev: String? = null, next: String? = null) = GallerySearchResult(prev, next, ids.map { BaseGalleryInfo(gid = it, token = "0123456789") })

    @Test fun appendingRetainsRealEarlierResultsAndTheOriginalBeginning() {
        val current = page(1, 2, prev = "5", next = "10")
        val result = mergeGalleryBrowsePages(current, page(2, 3, next = "20"), "10", false, emptySet())
        assertEquals(listOf(1L, 2L, 3L), result.galleryInfoList.map { it.gid })
        assertEquals("5", result.prev)
        assertEquals("20", result.next)
        assertEquals(listOf(1L, 2L), current.galleryInfoList.map { it.gid })
    }

    @Test fun prependingRetainsTheExistingEndAndDeduplicatesKeys() {
        val result = mergeGalleryBrowsePages(page(3, 4, prev = "10", next = "30"), page(1, 2, 3, prev = "5"), "10", true, emptySet())
        assertEquals(listOf(1L, 2L, 3L, 4L), result.galleryInfoList.map { it.gid })
        assertEquals("5", result.prev)
        assertEquals("30", result.next)
    }

    @Test fun anActualEndOrEmptyContinuationDoesNotEraseLoadedGalleries() {
        val result = mergeGalleryBrowsePages(page(1, next = "10"), page(), "10", false, emptySet())
        assertEquals(listOf(1L), result.galleryInfoList.map { it.gid })
        assertEquals(null, result.next)
    }

    @Test fun wrongRepeatedAndCyclicCursorsCannotCreateAnAutomaticRequestLoop() {
        for (previous in listOf(false, true)) {
            val current = page(1, prev = "10", next = "10")
            val valid = page(2, prev = "20", next = "20")
            for ((cursor, incoming, consumed) in listOf(
                Triple("99", valid, emptySet()),
                Triple("10", valid, setOf("10" to previous)),
                Triple("10", page(2, prev = "10", next = "10"), emptySet()),
                Triple("10", valid, setOf("20" to previous)),
            )) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { mergeGalleryBrowsePages(current, incoming, cursor, previous, consumed) }.failure)
            }
        }
    }

    @Test fun queryErrorsAreReportedWithoutReplacingTheSuccessfulPage() {
        val current = page(1, next = "10")
        val incoming = page().copy(queryError = GalleryQueryError.TOO_MANY_KEYWORDS)
        assertEquals(GalleryFailure.SEARCH_QUERY, assertFailsWith<GalleryRequestException> { mergeGalleryBrowsePages(current, incoming, "10", false, emptySet()) }.failure)
        assertEquals(listOf(1L), current.galleryInfoList.map { it.gid })
    }
}
