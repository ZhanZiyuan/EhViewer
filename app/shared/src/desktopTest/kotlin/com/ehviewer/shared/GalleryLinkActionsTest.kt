package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class GalleryLinkActionsTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun desktopShareRetriesRealActionsAndCancelNeverCopiesOrOpensALink() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            var url by mutableStateOf("https://e-hentai.org/g/1/0123456789/")
            var copied = "Owned clipboard sentinel"
            var rejectCopy = true
            var rejectBrowser = true
            val opened = mutableListOf<String>()
            val clipboard = object : ClipboardManager {
                override fun getText() = AnnotatedString(copied)
                override fun setText(annotatedString: AnnotatedString) {
                    check(!rejectCopy) { "Owned clipboard rejection" }
                    copied = annotatedString.text
                }
            }
            val browser = object : UriHandler {
                override fun openUri(uri: String) {
                    check(!rejectBrowser) { "Owned browser rejection" }
                    opened += uri
                }
            }
            val scene = ImageComposeScene(320, 900, Density(1f, 1.8f)) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalUriHandler provides browser) {
                    MaterialTheme {
                        GalleryLinkActions(url) { copy, open, share ->
                            Column {
                                Button(onClick = share) { Text("Owned share") }
                                Button(onClick = copy) { Text("Owned copy") }
                                Button(onClick = open) { Text("Owned browser") }
                            }
                        }
                    }
                }
            }
            var frame = 0L
            fun render() = repeat(10) {
                scene.render(frame).close()
                frame += 16_666_667
            }
            fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
            fun hasText(label: String) = all().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
            fun click(label: String) {
                val node = all().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
                val point = node.boundsInWindow.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                render()
                scene.sendPointerEvent(PointerEventType.Release, point)
                render()
            }
            try {
                render()
                click("Owned share")
                assertTrue(hasText(url))
                click("Cancel")
                assertEquals("Owned clipboard sentinel", copied)
                assertTrue(opened.isEmpty())
                click("Owned share")
                click("Copy link")
                assertTrue(hasText("The link could not be copied. Try again."))
                assertTrue(hasText(url))
                assertEquals("Owned clipboard sentinel", copied)
                rejectCopy = false
                click("Copy link")
                assertEquals(url, copied)
                assertTrue(hasText("Link copied"))
                assertFalse(hasText("Cancel"))
                url = "https://exhentai.org/g/2/9876543210/"
                render()
                assertFalse(hasText("Link copied"), "Feedback must not leak between galleries")
                click("Owned share")
                click("Open in browser")
                assertTrue(hasText("The browser could not be opened. Try again."))
                assertTrue(opened.isEmpty())
                rejectBrowser = false
                click("Open in browser")
                assertEquals(listOf(url), opened)
                assertTrue(hasText("Sent to the browser"))
                assertFalse(hasText("Cancel"))
            } finally {
                scene.close()
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
