package com.ehviewer.shared

import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PreviewBitmap<T>(val value: T, val width: Int, val height: Int)
internal data class PreviewRegion(val x: Int, val width: Int, val height: Int)

/** Sprite coordinates are untrusted parser output, checked before constructing a platform painter. */
internal fun previewRegion(preview: GalleryPreview, width: Int, height: Int): PreviewRegion {
    fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    if (width <= 0 || height <= 0) invalid()
    if (preview !is V2GalleryPreview) return PreviewRegion(0, width, height)
    // The site's sprite tiles include a one-pixel separator, as in the original Android renderer.
    if (preview.offsetX < 0 || preview.clipWidth <= 1 || preview.clipHeight <= 1) invalid()
    val region = PreviewRegion(preview.offsetX, preview.clipWidth - 1, preview.clipHeight - 1)
    if (region.x.toLong() + region.width > width || region.height > height) invalid()
    return region
}

/** One active download/decode; a bounded LRU shares sprite sheets across visible preview cells. */
internal class GalleryPreviewCache<T>(
    private val budget: Long = 16L * 1024 * 1024,
    private val load: suspend (GalleryPreview) -> PreviewBitmap<T>,
) {
    private val mutex = Mutex()
    private val images = linkedMapOf<String, PreviewBitmap<T>>()

    init {
        require(budget > 0)
    }

    suspend fun get(preview: GalleryPreview): PreviewBitmap<T> = mutex.withLock {
        val cached = images[preview.url]
        if (cached != null) {
            previewRegion(preview, cached.width, cached.height)
            images.remove(preview.url)
            images[preview.url] = cached
            return@withLock cached
        }
        val bitmap = load(preview)
        coroutineContext.ensureActive()
        try {
            checkedImageDimensions(bitmap.width, bitmap.height)
        } catch (_: IllegalArgumentException) {
            throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
        }
        val bytes = bitmap.width.toLong() * bitmap.height * 4
        if (bytes > budget) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
        previewRegion(preview, bitmap.width, bitmap.height)
        while (images.values.sumOf { it.width.toLong() * it.height * 4 } + bytes > budget) images.remove(images.keys.first())
        images[preview.url] = bitmap
        bitmap
    }
}
