package com.ehviewer.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

/** A single worker owns compressed data, codec and mutable pixels. Only immutable snapshots cross to UI. */
internal class SkiaGalleryFrames(bytes: ByteArray) : AutoCloseable {
    private val data = Data.makeFromBytes(bytes)
    private var codec: Codec? = null
    private var pixels: Bitmap? = null
    private var closed = false
    val count: Int
    val repetitions: Int

    init {
        try {
            val decoder = Codec.makeFromData(data).also { codec = it }
            count = decoder.frameCount
            require(count == galleryAnimationFrames(bytes) && count in 2..1000)
            galleryMediaCost(decoder.width, decoder.height, bytes.size)
            repetitions = decoder.repetitionCount
            require(repetitions >= -1)
            pixels = Bitmap()
            check(checkNotNull(pixels).allocPixels(decoder.imageInfo))
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun duration(index: Int): Int = checkNotNull(codec).getFrameInfo(index).duration

    fun frame(index: Int): Image {
        check(!closed && index in 0 until count)
        val bitmap = checkNotNull(pixels)
        // No prior-frame assumption: Skia reconstructs disposal, alpha and dependent frames correctly.
        checkNotNull(codec).readPixels(bitmap, index)
        return Image.makeFromBitmap(bitmap)
    }

    override fun close() {
        if (closed) return
        closed = true
        pixels?.close()
        pixels = null
        codec?.close()
        codec = null
        data.close()
    }
}

/** Called only by explicit packaged-product validation modes on each actual native runtime. */
fun verifyAnimatedGalleryProduct(): Int {
    val owned = listOf(
        "R0lGODlhAgABAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQADAAAACwAAAAAAgABAAACAgQKACH5BAASAAAALAAAAAACAAEAgAAA/wAAAAICBAoAOw==",
        "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAAAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAAEAAAAAAHgAAAJWUDhMDwAAAC8BAAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAAAALQAAABWUDhMDwAAAC8BAAAABxDR//4HIqL/AQA=",
    )
    for (encoded in owned) {
        SkiaGalleryFrames(Base64.decode(encoded)).use { frames ->
            check(frames.count == 2 && frames.repetitions == -1 && frames.duration(0) == 120 && frames.duration(1) == 180)
            val first = frames.frame(0)
            try {
                val second = frames.frame(1)
                try {
                    // Both snapshots must remain independently readable after codec disposal.
                    frames.close()
                    check(first.width == 2 && first.height == 1 && second.width == 2 && second.height == 1)
                    val red = first.toComposeImageBitmap().toPixelMap()[0, 0]
                    val blue = second.toComposeImageBitmap().toPixelMap()[0, 0]
                    check(red.red > 0.99f && red.blue < 0.01f && blue.blue > 0.99f && blue.red < 0.01f)
                } finally {
                    second.close()
                }
            } finally {
                first.close()
            }
        }
    }
    return owned.size
}

private class GalleryAnimationPainter(private val poster: BitmapPainter) : Painter(), AutoCloseable {
    private var frame by mutableStateOf<Image?>(null)
    override val intrinsicSize: Size get() = poster.intrinsicSize
    override fun DrawScope.onDraw() {
        val image = frame
        if (image == null) with(poster) { draw(size) } else drawIntoCanvas { it.skiaCanvas.drawImageRect(image, Rect.makeWH(size.width, size.height)) }
    }
    fun replace(image: Image?) {
        val previous = frame
        frame = image
        previous?.close()
    }
    override fun close() = replace(null)
}

@Composable
internal actual fun rememberGalleryMediaPainter(media: GalleryMedia, playing: Boolean, attempt: Int, failed: () -> Unit): Painter {
    val poster = remember(media) { BitmapPainter(media.poster) }
    val bytes = media.animation ?: return poster
    val painter = remember(media, playing, attempt) { GalleryAnimationPainter(poster) }
    LaunchedEffect(media, playing, attempt) {
        if (!playing) return@LaunchedEffect
        try {
            withContext(Dispatchers.Default) {
                SkiaGalleryFrames(bytes).use { frames ->
                    playGalleryFrames(frames.count, frames.repetitions, frames::duration) { index ->
                        val next = frames.frame(index)
                        var adopted = false
                        try {
                            withContext(Dispatchers.Main.immediate) {
                                painter.replace(next)
                                adopted = true
                            }
                        } finally {
                            if (!adopted) next.close()
                        }
                    }
                }
            }
            // A finite animation keeps its final frame until this page leaves the foreground.
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed()
        } finally {
            painter.close()
        }
    }
    return if (playing) painter else poster
}
