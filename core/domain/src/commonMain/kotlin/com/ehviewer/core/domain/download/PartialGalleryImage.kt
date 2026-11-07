package com.ehviewer.core.domain.download

import kotlinx.serialization.Serializable

/** Persist fingerprints, never image URLs, cookies or authorization headers. */
@Serializable
data class GalleryImageIdentity(val pageToken: String, val sourceSha256: String, val targetSha256: String, val etag: String, val totalBytes: Long) {
    fun validate() {
        require(Regex("[a-f0-9]{10}").matches(pageToken))
        require(listOf(sourceSha256, targetSha256).all { Regex("[a-f0-9]{64}").matches(it) })
        require(isStrongImageEtag(etag) && totalBytes in 1..MAX_BYTES)
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}

fun isStrongImageEtag(value: String): Boolean = value.length in 2..512 && value.first() == '"' && value.last() == '"' && value.substring(1, value.lastIndex).all { it in '!'..'~' && it != '"' }

@Serializable
data class GalleryImageProgress(val identity: GalleryImageIdentity? = null, val bytes: Long = 0, val chunks: List<String> = emptyList(), val complete: Boolean = false) {
    fun validate() {
        identity?.validate()
        require(bytes in 0..(identity?.totalBytes ?: 0))
        require(chunks.size == ((bytes + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt())
        require(chunks.all { Regex("[a-f0-9]{64}").matches(it) })
        require(!complete || identity != null && bytes == identity.totalBytes)
    }

    companion object {
        const val CHUNK_BYTES = 1024 * 1024
    }
}

/** One admitted page owner. Appends are durably checkpointed before returning. */
interface GalleryImageCheckpoint {
    suspend fun progress(): GalleryImageProgress
    suspend fun reset(identity: GalleryImageIdentity?)
    suspend fun append(bytes: ByteArray)
    suspend fun finish()
    suspend fun readComplete(): ByteArray
    suspend fun discard()
}

/** Optional capability: implementations without this contract retain full-image downloads. */
interface PartialGalleryDownloadStorage {
    suspend fun <T> withPartialPage(gid: Long, index: Int, action: suspend (GalleryImageCheckpoint) -> T): T
}
