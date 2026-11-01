package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_animation_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Retain compressed animation data, never every decoded frame. Static previews keep their existing decoder. */
internal class GalleryMedia(val poster: ImageBitmap, val animation: ByteArray?, val bytes: Long) {
    val width get() = poster.width
    val height get() = poster.height
}

internal suspend fun decodeGalleryMedia(bytes: ByteArray): GalleryMedia = withContext(Dispatchers.Default) {
    try {
        val animated = galleryAnimationFrames(bytes) > 1
        val poster = decodeGalleryImage(bytes)
        val cost = galleryMediaCost(poster.width, poster.height, if (animated) bytes.size else 0)
        GalleryMedia(poster, bytes.takeIf { animated }, cost)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: GalleryRequestException) {
        throw error
    } catch (_: Exception) {
        throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    }
}

internal fun galleryMediaCost(width: Int, height: Int, encoded: Int): Long {
    checkedImageDimensions(width, height)
    require(encoded in 0..32 * 1024 * 1024)
    // Poster, native canvas/disposal buffers, render handoff and compressed transport/native copies.
    val cost = width.toLong() * height * 4 * (if (encoded == 0) 1 else 6) + encoded.toLong() * 3
    require(cost <= 64L * 1024 * 1024) { "Animation exceeds the reader working-set limit" }
    return cost
}

internal suspend fun playGalleryFrames(count: Int, repetitions: Int, duration: (Int) -> Int, render: suspend (Int) -> Unit) {
    require(count in 2..1000 && repetitions >= -1)
    var iteration = 0L
    while (repetitions == -1 || iteration <= repetitions.toLong()) {
        for (index in 0 until count) {
            currentCoroutineContext().ensureActive()
            render(index)
            val millis = duration(index)
            require(millis >= 0)
            // Match browser-style handling of missing/very short delays without busy spinning.
            delay(if (millis < 20) 100L else millis.toLong())
        }
        iteration++
    }
}

@Composable
internal expect fun rememberGalleryMediaPainter(media: GalleryMedia, playing: Boolean, attempt: Int, failed: () -> Unit): Painter

@Composable
internal fun GalleryMediaImage(media: GalleryMedia, description: String, modifier: Modifier, visible: Boolean = true) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var attempt by remember(media) { mutableIntStateOf(0) }
    var failed by remember(media, attempt) { mutableStateOf(false) }
    val playing = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !failed
    val painter = rememberGalleryMediaPainter(media, playing, attempt) { failed = true }
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painter, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (failed) {
            TextButton(onClick = { attempt++ }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text(galleryString(Res.string.gallery_animation_failed))
            }
        }
    }
}
