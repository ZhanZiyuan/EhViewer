package com.ehviewer.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class GalleryReaderSwipeInputTest {
    private class ReaderInput : AutoCloseable {
        var page by mutableIntStateOf(1)
        var enabled by mutableStateOf(true)
        var rtl by mutableStateOf(false)
        var uiRtl by mutableStateOf(false)
        var zoom by mutableStateOf(ReaderZoom())
        var accept by mutableStateOf(true)
        var menuTaps = 0
        val requests = mutableListOf<Int>()
        private var frame = 0L
        private val viewport = IntSize(400, 400)
        private val image = IntSize(400, 200)
        private val scene = ImageComposeScene(400, 400, Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides if (uiRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                GalleryReaderPagedCanvas(page, 3, rtl, enabled && zoom.scale == 1f, { target ->
                    requests += target
                    if (accept) page = target
                    accept
                }) { slot ->
                    if (slot != page) {
                        Box(Modifier.fillMaxSize())
                        return@GalleryReaderPagedCanvas
                    }
                    Box(
                        Modifier.fillMaxSize().galleryReaderWheelZoom(enabled) { factor, anchor -> zoom = readerZoom(zoom, viewport, image, factor, anchor) }
                            .pointerInput(Unit) { detectTapGestures(onTap = { menuTaps++ }, onDoubleTap = { zoom = if (zoom.scale > 1f) ReaderZoom() else ReaderZoom(2f) }) }
                            .transformable(rememberTransformableState { centroid, scale, offset, _ -> zoom = readerZoom(zoom, viewport, image, scale, centroid, offset) }, canPan = { zoom.scale > 1f }, enabled = enabled),
                    ) {
                        Canvas(
                            Modifier.align(Alignment.Center).size(400.dp, 200.dp).graphicsLayer {
                                scaleX = zoom.scale
                                scaleY = zoom.scale
                                translationX = zoom.pan.x
                                translationY = zoom.pan.y
                            },
                        ) {
                            drawRect(Color.Red, size = Size(size.width / 2, size.height))
                            drawRect(Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                        }
                    }
                }
            }
        }

        fun render(times: Int = 5) {
            repeat(times) { scene.render(frame++ * 16_666_667L).close() }
        }

        fun drag(from: Offset, to: Offset, type: PointerType = PointerType.Touch, interrupt: () -> Unit = {}) {
            scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = frame * 16, type = type)
            render(1)
            val originalPage = page
            for (step in 1..20) {
                if (step == 10) interrupt()
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 20f), timeMillis = frame * 16, type = type)
                render(1)
                assertEquals(originalPage, page, "Dragging must not save or change the page before release")
            }
            scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = frame * 16, type = type)
            render(80)
        }

        fun centerPixel(): Int = Bitmap().use { pixels ->
            pixels.allocN32Pixels(400, 400)
            scene.render(frame++ * 16_666_667L).use { it.readPixels(pixels) }
            pixels.getColor(200, 200)
        }

        override fun close() = scene.close()
    }

    @Test fun touchDragsRespectOriginalPagerDirectionBoundsShortCancellationAndDisabledInput() {
        for (uiRtl in listOf(false, true)) {
            ReaderInput().use { reader ->
                reader.uiRtl = uiRtl
                reader.render()
                val left = Offset(80f, 200f)
                val right = Offset(320f, 200f)
                reader.drag(right, left)
                assertEquals(2, reader.page)
                reader.drag(right, left)
                assertEquals(2, reader.page, "The last page must not invent another target")
                reader.drag(left, right)
                assertEquals(1, reader.page)
                reader.drag(Offset(200f, 200f), Offset(175f, 200f))
                assertEquals(1, reader.page, "A short drag must cancel")
                reader.rtl = true
                reader.render()
                reader.drag(right, left)
                assertEquals(0, reader.page)
                reader.drag(right, left)
                assertEquals(0, reader.page, "The first page must not invent a previous target")
                reader.drag(left, right)
                assertEquals(1, reader.page)
                assertEquals(0, reader.menuTaps, "Page gestures must not also toggle the menu")
                reader.enabled = false
                reader.render()
                reader.drag(right, left)
                assertEquals(1, reader.page, "Loading/seeking must disable page gestures")
            }
        }
    }

    @Test fun zoomedDraggingPansActualPixelsAndNeverFlipsPages() {
        ReaderInput().use { reader ->
            reader.zoom = ReaderZoom(2f)
            reader.render()
            assertEquals(0xff0000ff.toInt(), reader.centerPixel())
            reader.drag(Offset(100f, 200f), Offset(300f, 200f), type = PointerType.Mouse)
            assertEquals(1, reader.page)
            assertEquals(2f, reader.zoom.scale)
            assertTrue(reader.zoom.pan.x > 0f, "The real transform recognizer must retain one-finger panning")
            assertEquals(0xffff0000.toInt(), reader.centerPixel(), "Panning must move actual rendered content")
            assertEquals(0, reader.menuTaps, "A zoomed pan must not also act as a tap")
            reader.zoom = ReaderZoom()
            reader.render()
            reader.drag(Offset(320f, 200f), Offset(80f, 200f))
            assertEquals(2, reader.page, "Returning to fit must restore page swiping")
        }
    }

    @Test fun missingSourceLinksAndMidGestureDisablingRestoreTheActualCurrentPage() {
        ReaderInput().use { reader ->
            reader.accept = false
            reader.render()
            reader.drag(Offset(320f, 200f), Offset(80f, 200f))
            assertEquals(listOf(2), reader.requests)
            assertEquals(1, reader.page)
            assertEquals(0xff0000ff.toInt(), reader.centerPixel(), "A rejected source must restore the actual decoded page")
            reader.requests.clear()
            reader.accept = true
            reader.drag(Offset(320f, 200f), Offset(80f, 200f)) { reader.enabled = false }
            assertTrue(reader.requests.isEmpty(), "Disabling input must cancel pending page navigation")
            assertEquals(1, reader.page)
            assertEquals(0xff0000ff.toInt(), reader.centerPixel())
        }
    }
}
