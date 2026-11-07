package com.ehviewer.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveExport
import com.ehviewer.core.domain.reader.ArchiveLibrary
import com.ehviewer.core.domain.reader.ArchiveLibrarySnapshot
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.gallery.GalleryContent
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository
import com.ehviewer.core.network.gallery.GalleryRepository
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_archive_password
import ehviewer.app.shared.generated.resources.gallery_back_library
import ehviewer.app.shared.generated.resources.gallery_comic_read
import ehviewer.app.shared.generated.resources.gallery_import_cancel
import ehviewer.app.shared.generated.resources.gallery_library_import_archive
import ehviewer.app.shared.generated.resources.gallery_library_open_failed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/** Controlled UI failures/cancellation; native private copying is checked by Desktop archive regression. */
class ArchiveInitialImportTest {
    private val book = ArchiveBook("a".repeat(64), "Opened comic", 128, 2, true)
    private val online = object : GalleryLibrary {
        override val restricted get() = false
        override val galleries: GalleryRepository get() = error("Local import must not request online galleries")
        override val content: GalleryContent get() = error("Protected local import must not request online content")
        override val favorites: GalleryFavoritesRepository get() = error("Local import must not request favorites")
    }

    private inner class Library : ArchiveLibrary {
        val books = mutableListOf<ArchiveBook>()
        var opened = 0
        var closedDocuments = 0
        override suspend fun recent() = books.toList()
        override suspend fun inspect() = ArchiveLibrarySnapshot(books.toList(), 0)
        override suspend fun importFile(path: String, title: String): ArchiveBook = error("Use the controlled import action")
        override suspend fun importDirectory(path: String, title: String): ArchiveBook = error("Use the controlled import action")
        override suspend fun prepareExport(book: ArchiveBook): ArchiveExport = error("Import must not export")
        override suspend fun remove(book: ArchiveBook) {
            books.remove(book)
        }
        override suspend fun close() = Unit
        override suspend fun open(book: ArchiveBook): ArchiveDocument {
            opened++
            return object : ArchiveDocument {
                override val pageCount = 2
                override val needsPassword = true
                override fun extension(index: Int) = "png"
                override fun providePassword(password: String) = false
                override fun read(index: Int): ByteArray = error("Locked documents must not be read")
                override fun close() {
                    closedDocuments++
                }
            }
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private inner class Screen(val library: Library, initial: suspend () -> ArchiveBook?) : AutoCloseable {
        var visible by mutableStateOf(true)
        var frame = 0L
        var back = 0
        val labels = mutableMapOf<String, String>()
        val scene = ImageComposeScene(800, 720, Density(1f)) {
            MaterialTheme {
                labels["password"] = galleryString(Res.string.gallery_archive_password)
                labels["back"] = galleryString(Res.string.gallery_back_library)
                labels["read"] = galleryString(Res.string.gallery_comic_read)
                labels["cancel"] = galleryString(Res.string.gallery_import_cancel)
                labels["import"] = galleryString(Res.string.gallery_library_import_archive)
                labels["failure"] = galleryString(Res.string.gallery_library_open_failed)
                if (visible) {
                    ArchiveLibraryScreen(library, online, pickAndImport = {
                        library.books.add(book)
                        book
                    }, pickAndImportDirectory = { error("No directory picker requested") }, pickAndExport = { error("No export requested") }, readingState = { error("Locked document must not read progress") }, onBack = { back++ }, initialImport = initial)
                }
            }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun texts() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
        fun render() {
            scene.render(frame).close()
            frame += 16_666_667
        }
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000
            while (System.nanoTime() < deadline) {
                render()
                if (predicate()) return
                Thread.sleep(5)
            }
            assertTrue(predicate(), "$message; actual=${texts()}")
        }
        fun click(text: String) {
            val target = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.first { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, target)
            render()
            scene.sendPointerEvent(PointerEventType.Release, target)
            render()
        }
        override fun close() {
            scene.close()
        }
    }

    @Test
    fun incomingDocumentOpensOnceAndReturningReleasesItWithoutReimporting() {
        val library = Library()
        var imports = 0
        Screen(library) {
            imports++
            library.books.add(book)
            book
        }.use { screen ->
            screen.await("Imported document must reach the real password UI") { screen.labels["password"] in screen.texts() }
            assertEquals(1, imports)
            assertEquals(1, library.opened)
            screen.click(checkNotNull(screen.labels["back"]))
            screen.await("Returning must list the imported book") { screen.labels["read"] in screen.texts() }
            repeat(10) { screen.render() }
            assertEquals(1, imports)
            assertEquals(1, library.closedDocuments)
        }
        assertEquals(1, library.closedDocuments)
    }

    @Test
    fun failedIncomingDocumentKeepsManualImportAvailableWithoutRepeatingTheRequest() {
        val library = Library()
        var imports = 0
        Screen(library) {
            imports++
            error("Controlled unreadable input")
        }.use { screen ->
            screen.await("Failure must remain visible") { screen.labels["failure"] in screen.texts() }
            assertEquals(1, imports)
            screen.click(checkNotNull(screen.labels["import"]))
            screen.await("A manual import must recover after startup failure") { screen.labels["password"] in screen.texts() }
            assertEquals(1, imports)
        }
        assertEquals(1, library.closedDocuments)
    }

    @Test
    fun cancelAndWindowDisposalCancelTheActualPendingRequest() {
        for (dispose in listOf(false, true)) {
            val library = Library()
            var imports = 0
            val cancelled = CompletableDeferred<Unit>()
            Screen(library) {
                imports++
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }.use { screen ->
                screen.await("Incoming copy must expose the existing cancel action") { screen.labels["cancel"] in screen.texts() }
                if (dispose) screen.visible = false else screen.click(checkNotNull(screen.labels["cancel"]))
                screen.await("Cancellation must reach the suspended import") { cancelled.isCompleted }
                repeat(10) { screen.render() }
                assertEquals(1, imports)
                assertEquals(0, library.opened)
                if (!dispose) {
                    screen.click(checkNotNull(screen.labels["import"]))
                    screen.await("Manual import must work after cancellation") { screen.labels["password"] in screen.texts() }
                }
            }
        }
    }
}
