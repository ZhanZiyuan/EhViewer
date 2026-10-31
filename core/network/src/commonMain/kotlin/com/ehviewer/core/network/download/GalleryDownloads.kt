package com.ehviewer.core.network.download

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.model.GalleryDetail
import kotlinx.coroutines.flow.StateFlow

/** Shared product operations; Android can retain its original task manager behind this boundary. */
interface GalleryDownloads : AutoCloseable {
    val tasks: StateFlow<List<GalleryDownload>>
    suspend fun initialize()
    suspend fun enqueue(gallery: GalleryDetail, restricted: Boolean)
    suspend fun pause(gid: Long)
    suspend fun resume(gid: Long)
    suspend fun cancel(gid: Long)
    suspend fun pauseAll()
    suspend fun open(gid: Long): GalleryDownloadOpen
}

interface CachedGalleryPages {
    val gallery: GalleryDownload
    suspend fun read(index: Int): ByteArray
}

sealed interface GalleryDownloadOpen {
    data class Cached(val pages: CachedGalleryPages) : GalleryDownloadOpen

    /** The existing platform reader preserves its archive/SAF support and original persisted state. */
    data class PlatformReader(val gid: Long) : GalleryDownloadOpen
}
