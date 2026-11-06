package com.ehviewer.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

class GalleryReaderZoomInputTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun modifiedWheelZoomsActualPixelsAroundThePointerAndOrdinaryScrollingIsUnchanged() {
        var current by mutableStateOf(ReaderZoom())
        var enabled by mutableStateOf(true)
        var unconsumed = 0
        var commandWheelCalls = 0
        var nativeGestureCalls = 0
        val viewport = IntSize(200, 200)
        val image = IntSize(400, 200)
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            GalleryReaderPagedCanvas(0, 2, false, enabled && current.scale == 1f, { error("Wheel input must not change pages") }) { page ->
                if (page != 0) {
                    Box(Modifier.fillMaxSize())
                    return@GalleryReaderPagedCanvas
                }
                Box(
                    Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.type == PointerEventType.Scroll && event.changes.none { it.isConsumed }) unconsumed++
                            }
                        }
                    }.galleryReaderWheelZoom(enabled) { factor, position ->
                        commandWheelCalls++
                        current = readerZoom(current, viewport, image, factor, position)
                    }.transformable(
                        rememberTransformableState { centroid, scale, offset, _ ->
                            nativeGestureCalls++
                            current = readerZoom(current, viewport, image, scale, centroid, offset)
                        },
                        canPan = { current.scale > 1f },
                        enabled = enabled,
                    ),
                ) {
                    Box(
                        Modifier.align(Alignment.Center).size(200.dp, 100.dp).graphicsLayer {
                            scaleX = current.scale
                            scaleY = current.scale
                            translationX = current.pan.x
                            translationY = current.pan.y
                        }.background(Color.Red),
                    )
                }
            }
        }
        var frame = 0L
        fun render() {
            scene.render(frame++ * 16_666_667L).close()
        }
        fun pixel(): Int = Bitmap().use { pixels ->
            pixels.allocN32Pixels(200, 200)
            scene.render(frame++ * 16_666_667L).use { it.readPixels(pixels) }
            pixels.getColor(100, 20)
        }
        fun wheel(delta: Float, modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()) {
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(150f, 100f), scrollDelta = Offset(0f, delta), keyboardModifiers = modifiers)
            repeat(4) { render() }
        }
        try {
            repeat(5) { render() }
            assertEquals(0xff000000.toInt(), pixel())
            wheel(-6f)
            assertEquals(ReaderZoom(), current, "Ordinary wheel input must not change image zoom")
            assertEquals(1, unconsumed, "Ordinary scrolling must reach ancestor handlers")
            wheel(-6f, PointerKeyboardModifiers(isMetaPressed = true))
            assertEquals(1, unconsumed, "A handled zoom must not also scroll an ancestor")
            assertEquals(1, commandWheelCalls)
            assertEquals(0, nativeGestureCalls, "Cmd-wheel must not also trigger the native Ctrl-scroll recognizer")
            assertTrue(current.scale > 2f && current.pan.x < 0f)
            assertEquals(exp(0.72f), current.scale, 0.0001f, "The composed touch/wheel chain must apply exactly one pointer-anchored zoom")
            assertEquals(50f * (1f - current.scale), current.pan.x, 0.0001f)
            assertEquals(0xffff0000.toInt(), pixel(), "The actual fitted image must grow into the previous letterbox")
            wheel(6f, PointerKeyboardModifiers(isMetaPressed = true))
            assertTrue(current.scale in 1f..1.0001f)
            assertEquals(0xff000000.toInt(), pixel())
            wheel(-6f, PointerKeyboardModifiers(isCtrlPressed = true))
            assertEquals(2, commandWheelCalls, "Native Ctrl-scroll must not also execute the Cmd-wheel handler")
            assertTrue(nativeGestureCalls > 0 && current.scale > 1f, "The existing native Ctrl-scroll path must remain functional")
            enabled = false
            repeat(3) { render() }
            val before = current
            wheel(-6f, PointerKeyboardModifiers(isCtrlPressed = true))
            assertEquals(before, current, "Loading/seeking must disable wheel transforms")
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun focusedCanvasAcceptsCtrlAndCommandZoomShortcutsOnlyOnKeyDown() {
        val focus = FocusRequester()
        val actions = mutableListOf<ReaderZoomAction>()
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            Box(
                Modifier.fillMaxSize().focusRequester(focus).onKeyEvent { event ->
                    readerZoomAction(event)?.let {
                        actions += it
                        true
                    } ?: false
                }.focusable(),
            )
        }
        var frame = 0L
        fun render() {
            scene.render(frame++ * 16_666_667L).close()
        }
        fun press(key: Key, ctrl: Boolean = false, meta: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isMetaPressed = meta))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isMetaPressed = meta))
            render()
        }
        try {
            repeat(5) { render() }
            focus.requestFocus()
            render()
            press(Key.Equals)
            press(Key.DirectionRight, ctrl = true)
            assertTrue(actions.isEmpty(), "Unmodified keys and existing navigation must remain outside zoom handling")
            press(Key.Equals, ctrl = true)
            press(Key.Minus, meta = true)
            press(Key.NumPadAdd, meta = true)
            press(Key.NumPadSubtract, ctrl = true)
            press(Key.Zero, ctrl = true)
            press(Key.NumPad0, meta = true)
            assertEquals(listOf(ReaderZoomAction.IN, ReaderZoomAction.OUT, ReaderZoomAction.IN, ReaderZoomAction.OUT, ReaderZoomAction.RESET, ReaderZoomAction.RESET), actions)
        } finally {
            scene.close()
        }
    }
}
