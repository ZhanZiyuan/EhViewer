package com.ehviewer.shared

import com.ehviewer.core.model.BaseGalleryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GalleryPageOrderTest {
    private fun item(id: Long, title: String? = null, japanese: String? = null, rating: Float = -1f, pages: Int = 0) = BaseGalleryInfo(gid = id, title = title, titleJpn = japanese, rating = rating, pages = pages)

    @Test fun serviceOrderRetainsTheActualPageAndOtherOrdersNeverMutateItsModelsOrList() {
        val items = listOf(item(3, "B", rating = 1f, pages = 1), item(1, "A", rating = 5f, pages = 2), item(2, "C", rating = 3f, pages = 3))
        assertSame(items, GalleryPageOrder.SERVICE.arrange(items))
        for (order in GalleryPageOrder.entries) {
            val sorted = order.arrange(items)
            assertEquals(setOf(1L, 2L, 3L), sorted.map { it.gid }.toSet())
            sorted.forEach { item -> assertSame(items.single { it.gid == item.gid }, item) }
            assertEquals(listOf(3L, 1L, 2L), items.map { it.gid })
        }
    }

    @Test fun titleUsesCaseIndependentTextAndJapaneseFallbackWithStableTiesAndUnknownsLast() {
        val items = listOf(item(1, "beta"), item(2, " Alpha "), item(3, "alpha"), item(4, "  ", "日本語"), item(5), item(6, ""))
        assertEquals(listOf(2L, 3L, 1L, 4L, 5L, 6L), GalleryPageOrder.TITLE.arrange(items).map { it.gid })
    }

    @Test fun ratingKeepsAllUnavailableOrInvalidValuesAfterRealRatingsWithoutReorderingTheirTies() {
        val items = listOf(item(1, rating = Float.NaN), item(2, rating = 4.5f), item(3, rating = -1f), item(4, rating = 5f), item(5, rating = 4.5f), item(6, rating = Float.POSITIVE_INFINITY), item(7, rating = 0f), item(8, rating = 6f))
        assertEquals(listOf(4L, 2L, 5L, 7L, 1L, 3L, 6L, 8L), GalleryPageOrder.RATING.arrange(items).map { it.gid })
    }

    @Test fun pageCountUsesOnlyPositiveKnownCountsAndRetainsServiceOrderForTiesAndUnknowns() {
        val items = listOf(item(1, pages = 0), item(2, pages = 12), item(3, pages = -1), item(4, pages = 20), item(5, pages = 12))
        assertEquals(listOf(4L, 2L, 5L, 1L, 3L), GalleryPageOrder.PAGES.arrange(items).map { it.gid })
    }
}
