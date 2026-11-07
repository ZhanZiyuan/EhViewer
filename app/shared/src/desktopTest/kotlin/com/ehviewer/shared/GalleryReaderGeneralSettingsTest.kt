package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.reader.ReaderPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GalleryReaderGeneralSettingsTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun originalSwitchRowsFitLargeFontsAndReflectOnlyCommittedPreferences() {
        for ((width, scale) in listOf(360 to 1f, 360 to 1.8f, 1040 to 1f)) {
            var preferences by mutableStateOf(ReaderPreferences())
            var enabled by mutableStateOf(true)
            val requested = mutableListOf<ReaderPreferences>()
            val scene = ImageComposeScene(width, 720, Density(1f, scale)) {
                GalleryTheme(false, null) {
                    Column { GalleryReaderGeneralSettings(preferences, enabled) { requested += it } }
                }
            }
            var frame = 0L
            fun render() {
                scene.render(frame++ * 16_666_667L).close()
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun switches() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch && it.config.contains(SemanticsActions.OnClick) }
                .sortedBy { it.boundsInRoot.top }
            fun click(bounds: Rect) {
                scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                render()
                scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                repeat(3) { render() }
            }
            try {
                repeat(5) { render() }
                val controls = switches()
                assertEquals(3, controls.size)
                controls.forEach { assertTrue(it.boundsInRoot.height >= 48f && it.boundsInRoot.bottom <= 720f) }
                controls.zipWithNext().forEach { (a, b) -> assertTrue(a.boundsInRoot.bottom <= b.boundsInRoot.top, "Large-font settings rows cannot overlap") }
                click(controls.first().boundsInRoot)
                assertEquals(1, requested.size)
                assertFalse(requested.single().showPageNumber)
                assertEquals(ToggleableState.On, switches().first().config[SemanticsProperties.ToggleableState], "Pending or failed storage must not invent a changed checkbox")
                preferences = requested.single()
                repeat(3) { render() }
                assertEquals(ToggleableState.Off, switches().first().config[SemanticsProperties.ToggleableState])
                enabled = false
                repeat(3) { render() }
                click(switches()[1].boundsInRoot)
                assertEquals(1, requested.size, "A saving/unavailable control must not dispatch another write")
            } finally {
                scene.close()
            }
        }
    }
}
