package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import io.ktor.http.Url

/** Network and actual local page sources use the same reader contract. */
interface GalleryContent {
    suspend fun details(gid: Long, token: String, previewPage: Int = 0): GalleryDetail
    fun pageUrl(gid: Long, pageToken: String, index: Int): Url
    fun pageIndex(url: String, gid: Long): Int
    suspend fun page(url: String, gid: Long, reloadKey: String? = null): GalleryImagePage
    suspend fun image(page: GalleryImagePage, pageUrl: String, gid: Long): ByteArray
    suspend fun preview(preview: GalleryPreview, gid: Long, token: String): ByteArray
}
