package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GallerySettingsLayoutTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun settingsAppBarFillsItsActualViewportAndItsBackActionWorksAtLargeFonts() {
        for (width in listOf(320, 411, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                var page = Rect.Zero
                var bar = Rect.Zero
                var backLabel = ""
                var backs = 0
                val scene = ImageComposeScene(width, 720, Density(1f, font)) {
                    MaterialTheme {
                        backLabel = galleryString(Res.string.gallery_back)
                        GalleryNavigationShell(GalleryDestination.entries, GalleryDestination.SETTINGS, {}) {
                            Column(galleryPageModifier(false, false, edgeToEdge = true).onGloballyPositioned { page = it.boundsInRoot() }) {
                                Box(Modifier.onGloballyPositioned { bar = it.boundsInRoot() }) { GallerySettingsHeader { backs++ } }
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() {
                    scene.render(frame).close()
                    frame += 16_666_667
                }
                try {
                    repeat(10) { render() }
                    assertEquals(page.left, bar.left, "App bar must not inherit the content's extra side keyline")
                    assertEquals(page.top, bar.top, "Safe drawing insets are applied by the host exactly once")
                    assertEquals(page.width, bar.width)
                    assertTrue(bar.height >= 64f)
                    val back = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.first { backLabel in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.touchBoundsInRoot
                    assertTrue(back.width >= 48 && back.height >= 48 && back.left >= page.left && back.right <= page.right, "Back target must remain reachable at $width/$font: $back")
                    val edge = Offset(back.left + 1f, back.center.y)
                    scene.sendPointerEvent(PointerEventType.Press, edge)
                    render()
                    scene.sendPointerEvent(PointerEventType.Release, edge)
                    repeat(3) { render() }
                    assertEquals(1, backs)
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun longLocalizedChoicesWrapWithoutClippingAndExposeOneSelectedRow() {
        val label = "A long localized reader preference 日本語 简体中文 ".repeat(10)
        for (width in listOf(320, 411, 1040)) {
            for (font in listOf(1f, 1.8f)) {
                var selected by mutableStateOf(false)
                var changes = 0
                val scene = ImageComposeScene(width, 1800, Density(1f, font)) {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize()) {
                            SettingsChoice(label, selected, true) {
                                selected = true
                                changes++
                            }
                            SettingsChoice("Other", false, false) { error("Disabled choices must not execute") }
                        }
                    }
                }
                var frame = 0L
                fun render() {
                    scene.render(frame).close()
                    frame += 16_666_667
                }
                fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                try {
                    repeat(5) { render() }
                    val text = all().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }.boundsInRoot
                    assertTrue(text.left >= 0 && text.right <= width && text.bottom <= 1800, "Localized text must wrap inside $width/$font: $text")
                    val rows = all().filter { it.config.contains(SemanticsProperties.Selected) }
                    assertEquals(2, rows.size)
                    assertTrue(rows.all { it.boundsInRoot.height >= 56f })
                    assertTrue(rows[0].boundsInRoot.bottom <= rows[1].boundsInRoot.top)
                    scene.sendPointerEvent(PointerEventType.Press, text.center)
                    render()
                    scene.sendPointerEvent(PointerEventType.Release, text.center)
                    repeat(3) { render() }
                    assertEquals(1, changes)
                    assertEquals(1, all().count { it.config.getOrNull(SemanticsProperties.Selected) == true })
                } finally {
                    scene.close()
                }
            }
        }
    }
}
