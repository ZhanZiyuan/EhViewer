package com.ehviewer.core.domain.download

/** A limit on committed download image bytes, not filesystem free space or transient cache usage. */
data class GalleryStorageUsage(val imageBytes: Long, val limitMiB: Int?) {
    init {
        require(imageBytes >= 0)
        validateDownloadLimit(limitMiB)
    }
}

fun validateDownloadLimit(limitMiB: Int?) {
    require(limitMiB == null || limitMiB in 1..1_048_576)
}

interface GalleryStoragePreferences {
    suspend fun downloadLimitMiB(): Int?
    suspend fun setDownloadLimitMiB(limitMiB: Int?)
}

/** Changing a limit never removes downloads; an over-limit library remains readable. */
interface GalleryStorageSettings {
    suspend fun usage(): GalleryStorageUsage
    suspend fun setDownloadLimitMiB(limitMiB: Int?)
}
