package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.AppLanguageStore
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.domain.GalleryLaunchPage
import com.ehviewer.core.domain.GalleryLaunchPreferences
import com.ehviewer.core.domain.GalleryLaunchSettings
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListMode
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.GalleryListPresentationPreferences
import com.ehviewer.core.domain.GalleryQuickSearch
import com.ehviewer.core.domain.GalleryQuickSearchPreferences
import com.ehviewer.core.domain.GalleryQuickSearchStore
import com.ehviewer.core.domain.GalleryToplistPeriod
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.domain.reader.ReadingPosition
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
import com.ehviewer.shared.settings.GalleryAppearancePreferences
import com.ehviewer.shared.settings.GalleryAppearanceRepository
import com.ehviewer.shared.settings.GalleryAppearanceStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.GalleryThemeMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import java.util.Collections
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    private class Library(val withCovers: Boolean = false, val galleryCount: Int = 1, val pageCount: Int = 0) : GalleryLibrary, AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val coverRequests = Collections.synchronizedList(mutableListOf<Url>())

        @Volatile var changedIdentity = false
        private val changedCover by lazy { SkiaGalleryCoverTest().ownedPng(16, 16) }

        @Volatile var unavailable = false

        @Volatile var blocked = false
        private val engine = MockEngine { request ->
            if (request.url.host == "ehgt.org") {
                check(withCovers)
                coverRequests += request.url
                return@MockEngine respond(if (changedIdentity) changedCover else GalleryAnimationFixtures.gif, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, if (changedIdentity) "image/png" else "image/gif"))
            }
            requests += request.url
            if (blocked) awaitCancellation()
            if (unavailable) {
                respond("Unavailable", HttpStatusCode.ServiceUnavailable)
            } else {
                respond(
                    request.url.parameters["f_search"] ?: when (request.url.encodedPath) {
                        "/watched" -> "Watched fixture"
                        "/popular" -> "Popular fixture"
                        "/toplist.php" -> "Toplist fixture"
                        else -> "Homepage"
                    },
                )
            }
        }
        private val client = HttpClient(engine)
        override val restricted = false
        override val galleries = GalleryRepository(client, { bytes ->
            val title = bytes.decodeToString()
            GallerySearchResult(
                null,
                null,
                List(galleryCount) { index ->
                    BaseGalleryInfo(index + 1L, "0123456789", if (index == 0) title else "$title ${index + 1}", thumbKey = "owned-cover.png".takeIf { withCovers }, category = 512, pages = pageCount)
                },
            )
        })
        override val content: GalleryContent get() = error("Browsing must not request details")
        override val favorites: GalleryFavoritesRepository get() = error("Browsing must not mutate favorites")
        override fun close() {
            client.close()
            engine.close()
        }
    }

    private class ListingPreferences : GalleryListPreferences, GalleryListPresentationPreferences {
        var value = GalleryListPresentation()
        override suspend fun density() = GalleryListDensity.COMFORTABLE
        override suspend fun setDensity(density: GalleryListDensity) = error("List-mode test must not change the saved detail width")
        override suspend fun showComments() = true
        override suspend fun setShowComments(show: Boolean) = error("List-mode test must not change comments")
        override suspend fun presentation() = value
        override suspend fun setMode(mode: GalleryListMode) {
            value = value.copy(mode = mode)
        }
        override suspend fun setDetailThumbnailSize(size: Int) {
            value = value.copy(detailThumbnailSize = size)
        }
        override suspend fun setThumbnailColumns(columns: Int) {
            value = value.copy(thumbnailColumns = columns)
        }
        override suspend fun setShowPages(enabled: Boolean) {
            value = value.copy(showPages = enabled)
        }
        override suspend fun setShowReadingProgress(enabled: Boolean) {
            value = value.copy(showReadingProgress = enabled)
        }
    }

    private class View(library: Library, browse: Boolean = true, local: Boolean = false, saved: Map<String, List<Any?>>? = null, appearance: GalleryAppearanceStore = browsingAppearance, listPreferences: ListingPreferences? = null, width: Int = 1000, launchPreferences: GalleryLaunchPreferences? = null, height: Int = 900, readingPositions: Map<Long, ReadingPosition> = emptyMap(), quickSearch: GalleryQuickSearchControls? = null) : AutoCloseable {
        var identityPauses = 0
            private set
        private val scheduler = TestCoroutineScheduler()
        private val dispatcher = StandardTestDispatcher(scheduler)
        val registry = SaveableStateRegistry(saved) { true }
        companion object {
            private val browsingAppearance = object : GalleryAppearanceStore {
                override suspend fun preferences() = GalleryAppearancePreferences(mode = GalleryThemeMode.LIGHT)
                override suspend fun setMode(mode: GalleryThemeMode) = error("Browsing must not change appearance")
                override suspend fun setBlackDarkTheme(enabled: Boolean) = error("Browsing must not change black theme")
                override suspend fun setHarmonizeCategoryColor(enabled: Boolean) = error("Browsing must not change category colors")
            }
        }
        private val language = GalleryLanguageSettings(object : AppLanguageStore {
            override suspend fun tag(): String? = "en"
            override suspend fun setTag(tag: String?) = error("Browsing must not change language")
        }) {}
        private val reader = object : ReaderStateStore {
            override suspend fun preferences() = ReaderPreferences()
            override suspend fun position(gid: Long) = readingPositions[gid]
            override suspend fun setPreferences(preferences: ReaderPreferences) = error("Browsing must not change reading preferences")
            override suspend fun savePosition(gid: Long, index: Int, pageToken: String?) = error("Browsing must not save progress")
        }
        private val history = object : GalleryHistoryStore {
            override suspend fun recent(limit: Int) = emptyList<BaseGalleryInfo>()
            override suspend fun record(gallery: GalleryInfo) = error("Browsing must not record an unopened gallery")
            override suspend fun touch(gid: Long) = error("Browsing must not touch history")
            override suspend fun recordDownloaded(gallery: com.ehviewer.core.domain.download.GalleryDownload) = error("Browsing must not record a download")
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
            override suspend fun pauseAll() {
                check(library.withCovers) { "Browsing must not pause all" }
                identityPauses++
            }
            override suspend fun open(gid: Long): GalleryDownloadOpen = error("Browsing must not read downloads")
            override fun close() = error("A child screen must not close the queue")
        }
        init {
            Dispatchers.setMain(dispatcher)
        }
        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = dispatcher) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                GalleryLibraryScreen(library, reader, appearance, history, downloads, language, archiveContent = { Text("Owned local library") }, accountContent = { changed, back ->
                    Column {
                        TextButton(onClick = back) { Text("Back from account") }
                        if (library.withCovers) {
                            TextButton(onClick = {
                                library.changedIdentity = true
                                changed()
                                back()
                            }) { Text("Change owned identity") }
                        }
                    }
                }, listPreferences = listPreferences, presentationPreferences = listPreferences, launchPreferences = launchPreferences, quickSearch = quickSearch, initialLocalLibrary = local, browseOnEntry = browse, restoreLaunchPage = launchPreferences != null)
            }
        }
        private var frame = 0L
        fun render() = repeat(4) {
            scheduler.runCurrent()
            scene.render(frame).close()
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            frame += 16_666_667
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun text(value: String) = all().firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value } }
        fun detailCard(value: String) = all().first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value }
            }
        }
        fun await(value: String, predicate: () -> Boolean = { text(value) != null }) {
            val deadline = System.nanoTime() + 10_000_000_000
            do {
                render()
                if (predicate()) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            val labels = all().flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.distinct().take(30)
            error("Missing rendered homepage state: $value. Current labels: $labels")
        }
        fun click(value: String) {
            val node = all().firstOrNull { value in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } ?: requireNotNull(text(value))
            tap(node.touchBoundsInRoot.center)
        }
        fun clickIconButton(label: String) {
            val button = all().last { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                    label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
            }
            tap(button.boundsInRoot.center)
        }
        fun tap(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at)
            render()
            scene.sendPointerEvent(PointerEventType.Release, at)
            // Once a destination replaces its button, that pointer may hover the new cover.
            // Leave the window and settle the real interaction layer before pixel assertions.
            scene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f))
            render()
        }
        fun drag(from: Offset, to: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = frame / 1_000_000, type = PointerType.Mouse)
            render()
            for (step in 1..20) {
                val at = from + (to - from) * (step / 20f)
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = frame / 1_000_000, type = PointerType.Mouse)
                render()
            }
            scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = frame / 1_000_000, type = PointerType.Mouse)
            render()
        }
        fun query(value: String) {
            val field = all().single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
            render()
        }
        fun coverPixels(magenta: Boolean): Int {
            val image = scene.render(frame)
            frame += 16_666_667
            return image.use {
                val pixels = it.toComposeImageBitmap().toPixelMap()
                var count = 0
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        val color = pixels[x, y]
                        if (color.red > 0.99f && color.green < 0.01f && (if (magenta) color.blue > 0.99f else color.blue < 0.01f)) count++
                    }
                }
                count
            }
        }
        fun appearancePixels(): Pair<Int, Int> = scene.render(frame).use { image ->
            frame += 16_666_667
            val pixels = image.toComposeImageBitmap().toPixelMap()
            var originalCategoryPixels = 0
            for (y in 0 until 340) {
                for (x in 0 until pixels.width) {
                    if (pixels[x, y].toArgb() == 0xff8bc34a.toInt()) originalCategoryPixels++
                }
            }
            pixels[pixels.width - 10, pixels.height - 10].toArgb() to originalCategoryPixels
        }
        fun captureFailure() {
            val output = java.io.File("build/tmp/gallery-identity-cover-failure.png").also { it.parentFile.mkdirs() }
            scene.render(frame).use { image ->
                frame += 16_666_667
                checkNotNull(image.encodeToData()).use { output.writeBytes(it.bytes) }
            }
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

    @Test fun changingTheOriginalLaunchPageOpensTheSelectedFeedImmediatelyAndOnRestart() = english {
        for ((choice, title, path) in listOf(
            Triple("Subscription", "Watched fixture", "/watched"),
            Triple("What's hot", "Popular fixture", "/popular"),
            Triple("Toplist", "Toplist fixture", "/toplist.php"),
        )) {
            Library(galleryCount = 45).use { library ->
                val preferences = object : GalleryLaunchPreferences {
                    var value = GalleryLaunchSettings(toplistPeriod = GalleryToplistPeriod.PAST_MONTH)
                    override suspend fun launchSettings() = value
                    override suspend fun setLaunchPage(page: GalleryLaunchPage) {
                        value = value.copy(page = page)
                    }
                    override suspend fun setToplistPeriod(period: GalleryToplistPeriod) {
                        value = value.copy(toplistPeriod = period)
                    }
                }
                View(library, launchPreferences = preferences).use { view ->
                    view.await("Homepage")
                    view.click("Settings")
                    view.await("EH")
                    view.click("EH")
                    view.await("Launch page")
                    view.click("Launch page")
                    view.await(choice)
                    view.click(choice)
                    view.await(title)
                    assertEquals(listOf("/", path), library.requests.map { it.encodedPath })
                    assertNull(view.text("Launch page"))
                }
                View(library, launchPreferences = preferences).use { view ->
                    view.await(title)
                    assertEquals(listOf("/", path, path), library.requests.map { it.encodedPath })
                    when (preferences.value.page) {
                        GalleryLaunchPage.SUBSCRIPTION -> {
                            view.query("followed fixture")
                            view.click("Search")
                            view.await("followed fixture")
                            assertEquals("/watched", library.requests.last().encodedPath)
                            assertEquals("followed fixture", library.requests.last().parameters["f_search"])
                        }
                        GalleryLaunchPage.TOPLIST -> {
                            assertEquals("13", library.requests.last().parameters["tl"])
                            view.click("Toplist")
                            view.await("Yesterday")
                            view.click("Yesterday")
                            view.await("Yesterday") { library.requests.last().parameters["tl"] == "15" }
                            assertEquals(GalleryToplistPeriod.YESTERDAY, preferences.value.toplistPeriod)
                        }
                        else -> Unit
                    }
                }
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

    @Test fun identityActionDiscardsDecodedCoversBeforeReusingTheSameGalleryKey() = english {
        Library(withCovers = true).use { library ->
            View(library).use { view ->
                view.await("Original identity's actual decoded red cover") { library.coverRequests.size == 1 && view.coverPixels(false) > 100 }
                assertEquals(1, library.requests.size)
                view.click("Account")
                view.await("Change owned identity")
                view.click("Change owned identity")
                assertTrue(library.changedIdentity, "The actual identity action must execute before testing cache ownership")
                try {
                    view.await("New identity must fetch and present its actual magenta pixels") { library.coverRequests.size == 2 && view.coverPixels(true) > 100 }
                } catch (failure: Throwable) {
                    view.captureFailure()
                    val texts = view.all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
                    throw AssertionError("Identity callback=${library.changedIdentity}, galleries=${library.requests.size}, covers=${library.coverRequests.size}, red=${view.coverPixels(false)}, magenta=${view.coverPixels(true)}, pauses=${view.identityPauses}, texts=$texts", failure)
                }
                assertEquals(0, view.coverPixels(false), "No old decoded cover may survive the identity action")
                assertEquals(2, library.requests.size)
                assertEquals(2, library.coverRequests.size)
                assertEquals(1, view.identityPauses)
            }
        }
    }

    @Test fun originalEhTogglesChangeRenderedGalleryColorsAndSurviveReopening() = english {
        Library().use { library ->
            val vault = AppearanceTestVault().apply { bytes = """{"schema":1,"mode":"DARK"}""".encodeToByteArray() }
            val black = 0xff000000.toInt()
            View(library, appearance = GalleryAppearanceRepository(vault)).use { view ->
                view.await("Homepage")
                val (originalSurface, originalCategoryPixels) = view.appearancePixels()
                assertTrue(originalSurface != black, "The legacy dark mode must not implicitly enable the black background")
                assertEquals(0, originalCategoryPixels, "The default must retain the harmonized category")
                view.click("Settings")
                view.await("EH")
                view.click("EH")
                view.await("Black dark theme")
                view.click("Black dark theme")
                view.click("Harmonize category color for Dynamic Color")
                view.click("Back")
                view.await("EH")
                view.click("Back")
                view.await("Homepage")
                val (surface, categoryPixels) = view.appearancePixels()
                assertEquals(black, surface, "The normal gallery surface must be pure black")
                assertTrue(categoryPixels > 30, "The visible category must use its original palette color")
            }
            View(library, appearance = GalleryAppearanceRepository(vault)).use { reopened ->
                reopened.await("Homepage")
                val (surface, categoryPixels) = reopened.appearancePixels()
                assertEquals(black, surface)
                assertTrue(categoryPixels > 30)
            }
        }
    }

    @Test fun originalHomeActionsUseTheCommittedListingAndCloseWithoutUnwantedRequests() = english {
        Library().use { library ->
            View(library).use { view ->
                fun awaitAction(label: String) = view.await(label) {
                    view.all().any { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                }
                fun openAction(label: String) {
                    awaitAction("Gallery actions")
                    view.click("Gallery actions")
                    awaitAction(label)
                    view.click(label)
                }
                view.await("Homepage")
                view.query("submitted title")
                view.click("Search")
                view.await("submitted title")
                view.query("unfinished draft")
                openAction("Go to")
                view.await("Cancel")
                view.click("Cancel")
                view.await("date dialog to close") { view.text("Cancel") == null }
                assertEquals(2, library.requests.size)
                openAction("Last page")
                view.await("last-page request") { library.requests.size == 3 }
                assertEquals("1", library.requests.last().parameters["prev"])
                assertEquals("submitted title", library.requests.last().parameters["f_search"])
                view.await("submitted title")
                openAction("Refresh")
                view.await("refresh request") { library.requests.size == 4 }
                assertNull(library.requests.last().parameters["prev"])
                assertNull(library.requests.last().parameters["seek"])
                assertEquals("submitted title", library.requests.last().parameters["f_search"])
            }
        }
    }

    @Test fun originalQuickSearchSavesTheCommittedQueryAndResumesAtTheVisibleGallery() = english {
        val saved = mutableListOf<GalleryQuickSearch>()
        val store = object : GalleryQuickSearchStore {
            override suspend fun list() = saved.toList()
            override suspend fun save(search: GalleryQuickSearch) {
                saved += search.copy(id = 1)
            }
            override suspend fun remove(id: Long) = error("Saving and reopening must preserve the bookmark")
            override suspend fun move(id: Long, before: Long?) = error("Saving and reopening must preserve the bookmark order")
        }
        val preferences = object : GalleryQuickSearchPreferences {
            override suspend fun saveQuickSearchProgress() = true
            override suspend fun setSaveQuickSearchProgress(enabled: Boolean) = error("The unchanged original default needs no write")
        }
        Library().use { library ->
            View(library, quickSearch = GalleryQuickSearchControls(store, preferences)).use { view ->
                view.await("Homepage")
                view.click("Search galleries")
                view.query("saved search")
                view.click("Search")
                view.await("saved search")
                view.clickIconButton("Quick search")
                view.await("Tap \"+\" to add Quick Search")
                view.clickIconButton("Add Quick Search")
                view.await("Save progress")
                view.click("OK")
                view.await("saved search@2")
                assertEquals("saved search", saved.single().keyword)
                view.click("saved search@2")
                view.await("saved shortcut request") { library.requests.size == 3 }
                assertEquals("saved search", library.requests.last().parameters["f_search"])
                assertEquals("2", library.requests.last().parameters["next"])
            }
        }
    }

    @Test fun originalPageCountAndReadingProgressSwitchesChangeActualGalleryCards() = english {
        Library(pageCount = 18).use { library ->
            val preferences = ListingPreferences()
            val saved = mapOf(1L to ReadingPosition(1L, 6, null, 1L))
            View(library, listPreferences = preferences, width = 1000, height = 1160, readingPositions = saved).use { view ->
                fun openEh() {
                    view.click("Settings")
                    view.await("EH")
                    view.click("EH")
                    view.await("Show gallery pages")
                }
                fun leaveEh() {
                    view.click("Back")
                    view.await("EH")
                    view.click("Back")
                    view.await("gallery search bar") {
                        view.all().any { "Search galleries" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                    }
                }
                view.await("18P")
                assertNull(view.text("7/18P"))
                openEh()
                view.click("Show reading progress")
                leaveEh()
                view.await("7/18P")
                openEh()
                view.click("List mode")
                view.await("Thumb")
                view.click("Thumb")
                leaveEh()
                view.await("7/18")
                openEh()
                view.click("Show gallery pages")
                leaveEh()
                assertNull(view.text("7/18"))
                assertNull(view.text("18"))
                assertEquals(1, library.requests.size)
            }
        }
    }

    @Test fun originalListModeAndThumbColumnsChangeTheActualCompactViewport() = english {
        Library(galleryCount = 5).use { library ->
            val preferences = ListingPreferences()
            View(library, listPreferences = preferences, width = 411).use { view ->
                view.await("Homepage")
                val before = view.detailCard("Homepage").boundsInRoot.height
                fun openEh() {
                    view.click("Open navigation")
                    view.await("Settings")
                    view.click("Settings")
                    view.await("EH")
                    view.click("EH")
                    view.await("List mode")
                }
                fun leaveEh() {
                    view.click("Back")
                    view.await("EH")
                    view.click("Back")
                }
                fun dragToLastTick(range: ClosedFloatingPointRange<Float>) {
                    val slider = view.all().single { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == range }
                    val bounds = slider.boundsInRoot
                    view.drag(bounds.center, Offset(bounds.right - 16f, bounds.center.y))
                }
                openEh()
                dragToLastTick(20f..60f)
                assertEquals(60, preferences.value.detailThumbnailSize, "The actual gesture must save the last size")
                leaveEh()
                view.await("Homepage")
                assertEquals(before + 90f, view.detailCard("Homepage").boundsInRoot.height, "The saved size changes the visible detail card")
                openEh()
                view.click("List mode")
                view.await("Thumb")
                view.click("Thumb")
                view.await("Persist thumbnail mode") { preferences.value.mode == GalleryListMode.THUMBNAIL }
                view.await("Detail-only size control disappears") { view.text("Thumb size in detail mode") == null }
                dragToLastTick(1f..10f)
                assertEquals(10, preferences.value.thumbnailColumns, "The actual gesture must save the last column count")
                leaveEh()
                fun cards() = view.all().filter { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.startsWith("Homepage") } }
                view.await("Thumbnail grid presents all five owned cards") { cards().size == 5 }
                val items = cards().map { it.boundsInRoot }
                // Fractional column widths and the card clip round individual edges by one pixel.
                assertTrue(items.maxOf { it.top } - items.minOf { it.top } <= 1f, "All five cards must share the first row: $items")
                assertTrue(items.all { it.width in 34f..36f }, "Ten compact columns must use the original spacing: $items")
                assertEquals(1, library.requests.size, "A layout change must not submit a new site search")
            }
        }
    }
}
