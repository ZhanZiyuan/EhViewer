package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.download.GalleryStorageSettings
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.download.CachedGalleryPages
import com.ehviewer.core.network.download.GalleryDownloadOpen
import com.ehviewer.core.network.download.GalleryDownloads
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchFilters
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.shared.settings.GalleryAppearanceException
import com.ehviewer.shared.settings.GalleryAppearanceStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.GalleryThemeMode
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_appearance_unavailable
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_back_history
import ehviewer.app.shared.generated.resources.gallery_back_search
import ehviewer.app.shared.generated.resources.gallery_download_background
import ehviewer.app.shared.generated.resources.gallery_error_authentication
import ehviewer.app.shared.generated.resources.gallery_error_challenge
import ehviewer.app.shared.generated.resources.gallery_error_local_storage
import ehviewer.app.shared.generated.resources.gallery_error_offline
import ehviewer.app.shared.generated.resources.gallery_error_rate_limit
import ehviewer.app.shared.generated.resources.gallery_error_response
import ehviewer.app.shared.generated.resources.gallery_error_server
import ehviewer.app.shared.generated.resources.gallery_error_session_storage
import ehviewer.app.shared.generated.resources.gallery_error_too_large
import ehviewer.app.shared.generated.resources.gallery_list_unavailable
import ehviewer.app.shared.generated.resources.gallery_next
import ehviewer.app.shared.generated.resources.gallery_no_galleries
import ehviewer.app.shared.generated.resources.gallery_previous
import ehviewer.app.shared.generated.resources.gallery_reload_page
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_search_hint
import ehviewer.app.shared.generated.resources.gallery_sort_page
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val searchFiltersSaver = Saver<GallerySearchFilters, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<GallerySearchFilters>(it) }.getOrNull() },
)

private data class GallerySearchRequest(val query: String, val filters: GallerySearchFilters, val cursor: String? = null, val previous: Boolean = false)

/** Search UI shared by the actual applications; requests run only after an explicit user action. */
@Composable
fun GallerySearchScreen(session: GallerySession, readerState: ReaderStateStore, appearance: GalleryAppearanceStore, history: GalleryHistoryStore, downloads: GalleryDownloads, language: GalleryLanguageSettings, backgroundDownloadsNote: Boolean = false, archiveContent: (@Composable (onBack: () -> Unit) -> Unit)? = null, browserFactory: ((String) -> GalleryLoginBrowser)? = null, storageSettings: GalleryStorageSettings? = null, listPreferences: GalleryListPreferences? = null, onReady: () -> Unit = {}) {
    DisposableEffect(session) { onDispose { session.close() } }
    val library = remember(session, history) {
        object : GalleryLibrary by session {
            override suspend fun details(gid: Long, token: String) = session.details(gid, token).also {
                try {
                    history.record(it)
                } catch (_: GalleryHistoryException) {
                    throw GalleryRequestException(GalleryFailure.LOCAL_STORAGE)
                }
            }
        }
    }
    GalleryLibraryScreen(library, readerState, appearance, history, downloads, language, networkPreferences = session.networkPreferences, storageSettings = storageSettings, listPreferences = listPreferences, backgroundDownloadsNote = backgroundDownloadsNote, archiveContent = archiveContent, accountContent = { changed, back ->
        GalleryAccountScreen(session, browserFactory, changed, back)
    }, onReady = onReady)
}

/** Android uses the same views while its original session/history adapters remain authoritative. */
@Composable
fun GalleryLibraryScreen(
    session: GalleryLibrary,
    readerState: ReaderStateStore,
    appearance: GalleryAppearanceStore,
    history: GalleryHistoryStore,
    downloads: GalleryDownloads,
    language: GalleryLanguageSettings,
    networkPreferences: GalleryNetworkPreferences? = null,
    storageSettings: GalleryStorageSettings? = null,
    listPreferences: GalleryListPreferences? = null,
    onPlatformStorageSettings: (() -> Unit)? = null,
    backgroundDownloadsNote: Boolean = false,
    onPlatformDownloadOpen: ((Long) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    platformTheme: GalleryPlatformTheme? = null,
    accountContent: (@Composable (onIdentityAction: () -> Unit, onBack: () -> Unit) -> Unit)? = null,
    archiveContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    onReady: () -> Unit = {},
) {
    var listDensity by remember(listPreferences) { mutableStateOf<GalleryListDensity?>(null) }
    var listFailed by remember(listPreferences) { mutableStateOf(false) }
    var listAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(listPreferences, listAttempt) {
        listDensity = null
        listFailed = false
        try {
            listDensity = listPreferences?.density() ?: GalleryListDensity.COMPACT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryListPreferencesException) {
            listFailed = true
        }
    }
    var languageTag by remember(language) { mutableStateOf<String?>(null) }
    var languageReady by remember(language) { mutableStateOf(false) }
    var languageFailed by remember(language) { mutableStateOf(false) }
    var languageAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(language, languageAttempt) {
        languageReady = false
        languageFailed = false
        try {
            languageTag = language.tag()
            languageReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AppLanguageException) {
            languageFailed = true
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = searchFiltersSaver) { mutableStateOf(GallerySearchFilters()) }
    var loadedSearch by remember { mutableStateOf<GallerySearchRequest?>(null) }
    var failedSearch by remember { mutableStateOf<GallerySearchRequest?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    var orderName by rememberSaveable { mutableStateOf(GalleryPageOrder.SERVICE.name) }
    val order = GalleryPageOrder.entries.firstOrNull { it.name == orderName } ?: GalleryPageOrder.SERVICE
    var orderOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var archivesOpen by rememberSaveable { mutableStateOf(false) }
    var cached by remember { mutableStateOf<CachedGalleryPages?>(null) }
    var downloadsReady by remember(downloads) { mutableStateOf(false) }
    var downloadsFailed by remember(downloads) { mutableStateOf(false) }
    var downloadsAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(downloads, downloadsAttempt) {
        downloadsFailed = false
        try {
            downloads.initialize()
            downloadsReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            downloadsFailed = true
        }
    }
    var themeMode by remember(appearance) { mutableStateOf<GalleryThemeMode?>(null) }
    var appearanceFailed by remember { mutableStateOf(false) }
    var appearanceAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(appearance, appearanceAttempt) {
        try {
            themeMode = appearance.mode()
            appearanceFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryAppearanceException) {
            appearanceFailed = true
        }
    }
    var page by remember { mutableStateOf<GallerySearchResult?>(null) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<BaseGalleryInfo?>(null) }
    var account by remember { mutableStateOf(false) }
    val resultsState = rememberLazyGridState()
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    // Android marks list cards whose gallery has any download entry.
    val downloadedGids by remember(downloads) { downloads.tasks.map { tasks -> tasks.mapTo(HashSet()) { it.gid } } }.collectAsState(emptySet())
    val coverContext = coil3.compose.LocalPlatformContext.current
    val coverLoader = remember(session, coverContext) { GalleryCoverLoader(coverContext) { session.galleries.cover(it.gid, it.token, it.key) } }
    DisposableEffect(coverLoader) { onDispose { coverLoader.close() } }
    val covers = remember(coverLoader, page) { GalleryCoverCache(load = coverLoader::get) }

    fun load(search: GallerySearchRequest, replaceResults: Boolean = false) {
        request?.cancel()
        val current = ++generation
        if (replaceResults) page = null
        loading = true
        failure = null
        failedSearch = null
        request = scope.launch {
            try {
                val result = session.galleries.search(search.query, search.cursor, search.previous, search.filters)
                if (current == generation) {
                    page = result
                    loadedSearch = search
                    resultsState.requestScrollToItem(0)
                    searchBarOffsetY = 0
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GalleryRequestException) {
                if (current == generation) {
                    failedSearch = search
                    failure = error.failure
                }
            } finally {
                if (current == generation) loading = false
            }
        }
    }

    fun search() = load(GallerySearchRequest(query, filters), replaceResults = true)

    fun turnPage(cursor: String?, previous: Boolean = false) {
        if (cursor != null) loadedSearch?.let { load(it.copy(cursor = cursor, previous = previous)) }
    }

    fun leaveSettings() {
        settingsOpen = false
        appearanceAttempt++
        languageAttempt++
        listAttempt++
    }

    fun openAccount() {
        request?.cancel()
        generation++
        loading = false
        if (onAccount != null) onAccount() else account = true
    }

    val destinations = listOfNotNull(
        GalleryDestination.GALLERIES,
        GalleryDestination.HISTORY,
        GalleryDestination.DOWNLOADS,
        GalleryDestination.LOCAL_LIBRARY.takeIf { archiveContent != null },
        GalleryDestination.ACCOUNT.takeIf { onAccount != null || accountContent != null },
        GalleryDestination.SETTINGS,
    )
    val destination = when {
        settingsOpen -> GalleryDestination.SETTINGS
        account && accountContent != null -> GalleryDestination.ACCOUNT
        archivesOpen && archiveContent != null -> GalleryDestination.LOCAL_LIBRARY
        downloadsOpen -> GalleryDestination.DOWNLOADS
        historyOpen -> GalleryDestination.HISTORY
        else -> GalleryDestination.GALLERIES
    }

    // Like Android's drawer, choosing a destination returns to its root; reselecting pops a child.
    fun navigate(target: GalleryDestination) {
        // Android's original account screen is a separate platform destination.
        if (target == GalleryDestination.ACCOUNT && onAccount != null) return openAccount()
        if (settingsOpen && target != GalleryDestination.SETTINGS) leaveSettings()
        if (target == GalleryDestination.ACCOUNT && !account) openAccount()
        account = target == GalleryDestination.ACCOUNT
        settingsOpen = target == GalleryDestination.SETTINGS
        historyOpen = target == GalleryDestination.HISTORY
        downloadsOpen = target == GalleryDestination.DOWNLOADS
        archivesOpen = target == GalleryDestination.LOCAL_LIBRARY
        selected = null
        cached = null
    }

    DisposableEffect(session) {
        onDispose {
            request?.cancel()
        }
    }
    val theme = themeMode ?: GalleryThemeMode.SYSTEM
    val dark = theme == GalleryThemeMode.DARK || theme == GalleryThemeMode.SYSTEM && isSystemInDarkTheme()
    CompositionLocalProvider(LocalGalleryLanguage provides languageTag) {
        GalleryTheme(dark, platformTheme) {
            Surface(Modifier.fillMaxSize()) {
                GalleryNavigationShell(destinations, destination, ::navigate) { openNavigation ->
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                        val searching = !settingsOpen && !(account && accountContent != null) && !(archivesOpen && archiveContent != null) && !downloadsOpen && selected == null && !historyOpen
                        Column(if (searching) Modifier.fillMaxSize() else Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            if (settingsOpen) {
                                GallerySettingsScreen(networkPreferences, appearance, readerState, themeMode, appearanceFailed, language, languageTag, languageReady, languageFailed, onLanguageChanged = { languageTag = it }, onRetryLanguage = { languageAttempt++ }, onModeChanged = { themeMode = it }, onRetryAppearance = { appearanceAttempt++ }, onBack = ::leaveSettings, storageSettings = storageSettings, onPlatformStorageSettings = onPlatformStorageSettings, listPreferences = listPreferences, listDensity = listDensity, listFailed = listFailed, onListChanged = { listDensity = it }, onRetryList = { listAttempt++ })
                                return@Column
                            }
                            if (account && accountContent != null) {
                                accountContent({
                                    if (downloadsReady) {
                                        scope.launch {
                                            try {
                                                downloads.pauseAll()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                downloadsFailed = true
                                            }
                                        }
                                    }
                                    request?.cancel()
                                    generation++
                                    selected = null
                                    page = null
                                    query = ""
                                    loadedSearch = null
                                    failedSearch = null
                                    failure = null
                                    loading = false
                                }, { account = false })
                                return@Column
                            }
                            val gallery = selected
                            if (archivesOpen && archiveContent != null) {
                                archiveContent { archivesOpen = false }
                                return@Column
                            }
                            if (downloadsOpen) {
                                val opened = cached
                                if (opened != null) {
                                    GalleryCachedReaderScreen(session, opened, readerState, onBack = { cached = null })
                                } else {
                                    GalleryDownloadsScreen(downloads, downloadsReady, downloadsFailed, retry = { downloadsAttempt++ }, backgroundNote = if (backgroundDownloadsNote) galleryString(Res.string.gallery_download_background) else null, onOpen = {
                                        when (it) {
                                            is GalleryDownloadOpen.Cached -> {
                                                history.touch(it.pages.gallery.gid)
                                                cached = it.pages
                                            }
                                            is GalleryDownloadOpen.PlatformReader -> checkNotNull(onPlatformDownloadOpen)(it.gid)
                                        }
                                    }, onBack = { downloadsOpen = false })
                                }
                                return@Column
                            }
                            if (gallery != null) {
                                GalleryDetailScreen(session, gallery, readerState, downloads.takeIf { downloadsReady }, onDownloads = { downloadsOpen = true }, backLabel = if (historyOpen) galleryString(Res.string.gallery_back_history) else galleryString(Res.string.gallery_back_search), onBack = { selected = null })
                                return@Column
                            }
                            if (historyOpen) {
                                GalleryHistoryScreen(history, onOpen = { selected = it }, onBack = { historyOpen = false })
                                return@Column
                            }
                            if (filtersOpen) {
                                GallerySearchFiltersDialog(filters, onApply = { applied ->
                                    filters = applied
                                    filtersOpen = false
                                    search()
                                }, onDismiss = { filtersOpen = false })
                            }
                            if (orderOpen) {
                                GalleryPageOrderDialog(order, onApply = {
                                    orderName = it.name
                                    orderOpen = false
                                    resultsState.requestScrollToItem(0)
                                    searchBarOffsetY = 0
                                }, onDismiss = { orderOpen = false })
                            }
                            val result = page
                            val error = failure
                            // SearchBarScreen: list content scrolls beneath a floating search bar that slides away with it.
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                val window = LocalWindowInfo.current.containerSize
                                val density = LocalDensity.current
                                val marginH = with(density) { galleryListMarginH(window.width.toDp(), window.height.toDp()) }
                                val barSpace = SearchBarDefaults.InputFieldHeight + 16.dp
                                val barSpacePx = with(density) { barSpace.roundToPx() }
                                val barConnection = remember(barSpacePx) {
                                    object : NestedScrollConnection {
                                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                            searchBarOffsetY = (searchBarOffsetY + consumed.y).roundToInt().coerceIn(-barSpacePx, 0)
                                            return Offset.Zero
                                        }
                                    }
                                }
                                if (result != null && result.galleryInfoList.isNotEmpty()) {
                                    val orderedGalleries = remember(result, order) { order.arrange(result.galleryInfoList) }
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive((listDensity ?: GalleryListDensity.COMPACT).minimumColumnWidth.dp),
                                        modifier = Modifier.fillMaxSize().nestedScroll(barConnection),
                                        state = resultsState,
                                        contentPadding = PaddingValues(start = marginH, top = barSpace + GalleryListMarginV, end = marginH, bottom = GalleryListMarginV),
                                        horizontalArrangement = Arrangement.spacedBy(GalleryListInterval),
                                        verticalArrangement = Arrangement.spacedBy(GalleryListInterval),
                                    ) {
                                        items(orderedGalleries, key = { it.gid }) { gallery ->
                                            GallerySearchCard(gallery, covers, downloaded = gallery.gid in downloadedGids) { selected = gallery }
                                        }
                                    }
                                }
                                // GalleryList: a refresh covers the list with a centered indicator; failures and empty pages show its ErrorTip.
                                when {
                                    loading -> Surface(Modifier.fillMaxSize()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                    }
                                    error != null && result == null -> Surface(Modifier.fillMaxSize()) {
                                        val retry = failedSearch
                                        GalleryErrorTip(error.userMessage(), Modifier.widthIn(max = 228.dp).clip(ShapeDefaults.Small).clickable(enabled = retry != null, onClickLabel = galleryString(Res.string.gallery_retry), role = Role.Button) { retry?.let { load(it) } })
                                    }
                                    result == null -> Box(Modifier.fillMaxSize().padding(horizontal = marginH), contentAlignment = Alignment.Center) {
                                        Text(galleryString(Res.string.gallery_search_hint), Modifier.widthIn(max = 320.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                                    }
                                    result.galleryInfoList.isEmpty() -> GalleryErrorTip(galleryString(Res.string.gallery_no_galleries), Modifier.widthIn(max = 228.dp))
                                }
                                val offset = Modifier.offset { IntOffset(0, searchBarOffsetY) }
                                GallerySearchBar(
                                    query = query,
                                    onQueryChange = { query = it.take(4096) },
                                    onSearch = { search() },
                                    maxWidth = maxWidth,
                                    filtered = filters != GallerySearchFilters(),
                                    onFilters = { filtersOpen = true },
                                    leading = {
                                        // Like Android's list, phones lead with the drawer; system Back still leaves an embedded screen.
                                        when {
                                            openNavigation != null -> GalleryNavigationButton(openNavigation)
                                            onBack != null -> IconButton(onClick = onBack) { Icon(GalleryIcons.ArrowBack, contentDescription = galleryString(Res.string.gallery_back)) }
                                            else -> Icon(GalleryIcons.Search, contentDescription = null)
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.TopCenter).then(offset),
                                )
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                if (appearanceFailed) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(galleryString(Res.string.gallery_appearance_unavailable), Modifier.weight(1f))
                                        TextButton(onClick = { appearanceAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
                                    }
                                }
                                if (listFailed) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(galleryString(Res.string.gallery_list_unavailable), Modifier.weight(1f))
                                        TextButton(onClick = { listAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
                                    }
                                }
                                // A failed page turn keeps the loaded page, like a failed Paging append.
                                if (error != null && result != null) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(error.userMessage(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                        TextButton(onClick = { failedSearch?.let { load(it) } }, enabled = failedSearch != null && !loading) { Text(galleryString(Res.string.gallery_retry)) }
                                    }
                                }
                                if (result != null) {
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { turnPage(result.prev, previous = true) }, enabled = result.prev != null && !loading) { Text(galleryString(Res.string.gallery_previous)) }
                                        TextButton(onClick = { loadedSearch?.let { load(it) } }, enabled = loadedSearch != null && !loading) { Text(galleryString(Res.string.gallery_reload_page)) }
                                        TextButton(onClick = { orderOpen = true }, enabled = !loading) {
                                            Icon(GalleryIcons.Sort, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                                            Text(galleryString(Res.string.gallery_sort_page), Modifier.padding(start = ButtonDefaults.IconSpacing))
                                        }
                                        TextButton(onClick = { turnPage(result.next) }, enabled = result.next != null && !loading) { Text(galleryString(Res.string.gallery_next)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (languageReady || languageFailed) SideEffect(onReady)
}

@Composable
internal fun GalleryFailure.userMessage(): String = when (this) {
    GalleryFailure.AUTHENTICATION -> galleryString(Res.string.gallery_error_authentication)
    GalleryFailure.CHALLENGE -> galleryString(Res.string.gallery_error_challenge)
    GalleryFailure.RATE_LIMIT -> galleryString(Res.string.gallery_error_rate_limit)
    GalleryFailure.OFFLINE -> galleryString(Res.string.gallery_error_offline)
    GalleryFailure.SERVER -> galleryString(Res.string.gallery_error_server)
    GalleryFailure.INVALID_RESPONSE -> galleryString(Res.string.gallery_error_response)
    GalleryFailure.RESPONSE_TOO_LARGE -> galleryString(Res.string.gallery_error_too_large)
    GalleryFailure.SESSION_STORAGE -> galleryString(Res.string.gallery_error_session_storage)
    GalleryFailure.LOCAL_STORAGE -> galleryString(Res.string.gallery_error_local_storage)
}
