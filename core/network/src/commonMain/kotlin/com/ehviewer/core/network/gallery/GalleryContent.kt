package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import io.ktor.http.Url

/** Network and actual local page sources use the same reader contract. */
interface GalleryContent {
    suspend fun details(gid: Long, token: String, previewPage: Int = 0): GalleryDetail

    /** Online comments use the service's explicit all-comments request; local books have none. */
    suspend fun comments(gid: Long, token: String): GalleryCommentList = invalid()
    suspend fun commentImage(url: String, gid: Long, token: String): ByteArray = invalid()

    /** Local books have no service torrent list. The online repository uses the original endpoint. */
    suspend fun torrents(gid: Long, token: String): List<GalleryTorrent> = invalid()
    fun pageUrl(gid: Long, pageToken: String, index: Int): Url
    fun pageIndex(url: String, gid: Long): Int
    suspend fun page(url: String, gid: Long, reloadKey: String? = null): GalleryImagePage
    suspend fun image(page: GalleryImagePage, pageUrl: String, gid: Long): ByteArray

    /** Local sources have no remote original; only validated online metadata advertises this action. */
    suspend fun originalImage(page: GalleryImagePage, pageUrl: String, gid: Long): ByteArray = invalid()
    suspend fun preview(preview: GalleryPreview, gid: Long, token: String): ByteArray
}
