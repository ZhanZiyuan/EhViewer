package com.ehviewer.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.reader.ReaderMode
import com.ehviewer.core.domain.reader.ReaderPreferences
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryReaderModeMenuTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun originalMenuFitsLargeFontsAndReflectsOnlyCommittedChoices() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for ((width, font) in listOf(360 to 1f, 360 to 1.8f, 1040 to 1f)) {
                var preferences by mutableStateOf(ReaderPreferences().withMode(ReaderMode.VERTICAL))
                var enabled by mutableStateOf(true)
                val requested = mutableListOf<ReaderMode>()
                val scene = ImageComposeScene(width, 720, Density(1f, font)) {
                    GalleryTheme(false, null) {
                        GalleryReaderModeButton(preferences, enabled) { requested += it }
                    }
                }
                var frame = 0L
                fun render() = repeat(20) { scene.render(frame++ * 16_666_667L).close() }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                fun choices() = all().filter { it.config.contains(SemanticsProperties.Selected) }
                fun click(node: SemanticsNode) {
                    val point = node.touchBoundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, point)
                    render()
                    scene.sendPointerEvent(PointerEventType.Release, point)
                    render()
                }
                fun open() = click(all().single { "Reading mode" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
                try {
                    render()
                    open()
                    val items = choices().sortedBy { it.boundsInRoot.top }
                    assertEquals(listOf("Default", "Left to right", "Right to left", "Vertical", "Webtoon", "Continuous vertical"), items.map(::text))
                    assertEquals("Vertical", text(items.single { it.config[SemanticsProperties.Selected] }))
                    items.forEach { assertTrue(it.boundsInRoot.width >= 192 && it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= width && it.boundsInRoot.bottom <= 720) }
                    items.zipWithNext().forEach { (a, b) -> assertTrue(a.boundsInRoot.bottom <= b.boundsInRoot.top, "Mode choices must remain separate at large font sizes") }
                    click(items.last())
                    assertEquals(listOf(ReaderMode.CONTINUOUS_VERTICAL), requested)
                    assertTrue(choices().isEmpty(), "Choosing a mode must dismiss the actual menu")
                    open()
                    assertEquals("Vertical", text(choices().single { it.config[SemanticsProperties.Selected] }), "A pending or failed save cannot invent a changed selection")
                    click(choices().single { it.config[SemanticsProperties.Selected] })
                    assertEquals(1, requested.size, "Selecting the current mode must not rewrite preferences")
                    preferences = preferences.withMode(requested.single())
                    render()
                    open()
                    assertEquals("Continuous vertical", text(choices().single { it.config[SemanticsProperties.Selected] }))
                    click(choices().single { it.config[SemanticsProperties.Selected] })
                    enabled = false
                    render()
                    open()
                    assertTrue(choices().isEmpty(), "Loading or saving must disable the actual mode menu")
                    assertEquals(1, requested.size)
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(before)
        }
    }
}
