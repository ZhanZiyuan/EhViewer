package com.ehviewer.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.network.gallery.GalleryContent
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GalleryTorrent
import io.ktor.http.Url
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

@Suppress("DEPRECATION")
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class GalleryTorrentDialogTest {
    private val torrent = GalleryTorrent(true, "2026-11-05", "63.14 MiB", 12, 3, 42, "Reader", "https://ehtracker.org/get/1/0123456789abcdef0123456789abcdef01234567.torrent", "Owned torrent.zip")

    private class Content(private val load: suspend () -> List<GalleryTorrent>) : GalleryContent {
        override suspend fun torrents(gid: Long, token: String): List<GalleryTorrent> {
            assertEquals(1, gid)
            assertEquals("0123456789", token)
            return load()
        }
        override suspend fun details(gid: Long, token: String, previewPage: Int): GalleryDetail = error("No detail request")
        override fun pageUrl(gid: Long, pageToken: String, index: Int): Url = error("No page request")
        override fun pageIndex(url: String, gid: Long): Int = error("No page request")
        override suspend fun page(url: String, gid: Long, reloadKey: String?): GalleryImagePage = error("No page request")
        override suspend fun image(page: GalleryImagePage, pageUrl: String, gid: Long): ByteArray = error("No image request")
        override suspend fun preview(preview: GalleryPreview, gid: Long, token: String): ByteArray = error("No preview request")
    }

    private class View(width: Int = 411, font: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        val scene = ImageComposeScene(width, 1000, Density(1f, font), coroutineContext = StandardTestDispatcher(scheduler)) { GalleryTheme(false, null) { content() } }
        private var frame = 0L
        fun render() = repeat(8) {
            scheduler.runCurrent()
            scene.render(frame).close()
            scheduler.runCurrent()
            frame += 16_666_667
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun text(value: String) = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value } }
        fun click(value: String) {
            val point = requireNotNull(text(value)) { "Missing rendered control: $value" }.boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            render()
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        fun dismiss() {
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            render()
        }
        override fun close() = scene.close()
    }

    @Test fun serverRetryAndSystemHandlerFailuresRetainTheListAndExplicitCopy() = english {
        var calls = 0
        val content = Content {
            if (++calls == 1) throw GalleryRequestException(GalleryFailure.SERVER)
            listOf(torrent)
        }
        var copied = "Owned sentinel"
        var rejectCopy = true
        var rejectOpen = true
        val opened = mutableListOf<String>()
        var visible by mutableStateOf(true)
        val clipboard = object : ClipboardManager {
            override fun getText() = AnnotatedString(copied)
            override fun setText(annotatedString: AnnotatedString) {
                check(!rejectCopy)
                copied = annotatedString.text
            }
        }
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                check(!rejectOpen)
                opened += uri
            }
        }
        View(320, 1.8f) {
            CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalUriHandler provides handler) {
                if (visible) GalleryTorrentDialog(content, 1, "0123456789", onEmpty = { error("Not empty") }, onCopied = { visible = false }, onDismiss = { visible = false }) else Text("Owned details")
            }
        }.use { view ->
            view.render()
            assertTrue(view.text("The site is temporarily unavailable.") != null)
            assertEquals(1, calls)
            view.click("Retry")
            assertEquals(2, calls)
            assertTrue(view.text(torrent.name) != null)
            assertFalse(view.text("Copy link") != null, "Original stable rows must not gain an extra button")
            view.click(torrent.name)
            assertTrue(view.text("The link could not be copied. Try again.") != null)
            assertTrue(view.text("No torrent application could be opened. Copy the link or try again.") != null)
            assertEquals("Owned sentinel", copied)
            assertTrue(opened.isEmpty())
            assertEquals(2, calls, "Handler failures must not repeat HTTP requests")
            rejectCopy = false
            view.click("Copy link")
            assertEquals(torrent.magnet(1), copied)
            assertFalse(visible)
            assertTrue(view.text("Owned details") != null)
            visible = true
            rejectOpen = false
            view.render()
            view.click(torrent.name)
            assertEquals(listOf(torrent.magnet(1)), opened)
            assertFalse(visible)
            assertEquals(3, calls, "Explicit reopen starts one fresh request")
            visible = true
            rejectOpen = true
            copied = "Owned second sentinel"
            view.render()
            view.click(torrent.name)
            assertEquals(torrent.magnet(1), copied, "The original missing-handler path automatically copies the magnet")
            assertFalse(visible)
            assertEquals(4, calls)
        }
    }

    @Test fun cancellingPendingLoadActuallyCancelsItAndNeverEmptiesOrLaunches() = english {
        var cancelled = false
        var visible by mutableStateOf(true)
        val content = Content {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        View {
            if (visible) GalleryTorrentDialog(content, 1, "0123456789", onEmpty = { error("Cancellation is not empty") }, onCopied = { error("Cancellation must not copy") }, onDismiss = { visible = false }) else Text("Owned details")
        }.use { view ->
            view.render()
            assertFalse(cancelled)
            view.dismiss()
            assertTrue(cancelled)
            assertTrue(view.text("Owned details") != null)
        }
    }

    @Test fun explicitEmptyReturnsToDetailsAndRowsRemainAccessibleAcrossSizes() = english {
        var empty = 0
        var visible by mutableStateOf(true)
        val content = Content { emptyList() }
        View {
            if (visible) {
                GalleryTorrentDialog(content, 1, "0123456789", onEmpty = {
                    empty++
                    visible = false
                }, onCopied = { error("Empty must not copy") }, onDismiss = { error("Not cancelled") })
            } else {
                Text("Owned details")
            }
        }.use { view ->
            view.render()
            assertEquals(1, empty)
            assertTrue(view.text("Owned details") != null)
        }
        val loaded = Content { listOf(torrent) }
        for (width in listOf(320, 411, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                var dismissed = false
                View(width, font) { GalleryTorrentDialog(loaded, 1, "0123456789", onEmpty = { error("Not empty") }, onCopied = { error("No copy expected") }, onDismiss = { dismissed = true }) }.use { view ->
                    view.render()
                    for (label in listOf(torrent.name, "Torrents")) {
                        val bounds = requireNotNull(view.text(label)).boundsInWindow
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 1000, "$label clipped at $width/$font: $bounds")
                    }
                    view.dismiss()
                    assertTrue(dismissed)
                }
            }
        }
    }

    private fun english(block: () -> Unit) {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            block()
        } finally {
            Locale.setDefault(before)
        }
    }
}
