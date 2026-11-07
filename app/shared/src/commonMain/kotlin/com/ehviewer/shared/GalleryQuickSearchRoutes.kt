package com.ehviewer.shared

import com.ehviewer.core.domain.GalleryLaunchPage
import com.ehviewer.core.domain.GalleryQuickSearch
import com.ehviewer.core.domain.GalleryToplistPeriod
import com.ehviewer.core.network.gallery.GallerySearchFilters

// Existing Android ListUrlBuilder modes and AdvanceTable bits stored in QUICK_SEARCH.
private const val NORMAL = 0
private const val UPLOADER = 1
private const val TAG = 2
private const val POPULAR = 3
private const val SUBSCRIPTION = 5
private const val TOPLIST = 6

internal fun GallerySearchRequest.asQuickSearch(name: String): GalleryQuickSearch {
    val mode = when {
        uploader != null -> UPLOADER
        tag != null -> TAG
        feed == GalleryLaunchPage.SUBSCRIPTION -> SUBSCRIPTION
        feed == GalleryLaunchPage.WHATS_HOT -> POPULAR
        feed == GalleryLaunchPage.TOPLIST -> TOPLIST
        else -> NORMAL
    }
    val keyword = uploader ?: tag ?: if (mode == TOPLIST) toplistPeriod.originalCode else query.takeIf(String::isNotBlank)
    val flags = listOf(filters.showExpunged, filters.onlyWithTorrents, filters.disableLanguageFilter, filters.disableUploaderFilter, filters.disableTagFilter)
    val bits = flags.foldIndexed(0) { index, bits, selected -> if (selected) bits or (1 shl index) else bits }
    val advanced = if (bits != 0 || filters.minimumRating != null || filters.minimumPages != null || filters.maximumPages != null) bits else -1
    return GalleryQuickSearch(name, mode, keyword, filters.includedCategories ?: -1, advanced, filters.minimumRating ?: -1, filters.minimumPages ?: -1, filters.maximumPages ?: -1)
}

internal fun GalleryQuickSearch.toSearchRequest(): GallerySearchRequest? {
    val value = keyword.orEmpty()
    if (value.length > 4096) return null
    val filter = try {
        val enabled = advanceSearch > 0 || minRating > 0 || pageFrom > 0 || pageTo > 0
        val bits = if (enabled) advanceSearch else 0
        GallerySearchFilters(
            includedCategories = category.takeIf { it > 0 },
            minimumRating = minRating.takeIf { it > 0 },
            minimumPages = pageFrom.takeIf { it > 0 },
            maximumPages = pageTo.takeIf { it > 0 },
            showExpunged = bits and 1 != 0,
            onlyWithTorrents = bits and 2 != 0,
            disableLanguageFilter = bits and 4 != 0,
            disableUploaderFilter = bits and 8 != 0,
            disableTagFilter = bits and 16 != 0,
        )
    } catch (_: IllegalArgumentException) {
        return null
    }
    return when (mode) {
        NORMAL -> GallerySearchRequest(value, filter, cursor = next, feed = GalleryLaunchPage.HOMEPAGE)
        UPLOADER -> value.takeIf(String::isNotBlank)?.let { GallerySearchRequest("uploader:$it", filter, cursor = next, uploader = it, feed = GalleryLaunchPage.HOMEPAGE) }
        TAG -> value.takeIf(String::isNotBlank)?.let { GallerySearchRequest(it, filter, cursor = next, tag = it, feed = GalleryLaunchPage.HOMEPAGE) }
        POPULAR -> GallerySearchRequest("", filter, feed = GalleryLaunchPage.WHATS_HOT)
        SUBSCRIPTION -> GallerySearchRequest(value, filter, cursor = next, feed = GalleryLaunchPage.SUBSCRIPTION)
        TOPLIST -> GalleryToplistPeriod.entries.firstOrNull { it.originalCode == value }?.let { period ->
            GallerySearchRequest("", filter, cursor = next?.takeIf { it.toIntOrNull()?.let { page -> page in 1..200 } == true }, feed = GalleryLaunchPage.TOPLIST, toplistPeriod = period)
        }
        else -> null
    }
}
