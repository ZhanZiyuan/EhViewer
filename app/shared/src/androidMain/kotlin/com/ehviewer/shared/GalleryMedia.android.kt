package com.ehviewer.shared

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import java.nio.Buffer
import java.nio.ByteBuffer

@Composable
internal actual fun rememberGalleryMediaPainter(media: GalleryMedia, playing: Boolean, attempt: Int, failed: () -> Unit): Painter {
    val poster = remember(media) { media.posterPainter() }
    val bytes = media.animation ?: return poster
    if (!playing) return poster
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val lease = remember(media, attempt) { GalleryAnimationLease { owner -> main.post { if (!owner.isClosed) failed() } } }
    DisposableEffect(lease) { onDispose { lease.close() } }
    val request = remember(media, context, attempt, lease) {
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            (this as Buffer).flip()
        }
        ImageRequest.Builder(context).data(buffer).size(Size.ORIGINAL)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
            .apply { extras[galleryAnimationLeaseKey] = lease }.build()
    }
    // The application factory retains its existing GIF and Rust WebP decoders; this request performs no HTTP.
    return rememberAsyncImagePainter(request, imageLoader = SingletonImageLoader.get(context), placeholder = poster, error = poster, onError = { failed() })
}
