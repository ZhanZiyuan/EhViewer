package com.ehviewer.core.domain

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo

class GalleryHistoryException : Exception("Gallery history could not be loaded or saved")

interface GalleryHistoryStore {
    suspend fun recent(limit: Int = 100): List<BaseGalleryInfo>
    suspend fun record(gallery: GalleryInfo)
    suspend fun forget(gid: Long)
}
