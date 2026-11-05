package com.ehviewer.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryCommentText
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.gallery.GallerySearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class GalleryCommentsTest {
    private fun comment(id: Long) = GalleryComment(id, 5, false, false, false, false, false, false, null, 1_790_000_000_000, "Reader $id", "<b>Owned $id</b>", 1_790_000_000_000, listOf(GalleryCommentText("Owned $id\n", bold = true), GalleryCommentText("Read page 2", link = "/s/1111111111/1-2")))
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    private class Scene(width: Int, font: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) : AutoCloseable {
        val scene = ImageComposeScene(width, 900, Density(1f, font)) { MaterialTheme(content = content) }
        private var time = 0L
        fun render() = repeat(3) {
            scene.render(time).close()
            time += 16_666_667
        }
        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at)
            render()
            scene.sendPointerEvent(PointerEventType.Release, at)
            render()
        }
        override fun close() = scene.close()
    }

    @Test fun originalPreviewRendersOnlyTwoCardsAndARealFooterAtNarrowAndLargeFontSizes() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for (width in listOf(320, 411, 1280)) {
                for (font in listOf(1f, 1.8f)) {
                    var opens = 0
                    val images = GalleryCoverCache<androidx.compose.ui.graphics.ImageBitmap> { error("No comment image expected") }
                    Scene(width, font) { GalleryCommentPreview(GalleryCommentList((1L..3L).map(::comment), false), images, 1, "0123456789") { opens++ } }.use { view ->
                        view.render()
                        val all = view.scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                        val texts = all.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                        assertTrue(texts.any { "Owned 1" in it } && texts.any { "Owned 2" in it })
                        assertFalse(texts.any { "Owned 3" in it || "<b>" in it })
                        val footer = all.single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "More comments" } }
                        assertTrue(footer.boundsInRoot.top >= 0 && footer.boundsInRoot.bottom <= 900 && footer.boundsInRoot.center.x in 0f..width.toFloat())
                        view.click(footer.boundsInRoot.center)
                        assertEquals(1, opens)
                    }
                }
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun fullCommentsRetryTheExactRequestAndRetainLoadedListAcrossARealPageLink() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        var requests = 0
        val all = GalleryCommentList((1L..30L).map(::comment), false)
        val engine = MockEngine { request ->
            assertEquals("1", request.url.parameters["hc"])
            requests++
            if (requests == 1) respond("Owned unavailable", HttpStatusCode.ServiceUnavailable) else respond("comments")
        }
        val client = HttpClient(engine) { followRedirects = false }
        val decoder = object : GalleryDocumentDecoder {
            override fun decodeDetails(html: ByteArray) = GalleryDetail(BaseGalleryInfo(gid = 1, token = "0123456789", pages = 2), tagGroups = emptyList(), comments = all, previewList = emptyList())
            override fun decodePage(html: ByteArray): GalleryImagePage = error("The detail reader owns image pages")
            override fun decode(html: ByteArray): GallerySearchResult = error("No listing expected")
            override fun decodeFavoriteNote(html: ByteArray): com.ehviewer.core.network.gallery.GalleryFavoriteNote = error("No mutation expected")
            override fun decodeProfileLink(html: ByteArray): String? = error("No account expected")
            override fun decodeProfile(html: ByteArray): com.ehviewer.core.network.session.GalleryAccountProfile = error("No account expected")
            override fun decodeUserConfig(html: ByteArray): List<String> = error("No account expected")
        }
        val library = object : GalleryLibrary {
            override val restricted = false
            override val content = GalleryContentRepository(client, decoder)
            override val galleries: GalleryRepository get() = error("No listing expected")
            override val favorites: GalleryFavoritesRepository get() = error("No mutation expected")
        }
        val state = GalleryCommentsState(GalleryCommentList(listOf(comment(1)), true)).apply { link = "#c12" }
        val images = GalleryCoverCache<androidx.compose.ui.graphics.ImageBitmap> { error("No inline image expected") }
        var page by mutableStateOf<String?>(null)
        try {
            Scene(411) {
                if (page != null) Text("Owned reader") else GalleryCommentsScreen(library, 1, "0123456789", state, images, onPage = { page = it }, onBack = { error("No Back expected") })
            }.use { view ->
                fun allNodes() = view.scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                fun textNode(value: String) = allNodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> value in text.text } }
                fun await(message: String, predicate: () -> Boolean) {
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (System.nanoTime() < deadline) {
                        view.render()
                        if (predicate()) return
                        Thread.sleep(5)
                    }
                    assertTrue(predicate(), message)
                }
                await("Actual503 must remain a visible failed request") { state.failure != null }
                assertEquals(1, requests)
                view.click(requireNotNull(textNode("Retry")).boundsInRoot.center)
                await("Retry must load all comments and navigate to the actual anchor") { state.loaded && state.list.firstVisibleItemIndex == 11 }
                assertEquals(2, requests)
                val text = requireNotNull(textNode("Owned 12"))
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(requireNotNull(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
                val layout = layouts.single()
                val offset = layout.layoutInput.text.text.indexOf("Read page 2")
                assertTrue(offset >= 0)
                view.click(text.boundsInRoot.topLeft + layout.getBoundingBox(offset + 2).center)
                await("The rendered link must route to the actual same-gallery reader URL") { page != null }
                assertEquals("https://e-hentai.org/s/1111111111/1-2", page)
                val index = state.list.firstVisibleItemIndex
                page = null
                await("Returning from the reader must restore comments") { textNode("Owned 12") != null }
                assertEquals(index, state.list.firstVisibleItemIndex)
                assertEquals(2, requests, "Returning must retain the completed all-comments request")
                assertEquals(all, state.comments)
            }
        } finally {
            client.close()
            engine.close()
            Locale.setDefault(before)
        }
    }
}
