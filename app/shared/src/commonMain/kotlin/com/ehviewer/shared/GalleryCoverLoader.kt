package com.ehviewer.shared

import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.serviceLoaderEnabled
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okio.Buffer

/** Session-owned Coil pipeline. The original Ktor repository is the only network authority. */
internal class GalleryCoverLoader(private val context: PlatformContext, load: suspend (GalleryCoverRequest) -> ByteArray) {
    private val lifetime = Job()
    private val loader = ImageLoader.Builder(context)
        // The page LRU owns the existing16 MiB budget. Never create a second cache or a disk copy.
        .memoryCache(null)
        .diskCache(null)
        .serviceLoaderEnabled(false)
        .components {
            add(
                Fetcher.Factory<GalleryCoverRequest> { data, options, _ ->
                    Fetcher {
                        val bytes = load(data)
                        if (bytes.isEmpty()) throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
                        if (bytes.size > 32 * 1024 * 1024) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                        SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), COVER_SOURCE, DataSource.NETWORK)
                    }
                },
            )
            add(
                Decoder.Factory { result, _, _ ->
                    if (result.mimeType != COVER_SOURCE) {
                        null
                    } else {
                        Decoder {
                            val decoded = decodeGalleryCoverResult(result.source.source().readByteArray())
                            DecodeResult(decoded.bitmap.asCoilCover(), decoded.sampled)
                        }
                    }
                },
            )
        }
        .build()

    suspend fun get(request: GalleryCoverRequest): PreviewBitmap<Image> = coroutineScope {
        lifetime.ensureActive()
        // Coil shutdown cancels enqueued work, but execute belongs to its caller. Keep both owners.
        val work = async(start = CoroutineStart.LAZY) {
            loader.execute(ImageRequest.Builder(context).data(request).size(COVER_MAX_EDGE).build())
        }
        val registration = lifetime.invokeOnCompletion { work.cancel() }
        val result = try {
            work.await().also { lifetime.ensureActive() }
        } finally {
            registration.dispose()
        }
        when (result) {
            is SuccessResult -> PreviewBitmap(result.image, result.image.width, result.image.height)
            is ErrorResult -> {
                val error = result.throwable
                if (error is CancellationException) throw error
                throw error as? GalleryRequestException ?: GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
            }
        }
    }

    fun close() {
        lifetime.cancel()
        loader.shutdown()
    }

    private companion object {
        const val COVER_SOURCE = "application/x-ehviewer-bounded-cover"
    }
}
