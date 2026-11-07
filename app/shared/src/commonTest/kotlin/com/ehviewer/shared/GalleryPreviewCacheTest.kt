package com.ehviewer.shared

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class GalleryPreviewCacheTest {
    private val tile = V2GalleryPreview("https://ehgt.org/sprite.jpg", 0, "1111111111", 100, 101, 201)
    private fun detail(previews: List<GalleryPreview>, pages: Int = 5) = GalleryDetail(
        BaseGalleryInfo(gid = 1, token = "0123456789", pages = pages),
        tagGroups = emptyList(),
        comments = GalleryCommentList(emptyList(), false),
        previewList = previews,
    )
    private val first = detail(listOf(0, 1).map { tile.copy(position = it) })

    @Test fun actualSpriteBoundsExcludeSeparatorAndRejectOverflowOrOutsidePixels() {
        assertEquals(PreviewRegion(100, 100, 200), previewRegion(tile, 200, 200))
        assertEquals(PreviewRegion(0, 1, 2), previewRegion(V1GalleryPreview(tile.url, 0, tile.pToken), 1, 2))
        for (bad in listOf(tile.copy(offsetX = -1), tile.copy(offsetX = Int.MAX_VALUE), tile.copy(clipWidth = Int.MAX_VALUE), tile.copy(clipHeight = 1), tile.copy(clipWidth = 1), tile.copy(clipHeight = 202))) {
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { previewRegion(bad, 200, 200) }.failure)
        }
    }

    @Test fun lastPartialPreviewPageKeepsActualPositionsAndRejectsLayoutOrGalleryChanges() {
        assertEquals(3, previewPageCount(first))
        assertEquals(0, previewPageCount(detail(emptyList())))
        val last = detail(listOf(tile.copy(position = 4)))
        checkPreviewWindow(first, last, 2)
        for ((window, page) in listOf(last to 1, first to 2, first to -1, last to 3, detail(listOf(tile.copy(position = 4)), pages = 6) to 2, last.copy(galleryInfo = BaseGalleryInfo(gid = 2, token = last.token, pages = last.pages)) to 2)) {
            assertFailsWith<GalleryRequestException> { checkPreviewWindow(first, window, page) }
        }
        assertFailsWith<GalleryRequestException> { previewPageCount(detail(listOf(tile.copy(position = 1)))) }
    }

    @Test fun visibleCellsShareOneSpriteDownloadAndValidateEachTileBeforeUse() = runTest {
        var loads = 0
        val cache = GalleryPreviewCache<String> {
            loads++
            delay(10)
            PreviewBitmap("pixels", 300, 200)
        }
        val values = listOf(tile, tile.copy(offsetX = 200)).map { async { cache.get(it) } }.awaitAll()
        assertEquals(listOf("pixels", "pixels"), values.map { it.value })
        assertEquals(1, loads)
        assertFailsWith<GalleryRequestException> { cache.get(tile.copy(offsetX = 250)) }
        assertEquals(1, loads)
    }

    @Test fun decodedBudgetEvictsLeastRecentlyUsedSpriteAndNeverAcceptsOversizedImages() = runTest {
        val loads = mutableListOf<String>()
        val cache = GalleryPreviewCache<String>(budget = 8) { preview ->
            loads += preview.url
            PreviewBitmap(preview.url, 1, 1)
        }
        fun preview(url: String) = V1GalleryPreview(url, 0, tile.pToken)
        for (url in listOf("a", "b", "a", "c", "b")) cache.get(preview(url))
        assertEquals(listOf("a", "b", "c", "b"), loads)
        val oversized = GalleryPreviewCache<String>(budget = 3) { PreviewBitmap("pixels", 1, 1) }
        assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { oversized.get(preview("a")) }.failure)
        val invalid = GalleryPreviewCache<String> { PreviewBitmap("pixels", Int.MAX_VALUE, Int.MAX_VALUE) }
        assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { invalid.get(preview("a")) }.failure)
    }

    @Test fun failedOrCancelledDownloadIsNotCachedAndCanBeRetriedWithoutPoisoningOtherTiles() = runTest {
        var loads = 0
        val cache = GalleryPreviewCache<String> {
            when (++loads) {
                1 -> throw CancellationException("cancelled")
                2 -> throw GalleryRequestException(GalleryFailure.OFFLINE)
                else -> PreviewBitmap("pixels", 200, 200)
            }
        }
        assertFailsWith<CancellationException> { cache.get(tile) }
        assertFailsWith<GalleryRequestException> { cache.get(tile) }
        assertEquals("pixels", cache.get(tile).value)
        assertEquals("pixels", cache.get(tile).value)
        assertEquals(3, loads)
    }
}
