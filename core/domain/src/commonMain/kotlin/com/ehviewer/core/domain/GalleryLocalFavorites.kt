package com.ehviewer.core.domain

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo

class GalleryLocalFavoritesException : Exception("Local favorites are unavailable")

/** Device collection, independent of the gallery's cloud slot or account. */
interface GalleryLocalFavorites {
    suspend fun contains(gid: Long): Boolean
    suspend fun page(offset: Int, limit: Int): List<BaseGalleryInfo>

    /** Atomically toggles membership and returns the new local state. */
    suspend fun toggle(gallery: GalleryInfo): Boolean
}
