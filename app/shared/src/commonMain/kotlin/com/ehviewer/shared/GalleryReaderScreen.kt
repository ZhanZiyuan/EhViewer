package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.math.min
import kotlinx.coroutines.CancellationException

/** A bounded, cancellable online page reader shared by touch and desktop applications. */
@Composable
internal fun GalleryReaderScreen(session: GallerySession, detail: GalleryDetail, initial: String, onBack: () -> Unit) {
    var url by remember(initial) { mutableStateOf(initial) }
    var reloadKey by remember(url) { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<GalleryImagePage?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }
    val index = session.content.pageIndex(url, detail.gid)
    fun target(link: String?, delta: Int): String? = link?.takeIf { session.content.pageIndex(it, detail.gid) == index + delta && index + delta in 0 until detail.pages }
    val previous = target(document?.previousPageUrl, -1)
    val next = target(document?.nextPageUrl, 1)
    LaunchedEffect(url, attempt) {
        loading = true
        document = null
        bitmap = null
        failure = null
        zoom = 1f
        pan = Offset.Zero
        try {
            val page = session.content.page(url, detail.gid, reloadKey)
            document = page
            bitmap = decodeGalleryImage(session.content.image(page, url, detail.gid))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Escape -> onBack()
                Key.DirectionLeft, Key.PageUp -> previous?.let { url = it } ?: return@onPreviewKeyEvent false
                Key.DirectionRight, Key.PageDown -> next?.let { url = it } ?: return@onPreviewKeyEvent false
                else -> return@onPreviewKeyEvent false
            }
            true
        }.focusable(),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("${index + 1} / ${detail.pages}")
            TextButton(onClick = {
                zoom = 1f
                pan = Offset.Zero
            }) { Text("Reset zoom") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let {
            GalleryError(it) {
                reloadKey = document?.skipHathKey
                attempt++
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }
                .pointerInput(url) {
                    detectTapGestures(onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else 2f
                        pan = Offset.Zero
                    })
                }
                .transformable(
                    rememberTransformableState { centroid, scale, offset, _ ->
                        val oldZoom = zoom
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        val anchor = if (centroid.isSpecified) centroid - Offset(viewport.width / 2f, viewport.height / 2f) else Offset.Zero
                        val moved = (pan - anchor) * (zoom / oldZoom) + anchor + offset
                        val image = bitmap
                        pan = if (zoom == 1f || image == null) {
                            Offset.Zero
                        } else {
                            val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
                            val maxX = ((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
                            val maxY = ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
                            Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { image ->
                Image(
                    image,
                    "Gallery page ${index + 1}",
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { previous?.let { url = it } }, enabled = previous != null) { Text("Previous page") }
            TextButton(onClick = { next?.let { url = it } }, enabled = next != null) { Text("Next page") }
        }
    }
}
