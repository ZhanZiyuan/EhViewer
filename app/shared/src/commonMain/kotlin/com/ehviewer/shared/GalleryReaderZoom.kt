package com.ehviewer.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp
import kotlin.math.min

internal data class ReaderZoom(val scale: Float = 1f, val pan: Offset = Offset.Zero)

/** The same fitted-image bounds and centroid anchor for pinch, wheel and keyboard input. */
internal fun readerZoom(current: ReaderZoom, viewport: IntSize, image: IntSize, factor: Float, centroid: Offset = Offset.Unspecified, delta: Offset = Offset.Zero): ReaderZoom {
    if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0 || !factor.isFinite() || factor <= 0f || !delta.x.isFinite() || !delta.y.isFinite()) return current
    val scale = (current.scale * factor).coerceIn(1f, 5f)
    if (scale == 1f) return ReaderZoom()
    val anchor = if (centroid.isSpecified && centroid.x.isFinite() && centroid.y.isFinite()) centroid - Offset(viewport.width / 2f, viewport.height / 2f) else Offset.Zero
    val moved = (current.pan - anchor) * (scale / current.scale) + anchor + delta
    val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val maxX = ((image.width * fit * scale - viewport.width) / 2).coerceAtLeast(0f)
    val maxY = ((image.height * fit * scale - viewport.height) / 2).coerceAtLeast(0f)
    return ReaderZoom(scale, Offset(if (maxX == 0f) 0f else moved.x.coerceIn(-maxX, maxX), if (maxY == 0f) 0f else moved.y.coerceIn(-maxY, maxY)))
}

internal enum class ReaderZoomAction { IN, OUT, RESET }

/** Focused controls get their keys first; only conventional modified zoom shortcuts reach here. */
internal fun readerZoomAction(event: KeyEvent): ReaderZoomAction? {
    if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed) || event.isAltPressed) return null
    return when (event.key) {
        Key.Plus, Key.Equals, Key.NumPadAdd -> ReaderZoomAction.IN
        Key.Minus, Key.NumPadSubtract -> ReaderZoomAction.OUT
        Key.Zero, Key.NumPad0 -> ReaderZoomAction.RESET
        else -> null
    }
}

/** Add pointer-anchored Cmd-wheel; transformable retains native Ctrl-scroll and trackpad gestures. */
@Composable
internal fun Modifier.galleryReaderWheelZoom(enabled: Boolean, onZoom: (Float, Offset) -> Unit): Modifier {
    val zoom by rememberUpdatedState(onZoom)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                // Compose's native Ctrl-scroll path does not honor consumed changes. Give
                // it sole ownership rather than applying a second zoom in another pass.
                if (event.type != PointerEventType.Scroll || !event.keyboardModifiers.isMetaPressed || event.keyboardModifiers.isCtrlPressed || event.changes.any { it.isConsumed }) continue
                val change = event.changes.firstOrNull() ?: continue
                val scroll = change.scrollDelta.y
                if (!scroll.isFinite() || scroll == 0f) continue
                zoom(exp((-scroll * 0.12f).coerceIn(-1f, 1f)), change.position)
                event.changes.forEach { it.consume() }
            }
        }
    }
}
