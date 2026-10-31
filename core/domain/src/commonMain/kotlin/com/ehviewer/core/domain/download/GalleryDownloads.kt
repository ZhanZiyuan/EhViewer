package com.ehviewer.core.domain.download

import kotlinx.serialization.Serializable

@Serializable
enum class GalleryDownloadState { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETE }

@Serializable
enum class GalleryDownloadFailure { NETWORK_OR_IMAGE, STORAGE }

/** Stable gallery identity and committed page count; never contains account credentials. */
@Serializable
data class GalleryDownload(
    val gid: Long,
    val token: String,
    val title: String,
    val pages: Int,
    val restricted: Boolean,
    val state: GalleryDownloadState = GalleryDownloadState.QUEUED,
    val finished: Int = 0,
    val failure: GalleryDownloadFailure? = null,
    val position: Long = 0,
) {
    fun validate() {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token))
        require(pages in 0..100_000 && finished in 0..pages && title.length <= 4096)
        require(state != GalleryDownloadState.COMPLETE || finished == pages)
        require((state == GalleryDownloadState.FAILED) == (failure != null))
        require(position >= 0)
    }
}

@Serializable
data class DownloadedGalleryPage(val index: Int, val token: String, val bytes: Long)

class GalleryDownloadStorageException : Exception("Download storage is unavailable")

/** Implementations commit each image before publishing its page record and never replace corrupt data. */
interface GalleryDownloadStorage {
    suspend fun list(): List<GalleryDownload>
    suspend fun save(download: GalleryDownload)
    suspend fun pages(gid: Long): List<DownloadedGalleryPage>
    suspend fun writePage(gid: Long, index: Int, token: String, bytes: ByteArray)
    suspend fun readPage(gid: Long, index: Int): ByteArray
    suspend fun remove(gid: Long)
}
