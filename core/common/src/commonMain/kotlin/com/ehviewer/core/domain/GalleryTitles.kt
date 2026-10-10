package com.ehviewer.core.domain

/** Preserve the selected title, falling back only when it is null or empty. */
fun selectGalleryTitle(title: String?, japaneseTitle: String?, preferJapanese: Boolean): String {
    val preferred = if (preferJapanese) japaneseTitle else title
    val fallback = if (preferJapanese) title else japaneseTitle
    return (if (preferred.isNullOrEmpty()) fallback else preferred).orEmpty()
}
