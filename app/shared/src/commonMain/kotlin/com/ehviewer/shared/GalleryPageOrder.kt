package com.ehviewer.shared

import com.ehviewer.core.model.BaseGalleryInfo

/** Reorders only the loaded page. Service cursors and the original model/list stay unchanged. */
internal enum class GalleryPageOrder {
    SERVICE,
    TITLE,
    RATING,
    PAGES,
    ;

    fun arrange(items: List<BaseGalleryInfo>): List<BaseGalleryInfo> = when (this) {
        SERVICE -> items
        TITLE -> items.sortedWith(compareBy<BaseGalleryInfo> { title(it).isEmpty() }.thenBy { title(it).lowercase() })
        RATING -> items.sortedWith(compareBy<BaseGalleryInfo> { !rated(it) }.thenByDescending { if (rated(it)) it.rating else 0f })
        PAGES -> items.sortedWith(compareBy<BaseGalleryInfo> { it.pages <= 0 }.thenByDescending { it.pages.coerceAtLeast(0) })
    }

    private fun title(item: BaseGalleryInfo) = (item.title?.takeIf { it.isNotBlank() } ?: item.titleJpn.orEmpty()).trim()
    private fun rated(item: BaseGalleryInfo) = item.rating.isFinite() && item.rating in 0f..5f
}
