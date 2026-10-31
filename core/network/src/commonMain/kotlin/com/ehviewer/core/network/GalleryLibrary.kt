package com.ehviewer.core.network

import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.gallery.GalleryContent
import com.ehviewer.core.network.gallery.GalleryRepository

/** Shared product access; platforms retain ownership of their existing HTTP/session and storage. */
interface GalleryLibrary {
    val restricted: Boolean
    val galleries: GalleryRepository
    val content: GalleryContent

    /** Android registers the real returned metadata in its existing history before reading. */
    suspend fun details(gid: Long, token: String): GalleryDetail = content.details(gid, token)
}
