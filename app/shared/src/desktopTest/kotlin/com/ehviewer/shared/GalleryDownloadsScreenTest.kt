package com.ehviewer.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadMetadata
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.download.GalleryDownloadOpen
import com.ehviewer.core.network.download.GalleryDownloads
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

@OptIn(androidx.compose.ui.InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class GalleryDownloadsScreenTest {
    private class Queue(entries: List<GalleryDownload> = emptyList()) : GalleryDownloads {
        override val tasks = MutableStateFlow(entries)
        val actions = mutableListOf<String>()
        override suspend fun initialize() = error("Rendering must not initialize the queue")
        override suspend fun enqueue(gallery: GalleryDetail, restricted: Boolean) = error("Filtering must not enqueue")
        override suspend fun pause(gid: Long) {
            actions += "pause:$gid"
        }
        override suspend fun resume(gid: Long) {
            actions += "resume:$gid"
        }
        override suspend fun cancel(gid: Long) {
            actions += "cancel:$gid"
            tasks.value = tasks.value.filterNot { it.gid == gid }
        }
        override suspend fun pauseAll() = error("Filtering must not pause all")
        override suspend fun open(gid: Long): GalleryDownloadOpen {
            actions += "open:$gid"
            return GalleryDownloadOpen.PlatformReader(gid)
        }
        override fun close() = error("A child screen must not close the application queue")
    }

    private class View(width: Int, font: Float = 1f, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        val scene = ImageComposeScene(width, 1000, Density(1f, font), coroutineContext = StandardTestDispatcher(scheduler)) { GalleryTheme(dark, null) { content() } }
        private var frame = 0L
        fun render() = repeat(8) {
            scheduler.runCurrent()
            scene.render(frame).close()
            scheduler.runCurrent()
            frame += 16_666_667
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun text(label: String) = all().firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
        fun click(label: String) {
            val node = all().firstOrNull { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } ?: requireNotNull(text(label)) { "Missing rendered control $label" }
            val point = node.touchBoundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            render()
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        fun query(value: String) {
            val field = all().single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
            render()
        }
        override fun close() = scene.close()
    }

    private fun english(action: () -> Unit) {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            action()
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun searchCommitsLocallyTracksQueueUpdatesAndKeepsRealActionIdentities() = english {
        val original = listOf(
            GalleryDownload(1, "0123456789", "Alice in Wonderland", 3, false, GalleryDownloadState.COMPLETE, 3),
            GalleryDownload(2, "abcdef0123", "Second book", 5, false, GalleryDownloadState.PAUSED, 1),
        )
        val queue = Queue(original)
        val opened = mutableListOf<Long>()
        var backs = 0
        View(411) { GalleryDownloadsScreen(queue, true, false, {}, null, { opened += (it as GalleryDownloadOpen.PlatformReader).gid }, { backs++ }) }.use { view ->
            view.render()
            view.query("ALICE")
            assertTrue(view.text("Second book") != null, "An unsubmitted draft must not change results")
            view.click("Search")
            assertTrue(view.text("Alice in Wonderland") != null)
            assertTrue(view.text("Second book") == null)
            assertEquals(original, queue.tasks.value)
            assertTrue(queue.actions.isEmpty())
            view.click("Read offline")
            assertEquals(listOf(1L), opened)
            assertEquals(listOf("open:1"), queue.actions)
            queue.tasks.value = original + GalleryDownload(3, "1111111111", "ALICE follow-up", 2, false)
            view.render()
            assertTrue(view.text("ALICE follow-up") != null, "Live queue updates must obey the committed filter")
            view.query("second")
            view.click("Search")
            view.click("Resume")
            assertEquals("resume:2", queue.actions.last())
            view.click("Second book")
            view.click("Delete download and files")
            assertEquals("cancel:2", queue.actions.last())
            assertTrue(view.text("Download items will be shown here") != null, "Keep the original Downloads empty presentation for a filtered page")
            assertEquals(listOf(1L, 3L), queue.tasks.value.map { it.gid }, "Filtering must not erase hidden downloads")
            view.query("")
            view.click("Search")
            assertTrue(view.text("Alice in Wonderland") != null && view.text("ALICE follow-up") != null)
            view.click("Back")
            assertEquals(1, backs)
            assertEquals(listOf(1L, 3L), queue.tasks.value.map { it.gid })
        }
    }

    @Test
    fun originalEmptyStateAndSearchStayVisibleAcrossSizesThemesFontsAndStorageFailure() = english {
        assertEquals(120f, GalleryIcons.DownloadEmpty.defaultWidth.value)
        for (width in listOf(320, 840, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                for (dark in listOf(false, true)) {
                    var failed by mutableStateOf(false)
                    var retries = 0
                    var navigation = 0
                    View(width, font, dark) { GalleryDownloadsScreen(Queue(), true, failed, { retries++ }, null, {}, {}, openNavigation = { navigation++ }) }.use { view ->
                        view.render()
                        val text = requireNotNull(view.text("Download items will be shown here"))
                        val bounds = text.boundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top > 72 && bounds.bottom <= 1000, "Empty text clipped: $width/$font/$dark $bounds")
                        view.click("Open navigation")
                        assertEquals(1, navigation)
                        failed = true
                        view.render()
                        assertFalse(view.text("Download items will be shown here") != null, "Storage failure must not claim an empty queue")
                        view.click("Retry")
                        assertEquals(1, retries)
                        assertTrue(view.all().any { "Search Downloads" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                    }
                }
            }
        }
    }

    @Test
    fun actualCardTextCategoryAndControlsRemainInsideNarrowLargeFontAndWideLayouts() = english {
        val title = "Owned long download title 日本語"
        val uploader = "Owned artist with a long public name"
        val task = GalleryDownload(1, "0123456789", title, 3, false, GalleryDownloadState.PAUSED, 1, metadata = GalleryDownloadMetadata(uploader, true, 4.5f, 512))
        for (width in listOf(320, 840, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                for (dark in listOf(false, true)) {
                    val queue = Queue(listOf(task))
                    View(width, font, dark) { GalleryDownloadsScreen(queue, true, false, {}, null, {}, {}) }.use { view ->
                        view.render()
                        for (label in listOf(title, uploader, galleryCategoryName(512).uppercase(), "Idle")) {
                            val node = requireNotNull(view.text(label)) { "Missing actual card text $label at $width/$font/$dark" }
                            val bounds = node.boundsInRoot
                            assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top > 72 && bounds.bottom <= 1000, "Card text clipped at $width/$font/$dark: $label $bounds")
                        }
                        val resume = view.all().single { "Resume" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                        val bounds = resume.touchBoundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top > 72 && bounds.bottom <= 1000, "Actual action is outside the viewport: $bounds")
                        assertTrue(view.all().any { it.config.getOrNull(SemanticsProperties.StateDescription) == "Paused · 1 / 3" })
                        view.click("Resume")
                        assertEquals(listOf("resume:1"), queue.actions)
                    }
                }
            }
        }
    }
}
