package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.ehviewer.core.model.GalleryTag
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.VoteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryDetailTagsTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun wrappingPillsRemainWithinPhonesAndWideWindowsAndSearchTheExactTag() {
        val literal = "Alice / 日本語 ?x=1#quoted\""
        val tags = listOf(literal, "A".repeat(160), "short", "another tag")
        val group = GalleryTagGroup(TagNamespace.Parody, tags.map { GalleryTag(it, PowerStatus.Weak, VoteStatus.None) })
        for (width in listOf(320, 411, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                val requests = mutableListOf<String>()
                val scene = ImageComposeScene(width, 1800, Density(1f, font)) {
                    MaterialTheme { Column { GalleryDetailTagGroup(group, { requests += it }) } }
                }
                var frame = 0L
                fun render() {
                    scene.render(frame).close()
                    frame += 16_666_667
                }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                try {
                    repeat(3) { render() }
                    val pills = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.filter { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("parody:") } == true
                    }
                    assertEquals(tags.size, pills.size)
                    for (pill in pills) {
                        val bounds = pill.boundsInRoot
                        assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= width, "Clipped tag at $width/$font: $bounds")
                    }
                    val target = pills.single { "parody:$literal" in checkNotNull(it.config.getOrNull(SemanticsProperties.ContentDescription)) }.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, target)
                    render()
                    scene.sendPointerEvent(PointerEventType.Release, target)
                    repeat(3) { render() }
                    assertEquals(listOf("parody:$literal"), requests, "A real pointer tap must preserve the literal namespace and text")
                } finally {
                    scene.close()
                }
            }
        }
    }
}
