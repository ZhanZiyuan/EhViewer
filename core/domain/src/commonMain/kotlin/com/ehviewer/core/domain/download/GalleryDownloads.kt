package com.ehviewer.core.domain.download

import com.ehviewer.core.model.GalleryInfo
import kotlinx.serialization.Serializable

/** Public card information only: no favorite notes, session cookies or account API credentials. */
@Serializable
data class GalleryDownloadMetadata(val uploader: String?, val disowned: Boolean, val rating: Float?, val category: Int) {
    fun validate() {
        require(uploader == null || uploader.length <= 4096)
        require(rating == null || rating.isFinite() && rating in 0f..5f)
    }
}

fun GalleryInfo.downloadMetadata(): GalleryDownloadMetadata? {
    val rating = rating.takeIf { it.isFinite() && it in 0f..5f }
    if (uploader.isNullOrEmpty() && !disowned && (rating == null || rating == 0f) && category == 0) return null
    return GalleryDownloadMetadata(uploader?.take(4096), disowned, rating, category)
}

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
    val metadata: GalleryDownloadMetadata? = null,
) {
    fun validate() {
        require(gid > 0 && Regex("[a-f0-9]{10}").matches(token))
        require(pages in 0..100_000 && finished in 0..pages && title.length <= 4096)
        require(state != GalleryDownloadState.COMPLETE || finished == pages)
        require((state == GalleryDownloadState.FAILED) == (failure != null))
        require(position >= 0)
        metadata?.validate()
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
