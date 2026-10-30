package com.ehviewer.shared

import androidx.compose.ui.graphics.ImageBitmap
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Check decoded dimensions before allocating pixels, including overflow and decompression bombs. */
internal fun checkedImageDimensions(width: Int, height: Int) {
    require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 16 * 1024 * 1024) { "Image dimensions exceed the reader limit" }
}

internal expect fun decodePlatformImage(bytes: ByteArray): ImageBitmap

internal suspend fun decodeGalleryImage(bytes: ByteArray): ImageBitmap = withContext(Dispatchers.Default) {
    try {
        decodePlatformImage(bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    }
}
