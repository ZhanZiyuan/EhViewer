package com.ehviewer.shared

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GalleryCoverRequest(val gid: Long, val token: String, val key: String)

/** One download/decode at a time and a 16 MiB LRU, owned by the committed browse route. */
internal class GalleryCoverCache<T>(private val budget: Long = 16L * 1024 * 1024, private val load: suspend (GalleryCoverRequest) -> PreviewBitmap<T>) {
    private val mutex = Mutex()
    private val images = linkedMapOf<String, PreviewBitmap<T>>()

    init {
        require(budget > 0)
    }

    suspend fun get(request: GalleryCoverRequest): PreviewBitmap<T> = mutex.withLock {
        if (request.gid <= 0 || !Regex("[a-f0-9]{10}").matches(request.token)) throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
        val cached = images.remove(request.key)
        if (cached != null) {
            images[request.key] = cached
            return@withLock cached
        }
        val image = load(request)
        coroutineContext.ensureActive()
        try {
            checkedImageDimensions(image.width, image.height)
        } catch (_: IllegalArgumentException) {
            throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
        }
        val bytes = image.width.toLong() * image.height * 4
        if (bytes > budget) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
        while (images.values.sumOf { it.width.toLong() * it.height * 4 } + bytes > budget) images.remove(images.keys.first())
        images[request.key] = image
        image
    }

    suspend fun invalidate(key: String) = mutex.withLock {
        images.remove(key)
        Unit
    }
}
