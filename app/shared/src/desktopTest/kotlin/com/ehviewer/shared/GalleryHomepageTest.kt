package com.ehviewer.shared

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.AppLanguageStore
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.download.GalleryDownloadOpen
import com.ehviewer.core.network.download.GalleryDownloads
import com.ehviewer.core.network.gallery.GalleryContent
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.shared.settings.GalleryAppearanceStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.GalleryThemeMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import java.util.Collections
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** Actual shared screen, pointer actions, saved composition state and Ktor requests. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class GalleryHomepageTest {
    private class Library : GalleryLibrary, AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Url>())

        @Volatile var unavailable = false

        @Volatile var blocked = false
        private val engine = MockEngine { request ->
            requests += request.url
            if (blocked) awaitCancellation()
            if (unavailable) respond("Unavailable", HttpStatusCode.ServiceUnavailable) else respond(request.url.parameters["f_search"] ?: "Homepage")
        }
        private val client = HttpClient(engine)
        override val restricted = false
        override val galleries = GalleryRepository(client, { bytes -> GallerySearchResult(null, null, listOf(BaseGalleryInfo(1, "0123456789", bytes.decodeToString(), category = 512))) })
        override val content: GalleryContent get() = error("Browsing must not request details")
        override val favorites: GalleryFavoritesRepository get() = error("Browsing must not mutate favorites")
        override fun close() {
            client.close()
            engine.close()
        }
    }

    private class View(library: Library, browse: Boolean = true, local: Boolean = false, saved: Map<String, List<Any?>>? = null) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private val dispatcher = StandardTestDispatcher(scheduler)
        val registry = SaveableStateRegistry(saved) { true }
        private val appearance = object : GalleryAppearanceStore {
            override suspend fun mode() = GalleryThemeMode.LIGHT
            override suspend fun setMode(mode: GalleryThemeMode) = error("Browsing must not change appearance")
        }
        private val language = GalleryLanguageSettings(object : AppLanguageStore {
            override suspend fun tag(): String? = "en"
            override suspend fun setTag(tag: String?) = error("Browsing must not change language")
        }) {}
        private val reader = object : ReaderStateStore {
            override suspend fun preferences() = ReaderPreferences()
            override suspend fun position(gid: Long) = null
            override suspend fun setPreferences(preferences: ReaderPreferences) = error("Browsing must not change reading preferences")
            override suspend fun savePosition(gid: Long, index: Int, pageToken: String?) = error("Browsing must not save progress")
        }
        private val history = object : GalleryHistoryStore {
            override suspend fun recent(limit: Int) = emptyList<BaseGalleryInfo>()
            override suspend fun record(gallery: GalleryInfo) = error("Browsing must not record an unopened gallery")
            override suspend fun touch(gid: Long) = error("Browsing must not touch history")
            override suspend fun forget(gid: Long) = error("Browsing must not remove history")
            override suspend fun clear() = error("Browsing must not clear history")
        }
        private val downloads = object : GalleryDownloads {
            override val tasks = MutableStateFlow(emptyList<com.ehviewer.core.domain.download.GalleryDownload>())
            override suspend fun initialize() = Unit
            override suspend fun enqueue(gallery: GalleryDetail, restricted: Boolean) = error("Browsing must not enqueue")
            override suspend fun pause(gid: Long) = error("Browsing must not pause")
            override suspend fun resume(gid: Long) = error("Browsing must not resume")
            override suspend fun cancel(gid: Long) = error("Browsing must not cancel")
            override suspend fun pauseAll() = error("Browsing must not pause all")
            override suspend fun open(gid: Long): GalleryDownloadOpen = error("Browsing must not read downloads")
            override fun close() = error("A child screen must not close the queue")
        }
        init {
            Dispatchers.setMain(dispatcher)
        }
        val scene = ImageComposeScene(1000, 900, Density(1f), coroutineContext = dispatcher) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                GalleryLibraryScreen(library, reader, appearance, history, downloads, language, archiveContent = { Text("Owned local library") }, accountContent = { _, back -> TextButton(onClick = back) { Text("Back from account") } }, initialLocalLibrary = local, browseOnEntry = browse)
            }
        }
        private var frame = 0L
        fun render() = repeat(4) {
            scheduler.runCurrent()
            scene.render(frame).close()
            scheduler.runCurrent()
            frame += 16_666_667
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun text(value: String) = all().firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value } }
        fun await(value: String, predicate: () -> Boolean = { text(value) != null }) {
            val deadline = System.nanoTime() + 10_000_000_000
            do {
                render()
                if (predicate()) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            error("Missing rendered homepage state: $value")
        }
        fun click(value: String) {
            val node = all().firstOrNull { value in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } ?: requireNotNull(text(value))
            val at = node.touchBoundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, at)
            render()
            scene.sendPointerEvent(PointerEventType.Release, at)
            render()
        }
        fun query(value: String) {
            val field = all().single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
            render()
        }
        override fun close() {
            scene.close()
            scheduler.runCurrent()
            Dispatchers.resetMain()
        }
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

    @Test fun homepageLoadsOnceAndRestoresTheCommittedRouteWithoutSubmittingADraft() = english {
        Library().use { library ->
            val saved = View(library).use { view ->
                view.await("Homepage")
                assertTrue(library.requests.single().parameters.isEmpty())
                view.click("Downloads")
                view.click("Galleries")
                view.await("Homepage")
                assertEquals(1, library.requests.size)
                view.query("committed")
                view.click("Search")
                view.await("committed")
                view.query("unfinished draft")
                view.scene.constraints = Constraints.fixed(411, 900)
                view.render()
                assertEquals(2, library.requests.size)
                view.registry.performSave()
            }
            View(library, saved = saved).use { view ->
                view.await("committed")
                assertEquals(listOf(null, "committed", "committed"), library.requests.map { it.parameters["f_search"] })
                assertTrue(view.all().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "unfinished draft" })
            }
        }
    }

    @Test fun failedHomepageWaitsForExplicitRetryAndDoesNotSubmitEditedInput() = english {
        Library().use { library ->
            library.unavailable = true
            View(library).use { view ->
                view.await("The site is temporarily unavailable.")
                view.query("unsubmitted query")
                view.click("Downloads")
                view.click("Galleries")
                view.render()
                assertEquals(1, library.requests.size)
                library.unavailable = false
                view.click("The site is temporarily unavailable.")
                view.await("Homepage")
                assertEquals(listOf(null, null), library.requests.map { it.parameters["f_search"] })
            }
        }
    }

    @Test fun incomingLocalDocumentStaysOfflineUntilGalleriesIsSelected() = english {
        Library().use { library ->
            View(library, local = true).use { view ->
                view.await("Owned local library")
                assertTrue(library.requests.isEmpty())
                view.click("Galleries")
                view.await("Homepage")
                view.click("Local library")
                view.await("Owned local library")
                view.click("Galleries")
                view.await("Homepage")
                assertEquals(1, library.requests.size)
            }
        }
    }

    @Test fun returningFromAccountResumesItsCancelledInitialLoad() = english {
        Library().use { library ->
            library.blocked = true
            View(library).use { view ->
                view.await("Initial request must start") { library.requests.size == 1 }
                view.click("Account")
                view.await("Back from account")
                library.blocked = false
                view.click("Back from account")
                view.await("Homepage")
                assertEquals(listOf(null, null), library.requests.map { it.parameters["f_search"] })
            }
        }
    }

    @Test fun embeddedExplicitSearchKeepsItsExistingOptInContract() = english {
        Library().use { library ->
            View(library, browse = false).use { view ->
                view.render()
                assertTrue(library.requests.isEmpty())
                view.query("explicit")
                view.click("Search")
                view.await("explicit")
                assertEquals(listOf("explicit"), library.requests.map { it.parameters["f_search"] })
            }
        }
    }
}
