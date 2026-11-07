package com.ehviewer.core.domain

enum class GalleryListMode { DETAIL, THUMBNAIL }

data class GalleryListPresentation(
    val mode: GalleryListMode = GalleryListMode.DETAIL,
    val detailThumbnailSize: Int = 40,
    val thumbnailColumns: Int = 3,
) {
    init {
        require(detailThumbnailSize in 20..60)
        require(thumbnailColumns in 1..10)
    }
}

interface GalleryListPresentationPreferences {
    suspend fun presentation(): GalleryListPresentation
    suspend fun setMode(mode: GalleryListMode)
    suspend fun setDetailThumbnailSize(size: Int)
    suspend fun setThumbnailColumns(columns: Int)
}
