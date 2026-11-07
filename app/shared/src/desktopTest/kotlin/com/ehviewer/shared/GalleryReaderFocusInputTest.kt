package com.ehviewer.shared

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.reader.ReaderMode
import com.ehviewer.core.domain.reader.ReaderPreferences
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GalleryReaderFocusInputTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun dismissingControlsAfterModeSelectionReturnsKeyboardInputToTheCanvas() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val chrome = GalleryReaderChromeState()
        val focus = FocusRequester()
        var preferences by mutableStateOf(ReaderPreferences())
        var keys = 0
        val scene = ImageComposeScene(400, 720, Density(1f)) {
            GalleryTheme(false, null) {
                LaunchedEffect(Unit) { focus.requestFocus() }
                GalleryReaderChrome(
                    "Gallery", 0, 3, chrome, {}, {}, {},
                    modifier = Modifier.focusRequester(focus),
                    onKey = { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            keys++
                            true
                        } else {
                            false
                        }
                    },
                    actions = { GalleryReaderModeButton(preferences, true) { preferences = preferences.withMode(it) } },
                ) {
                    Box(Modifier.fillMaxSize().pointerInput(chrome) { detectTapGestures(onTap = { chrome.toggle() }) })
                }
            }
        }
        var frame = 0L
        fun render() = repeat(20) { scene.render(frame++ * 16_666_667L).close() }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point)
            render()
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        fun down() {
            scene.sendKeyEvent(KeyEvent(Key.DirectionDown, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.DirectionDown, KeyEventType.KeyUp))
            render()
        }
        try {
            render()
            down()
            assertEquals(1, keys, "The initial actual reader canvas must receive keyboard input")
            click(Offset(200f, 360f))
            click(all().single { "Reading mode" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.touchBoundsInRoot.center)
            click(all().single { it.config.contains(SemanticsProperties.Selected) && it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Vertical" } }.touchBoundsInRoot.center)
            assertEquals(ReaderMode.VERTICAL, preferences.selectedMode)
            click(Offset(200f, 360f))
            assertFalse(chrome.visible, "The real canvas tap must dismiss the controls")
            down()
            assertEquals(2, keys, "Closing the focused mode control must restore actual canvas keyboard input")
        } finally {
            scene.close()
            Locale.setDefault(before)
        }
    }
}
