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

internal const val COVER_MAX_EDGE = 512

internal fun coverDimensions(width: Int, height: Int): Pair<Int, Int> {
    checkedImageDimensions(width, height)
    val edge = maxOf(width, height)
    return if (edge <= COVER_MAX_EDGE) width to height else maxOf(1, (width.toLong() * COVER_MAX_EDGE / edge).toInt()) to maxOf(1, (height.toLong() * COVER_MAX_EDGE / edge).toInt())
}

internal expect fun decodePlatformCover(bytes: ByteArray): ImageBitmap

/** Publish at most 1 MiB of RGBA per cover, independently of the bounded full reader decoder. */
internal suspend fun decodeGalleryCover(bytes: ByteArray): ImageBitmap = withContext(Dispatchers.Default) {
    try {
        decodePlatformCover(bytes).also { check(it.width in 1..COVER_MAX_EDGE && it.height in 1..COVER_MAX_EDGE) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    }
}

internal suspend fun decodeGalleryImage(bytes: ByteArray): ImageBitmap = withContext(Dispatchers.Default) {
    try {
        decodePlatformImage(bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    }
}
