package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.domain.GalleryLaunchPage
import com.ehviewer.core.domain.GalleryLaunchPreferences
import com.ehviewer.core.domain.GalleryLaunchPreferencesException
import com.ehviewer.core.domain.GalleryLaunchSettings
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListMode
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.GalleryListPresentationPreferences
import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.GalleryToplistPeriod
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
import com.ehviewer.shared.settings.GalleryAppearancePreferences
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
import ehviewer.app.shared.generated.resources.gallery_error_no_watched_tags
import ehviewer.app.shared.generated.resources.gallery_error_offline
import ehviewer.app.shared.generated.resources.gallery_error_query
import ehviewer.app.shared.generated.resources.gallery_error_rate_limit
import ehviewer.app.shared.generated.resources.gallery_error_response
import ehviewer.app.shared.generated.resources.gallery_error_server
import ehviewer.app.shared.generated.resources.gallery_error_session_storage
import ehviewer.app.shared.generated.resources.gallery_error_too_large
import ehviewer.app.shared.generated.resources.gallery_feed_toplist
import ehviewer.app.shared.generated.resources.gallery_filters
import ehviewer.app.shared.generated.resources.gallery_filters_applied
import ehviewer.app.shared.generated.resources.gallery_launch_preferences_unavailable
import ehviewer.app.shared.generated.resources.gallery_list_unavailable
import ehviewer.app.shared.generated.resources.gallery_no_galleries
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_search
import ehviewer.app.shared.generated.resources.gallery_search_galleries
import ehviewer.app.shared.generated.resources.gallery_search_hint
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val searchFiltersSaver = Saver<GallerySearchFilters, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<GallerySearchFilters>(it) }.getOrNull() },
)

@Serializable
private data class GallerySearchRequest(
    val query: String,
    val filters: GallerySearchFilters,
    val cursor: String? = null,
    val previous: Boolean = false,
    val uploader: String? = null,
    val tag: String? = null,
    val feed: GalleryLaunchPage? = null,
    val toplistPeriod: GalleryToplistPeriod = GalleryToplistPeriod.ALL_TIME,
    val range: Int = 0,
    val seekDate: String? = null,
)

private val searchRequestSaver = Saver<GallerySearchRequest, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<GallerySearchRequest>(it) }.getOrNull() },
)

/** Product entry shared by Desktop/iOS, loading the homepage when online browsing is entered. */
@Composable
fun GallerySearchScreen(
    session: GallerySession,
    readerState: ReaderStateStore,
    appearance: GalleryAppearanceStore,
    history: GalleryHistoryStore,
    downloads: GalleryDownloads,
    language: GalleryLanguageSettings,
    backgroundDownloadsNote: Boolean = false,
    archiveContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    browserFactory: ((String) -> GalleryLoginBrowser)? = null,
    storageSettings: GalleryStorageSettings? = null,
    listPreferences: GalleryListPreferences? = null,
    presentationPreferences: GalleryListPresentationPreferences? = null,
    launchPreferences: GalleryLaunchPreferences? = null,
    favoriteControls: GalleryFavoriteControls? = null,
    initialLocalLibrary: Boolean = false,
    buildInfo: GalleryBuildInfo? = null,
    onReady: () -> Unit = {},
) {
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
    }, presentationPreferences = presentationPreferences, launchPreferences = launchPreferences, favoriteControls = favoriteControls, initialLocalLibrary = initialLocalLibrary, browseOnEntry = true, restoreLaunchPage = true, buildInfo = buildInfo, onReady = onReady)
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
    presentationPreferences: GalleryListPresentationPreferences? = null,
    launchPreferences: GalleryLaunchPreferences? = null,
    favoriteControls: GalleryFavoriteControls? = null,
    backgroundDownloadsNote: Boolean = false,
    onPlatformDownloadOpen: ((Long) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    platformTheme: GalleryPlatformTheme? = null,
    accountContent: (@Composable (onIdentityAction: () -> Unit, onBack: () -> Unit) -> Unit)? = null,
    archiveContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    initialLocalLibrary: Boolean = false,
    browseOnEntry: Boolean = false,
    restoreLaunchPage: Boolean = false,
    buildInfo: GalleryBuildInfo? = null,
    onPlatformSettings: ((GallerySettingsPage) -> Boolean)? = null,
    onReady: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var listDensity by remember(listPreferences) { mutableStateOf<GalleryListDensity?>(null) }
    var showComments by remember(listPreferences) { mutableStateOf<Boolean?>(null) }
    var presentation by remember(presentationPreferences) { mutableStateOf<GalleryListPresentation?>(null) }
    var listFailed by remember(listPreferences) { mutableStateOf(false) }
    var listAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(listPreferences, presentationPreferences, listAttempt) {
        listDensity = null
        showComments = null
        listFailed = false
        try {
            val density = listPreferences?.density() ?: GalleryListDensity.COMPACT
            val comments = listPreferences?.showComments() ?: true
            val view = presentationPreferences?.presentation() ?: GalleryListPresentation()
            listDensity = density
            showComments = comments
            presentation = view
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryListPreferencesException) {
            listFailed = true
        }
    }
    var launchSettings by remember(launchPreferences) { mutableStateOf<GalleryLaunchSettings?>(null) }
    var launchReadFailed by remember(launchPreferences) { mutableStateOf(false) }
    var launchSaveFailed by remember(launchPreferences) { mutableStateOf(false) }
    var launchSaving by remember(launchPreferences) { mutableStateOf(false) }
    var launchAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(launchPreferences, launchAttempt) {
        try {
            launchSettings = launchPreferences?.launchSettings() ?: GalleryLaunchSettings()
            launchReadFailed = false
            launchSaveFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryLaunchPreferencesException) {
            launchReadFailed = true
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
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var favoritesOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var archivesOpen by rememberSaveable { mutableStateOf(initialLocalLibrary && archiveContent != null) }
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
    var appearancePreferences by remember(appearance) { mutableStateOf<GalleryAppearancePreferences?>(null) }
    var appearanceFailed by remember { mutableStateOf(false) }
    var appearanceAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(appearance, appearanceAttempt) {
        try {
            appearancePreferences = appearance.preferences()
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
    var merging by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var failedMerge by remember { mutableStateOf(false) }
    var consumedCursors by remember { mutableStateOf(emptySet<Pair<String, Boolean>>()) }
    var entryStarted by remember(session) { mutableStateOf(false) }
    // Restore the submitted route, rather than accidentally submitting an unfinished draft.
    var entrySearch by rememberSaveable(stateSaver = searchRequestSaver) { mutableStateOf(GallerySearchRequest("", GallerySearchFilters())) }
    val scope = rememberCoroutineScope()
    fun saveLaunch(change: suspend GalleryLaunchPreferences.(GalleryLaunchSettings) -> GalleryLaunchSettings, onSaved: (GalleryLaunchSettings) -> Unit = {}) {
        val preferences = launchPreferences ?: return
        val current = launchSettings ?: return
        if (launchSaving || launchReadFailed || launchSaveFailed) return
        launchSaving = true
        scope.launch {
            try {
                val saved = preferences.change(current)
                launchSettings = saved
                onSaved(saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GalleryLaunchPreferencesException) {
                launchSaveFailed = true
            } finally {
                launchSaving = false
            }
        }
    }
    var selected by remember { mutableStateOf<BaseGalleryInfo?>(null) }
    var account by remember { mutableStateOf(false) }
    val resultsState = rememberLazyGridState()
    val thumbnailState = rememberLazyStaggeredGridState()
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    var homeActionsHidden by remember { mutableStateOf(false) }
    // Android marks list cards whose gallery has any download entry.
    val downloadedGids by remember(downloads) { downloads.tasks.map { tasks -> tasks.mapTo(HashSet()) { it.gid } } }.collectAsState(emptySet())
    val coverContext = coil3.compose.LocalPlatformContext.current
    // Keep one pipeline across cursor merges, but discard it on replacement routes and the
    // existing identity callback's loadedSearch=null transition, cancelling old decode work.
    val coverRoute = loadedSearch?.copy(cursor = null, previous = false, range = 0, seekDate = null)
    val coverLoader = remember(session, coverContext, coverRoute) { GalleryCoverLoader(coverContext) { session.galleries.cover(it.gid, it.token, it.key) } }
    DisposableEffect(coverLoader) { onDispose { coverLoader.close() } }
    val covers = remember(coverLoader) { GalleryCoverCache(load = coverLoader::get) }
    val historyCovers = remember(coverLoader, historyOpen) { GalleryCoverCache(load = coverLoader::get) }
    val favoriteCovers = remember(coverLoader, favoritesOpen) { GalleryCoverCache(load = coverLoader::get) }

    fun load(search: GallerySearchRequest, replaceResults: Boolean = false, mergeResults: Boolean = false, refreshGesture: Boolean = false) {
        entryStarted = true
        entrySearch = if (search.feed == null) search.copy(feed = GalleryLaunchPage.HOMEPAGE) else search
        request?.cancel()
        val current = ++generation
        val before = page
        if (!mergeResults) consumedCursors = emptySet()
        if (replaceResults) page = null
        loading = true
        merging = mergeResults
        refreshing = refreshGesture
        failedMerge = false
        failure = null
        failedSearch = null
        request = scope.launch {
            try {
                val result = search.tag?.let { session.galleries.tag(it, search.cursor, search.previous, search.filters, search.range, search.seekDate) }
                    ?: search.uploader?.let { session.galleries.uploader(it, search.cursor, search.previous, search.filters, search.range, search.seekDate) }
                    ?: when (search.feed) {
                        GalleryLaunchPage.SUBSCRIPTION -> session.galleries.subscription(search.query, search.cursor, search.previous, search.filters, search.range, search.seekDate)
                        GalleryLaunchPage.WHATS_HOT -> session.galleries.whatsHot()
                        GalleryLaunchPage.TOPLIST -> {
                            val number = search.cursor?.let { cursor ->
                                cursor.toIntOrNull()?.takeIf { it in 1..200 } ?: throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
                            } ?: 1
                            session.galleries.toplist(search.toplistPeriod, number)
                        }
                        GalleryLaunchPage.HOMEPAGE, null -> session.galleries.search(search.query, search.cursor, search.previous, search.filters, search.range, search.seekDate)
                    }
                if (current == generation) {
                    page = if (mergeResults && before != null) {
                        val cursor = requireNotNull(search.cursor)
                        mergeGalleryBrowsePages(before, result, cursor, search.previous, consumedCursors).also {
                            consumedCursors = consumedCursors + (cursor to search.previous)
                        }
                    } else {
                        result
                    }
                    loadedSearch = search
                    if (!mergeResults) {
                        resultsState.requestScrollToItem(0)
                        thumbnailState.requestScrollToItem(0)
                        searchBarOffsetY = 0
                        homeActionsHidden = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GalleryRequestException) {
                if (current == generation) {
                    failedSearch = search
                    failedMerge = mergeResults
                    failure = error.failure
                }
            } finally {
                if (current == generation) {
                    loading = false
                    refreshing = false
                }
            }
        }
    }

    fun search() {
        // Match Android's collapsed search: the submitted query leaves editing mode.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        val routed = (failedSearch ?: loadedSearch)?.takeIf { it.query == query }
        val currentFeed = (failedSearch ?: loadedSearch)?.feed
        load(GallerySearchRequest(query, filters, uploader = routed?.uploader, tag = routed?.tag, feed = currentFeed?.takeIf { it == GalleryLaunchPage.SUBSCRIPTION }), replaceResults = true)
    }

    fun turnPage(cursor: String?, previous: Boolean = false) {
        if (cursor != null) loadedSearch?.let { load(it.copy(cursor = cursor, previous = previous, range = 0, seekDate = null), mergeResults = true) }
    }

    fun retryPage() {
        failedSearch?.let { load(it, mergeResults = failedMerge) }
    }

    fun refresh() = loadedSearch?.let { load(it.copy(cursor = null, previous = false, range = 0, seekDate = null), refreshGesture = true) }

    fun leaveSettings() {
        settingsOpen = false
        appearanceAttempt++
        languageAttempt++
        listAttempt++
    }

    fun openAccount() {
        // Returning from an account page must resume an initial/replacement load that it cancelled.
        if (loading && page == null) entryStarted = false
        request?.cancel()
        generation++
        loading = false
        refreshing = false
        if (onAccount != null) onAccount() else account = true
    }

    val destinations = listOfNotNull(
        GalleryDestination.GALLERIES,
        GalleryDestination.FAVORITES.takeIf { favoriteControls != null },
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
        favoritesOpen && favoriteControls != null -> GalleryDestination.FAVORITES
        historyOpen -> GalleryDestination.HISTORY
        else -> GalleryDestination.GALLERIES
    }

    // The original HomePageScreen immediately collects its default Pager. Embedded search
    // may opt in; opening a local document stays offline until Galleries is actually chosen.
    LaunchedEffect(session, browseOnEntry, destination, launchSettings, restoreLaunchPage) {
        if (browseOnEntry && destination == GalleryDestination.GALLERIES && !entryStarted) {
            val entry = if (entrySearch.feed == null && restoreLaunchPage && launchPreferences != null) {
                val saved = launchSettings ?: return@LaunchedEffect
                entrySearch.copy(feed = saved.page, toplistPeriod = saved.toplistPeriod)
            } else {
                entrySearch
            }
            load(entry, replaceResults = true)
        }
    }

    // Like Android's drawer, choosing a destination returns to its root; reselecting pops a child.
    fun navigate(target: GalleryDestination) {
        // Android's original account screen is a separate platform destination.
        if (target == GalleryDestination.ACCOUNT && onAccount != null) return openAccount()
        if (settingsOpen && target != GalleryDestination.SETTINGS) leaveSettings()
        if (target == GalleryDestination.ACCOUNT && !account) openAccount()
        account = target == GalleryDestination.ACCOUNT
        settingsOpen = target == GalleryDestination.SETTINGS
        favoritesOpen = target == GalleryDestination.FAVORITES
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
    val theme = appearancePreferences?.mode ?: GalleryThemeMode.SYSTEM
    val dark = theme == GalleryThemeMode.DARK || theme == GalleryThemeMode.SYSTEM && isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalGalleryLanguage provides languageTag,
        LocalGalleryAppearance provides (appearancePreferences ?: GalleryAppearancePreferences()),
    ) {
        GalleryTheme(dark, platformTheme) {
            Surface(Modifier.fillMaxSize()) {
                GalleryNavigationShell(destinations, destination, ::navigate) { openNavigation ->
                    val immersive = galleryIsImmersive()
                    Box(Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)), contentAlignment = Alignment.TopCenter) {
                        // Search, History and Downloads use Android's floating search bar.
                        val listPresentation = presentation ?: GalleryListPresentation()
                        val localFavorites = favoritesOpen && favoriteControls != null
                        val searchBarPage = !settingsOpen && !localFavorites && !(account && accountContent != null) && !(archivesOpen && archiveContent != null) && (if (downloadsOpen) cached == null else selected == null)
                        Column(galleryPageModifier(searchBarPage, immersive, edgeToEdge = settingsOpen || localFavorites || selected != null)) {
                            if (settingsOpen) {
                                GallerySettingsScreen(
                                    networkPreferences, appearance, appearancePreferences, appearanceFailed,
                                    language, languageTag, languageReady, languageFailed,
                                    onLanguageChanged = { languageTag = it },
                                    onRetryLanguage = { languageAttempt++ },
                                    onAppearanceChanged = { appearancePreferences = it },
                                    onRetryAppearance = { appearanceAttempt++ },
                                    onBack = ::leaveSettings,
                                    favoriteSettings = favoriteControls?.let { controls ->
                                        { GalleryDefaultFavoriteSettings(controls.preferences, session.favorites) }
                                    },
                                    launchSettings = launchPreferences?.let {
                                        {
                                            GalleryLaunchSettingsRow(launchSettings?.page, launchReadFailed || launchSaveFailed, launchSaving, onSelect = { page ->
                                                saveLaunch({ current ->
                                                    setLaunchPage(page)
                                                    current.copy(page = page)
                                                }, onSaved = { saved ->
                                                    // Android's original navigation graph changes its root immediately.
                                                    if (restoreLaunchPage) {
                                                        navigate(GalleryDestination.GALLERIES)
                                                        query = ""
                                                        filters = GallerySearchFilters()
                                                        load(GallerySearchRequest(query, filters, feed = saved.page, toplistPeriod = saved.toplistPeriod), replaceResults = true)
                                                    }
                                                })
                                            }, onRetry = { launchAttempt++ })
                                        }
                                    },
                                    storageSettings = storageSettings,
                                    listPreferences = listPreferences,
                                    listDensity = listDensity,
                                    listFailed = listFailed,
                                    onListChanged = { listDensity = it },
                                    presentationPreferences = presentationPreferences,
                                    presentation = presentation,
                                    onPresentationChanged = { presentation = it },
                                    showComments = showComments,
                                    onCommentsChanged = { showComments = it },
                                    onRetryList = { listAttempt++ },
                                    onAccount = { navigate(GalleryDestination.ACCOUNT) }.takeIf { onAccount != null || accountContent != null },
                                    onHistory = { navigate(GalleryDestination.HISTORY) },
                                    buildInfo = buildInfo,
                                    onPlatformSettings = onPlatformSettings,
                                )
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
                                    refreshing = false
                                    entryStarted = false
                                    entrySearch = GallerySearchRequest("", filters)
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
                                                history.recordDownloaded(it.pages.gallery)
                                                cached = it.pages
                                            }
                                            is GalleryDownloadOpen.PlatformReader -> checkNotNull(onPlatformDownloadOpen)(it.gid)
                                        }
                                    }, onBack = { downloadsOpen = false }, openNavigation = openNavigation)
                                }
                                return@Column
                            }
                            if (gallery != null) {
                                val detailCovers = when {
                                    localFavorites -> favoriteCovers
                                    historyOpen -> historyCovers
                                    else -> covers
                                }
                                GalleryDetailScreen(session, gallery, readerState, downloads.takeIf { downloadsReady }, covers = detailCovers, showComments = showComments == true, favoriteControls = favoriteControls, onDownloads = { downloadsOpen = true }, onCategory = {
                                    selected = null
                                    historyOpen = false
                                    favoritesOpen = false
                                    filters = GallerySearchFilters(includedCategories = it)
                                    query = ""
                                    load(GallerySearchRequest(query, filters), replaceResults = true)
                                }, onUploader = {
                                    selected = null
                                    historyOpen = false
                                    favoritesOpen = false
                                    filters = GallerySearchFilters()
                                    query = "uploader:$it"
                                    load(GallerySearchRequest(query, filters, uploader = it), replaceResults = true)
                                }, onTag = {
                                    selected = null
                                    historyOpen = false
                                    favoritesOpen = false
                                    filters = GallerySearchFilters()
                                    query = it
                                    load(GallerySearchRequest(query, filters, tag = it), replaceResults = true)
                                }, onSimilar = { related ->
                                    selected = null
                                    historyOpen = false
                                    favoritesOpen = false
                                    filters = GallerySearchFilters()
                                    query = related.query
                                    load(GallerySearchRequest(query, filters, uploader = related.uploader, tag = related.tag), replaceResults = true)
                                }, backLabel = if (historyOpen) galleryString(Res.string.gallery_back_history) else galleryString(Res.string.gallery_back_search), onBack = { selected = null })
                                return@Column
                            }
                            if (localFavorites && favoriteControls != null) {
                                GalleryLocalFavoritesScreen(favoriteControls.local, favoriteCovers, downloadedGids, listPresentation, readerState, openNavigation, onOpen = { selected = it }, onBack = { favoritesOpen = false })
                                return@Column
                            }
                            if (historyOpen) {
                                GalleryHistoryScreen(history, historyCovers, downloadedGids, listPresentation, readerState, leading = {
                                    if (openNavigation != null) GalleryNavigationButton(openNavigation) else Icon(GalleryIcons.Search, contentDescription = null)
                                }, onOpen = { selected = it }, onBack = { historyOpen = false })
                                return@Column
                            }
                            if (filtersOpen) {
                                GallerySearchFiltersDialog(filters, onApply = { applied ->
                                    filters = applied
                                    filtersOpen = false
                                    search()
                                }, onDismiss = { filtersOpen = false })
                            }
                            val result = page
                            val error = failure
                            val thumbnails = presentation?.mode == GalleryListMode.THUMBNAIL
                            val pendingCursor by remember(resultsState, thumbnailState, result, thumbnails) {
                                derivedStateOf {
                                    if (thumbnails) {
                                        val layout = thumbnailState.layoutInfo
                                        val first = layout.visibleItemsInfo.minByOrNull { it.index }
                                        val last = layout.visibleItemsInfo.maxOfOrNull { it.index }
                                        galleryVisibleCursor(result, layout.totalItemsCount, first?.index, first?.key, last)
                                    } else {
                                        val layout = resultsState.layoutInfo
                                        val first = layout.visibleItemsInfo.firstOrNull()
                                        galleryVisibleCursor(result, layout.totalItemsCount, first?.index, first?.key, layout.visibleItemsInfo.lastOrNull()?.index)
                                    }
                                }
                            }
                            LaunchedEffect(pendingCursor, loading, error) {
                                if (!loading && error == null) pendingCursor?.let { (cursor, previous) -> turnPage(cursor, previous) }
                            }
                            val refreshState = rememberPullToRefreshState()
                            // SearchBarScreen: list content scrolls beneath a floating search bar that slides away with it.
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().pullToRefresh(isRefreshing = refreshing, state = refreshState, enabled = !loading, onRefresh = { refresh() })) {
                                val window = LocalWindowInfo.current.containerSize
                                val density = LocalDensity.current
                                val marginH = with(density) { galleryListMarginH(window.width.toDp(), window.height.toDp()) }
                                val barSpace = SearchBarDefaults.InputFieldHeight + 16.dp
                                val barSpacePx = with(density) { barSpace.roundToPx() }
                                val touchSlop = LocalViewConfiguration.current.touchSlop
                                val barConnection = remember(barSpacePx, touchSlop) {
                                    object : NestedScrollConnection {
                                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                            if (consumed.y <= -touchSlop) homeActionsHidden = true
                                            if (consumed.y >= touchSlop / 2) homeActionsHidden = false
                                            searchBarOffsetY = (searchBarOffsetY + consumed.y).roundToInt().coerceIn(-barSpacePx, 0)
                                            return Offset.Zero
                                        }
                                    }
                                }
                                if (result != null && result.galleryInfoList.isNotEmpty()) {
                                    if (thumbnails) {
                                        val columns = presentation?.thumbnailColumns ?: 3
                                        LazyVerticalStaggeredGrid(
                                            columns = if (maxWidth < 600.dp) StaggeredGridCells.Fixed(columns) else StaggeredGridCells.Adaptive((411f / columns).dp),
                                            modifier = Modifier.fillMaxSize().nestedScroll(barConnection),
                                            state = thumbnailState,
                                            contentPadding = PaddingValues(start = marginH, top = barSpace + GalleryListMarginV, end = marginH, bottom = GalleryListMarginV),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalItemSpacing = 4.dp,
                                        ) {
                                            if ((merging && loading || failedMerge && error != null) && entrySearch.previous) {
                                                item(span = StaggeredGridItemSpan.FullLine) { GalleryBrowseLoadState(loading, error, ::retryPage) }
                                            }
                                            items(result.galleryInfoList, key = { it.gid }) { gallery ->
                                                GalleryThumbnailCard(gallery, covers, presentation = listPresentation, readerState = readerState) { selected = gallery }
                                            }
                                            if ((merging && loading || failedMerge && error != null) && !entrySearch.previous) {
                                                item(span = StaggeredGridItemSpan.FullLine) { GalleryBrowseLoadState(loading, error, ::retryPage) }
                                            }
                                        }
                                    } else {
                                        LazyVerticalGrid(
                                            columns = GridCells.Adaptive((listDensity ?: GalleryListDensity.COMPACT).minimumColumnWidth.dp),
                                            modifier = Modifier.fillMaxSize().nestedScroll(barConnection),
                                            state = resultsState,
                                            contentPadding = PaddingValues(start = marginH, top = barSpace + GalleryListMarginV, end = marginH, bottom = GalleryListMarginV),
                                            horizontalArrangement = Arrangement.spacedBy(GalleryListInterval),
                                            verticalArrangement = Arrangement.spacedBy(GalleryListInterval),
                                        ) {
                                            if ((merging && loading || failedMerge && error != null) && entrySearch.previous) {
                                                item(span = { GridItemSpan(maxLineSpan) }) { GalleryBrowseLoadState(loading, error, ::retryPage) }
                                            }
                                            items(result.galleryInfoList, key = { it.gid }) { gallery ->
                                                GallerySearchCard(gallery, covers, downloaded = gallery.gid in downloadedGids, thumbnailSize = listPresentation.detailThumbnailSize, presentation = listPresentation, readerState = readerState) { selected = gallery }
                                            }
                                            if ((merging && loading || failedMerge && error != null) && !entrySearch.previous) {
                                                item(span = { GridItemSpan(maxLineSpan) }) { GalleryBrowseLoadState(loading, error, ::retryPage) }
                                            }
                                        }
                                    }
                                }
                                // GalleryList: a refresh covers the list with a centered indicator; failures and empty pages show its ErrorTip.
                                when {
                                    loading && !merging && !refreshing -> Surface(Modifier.fillMaxSize()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularWavyProgressIndicator() }
                                    }
                                    error != null && !failedMerge -> Surface(Modifier.fillMaxSize()) {
                                        val retry = failedSearch
                                        GalleryErrorTip(error.userMessage(), Modifier.widthIn(max = 228.dp).clip(ShapeDefaults.Small).clickable(enabled = retry != null, onClickLabel = galleryString(Res.string.gallery_retry), role = Role.Button) { retryPage() })
                                    }
                                    result == null -> Box(Modifier.fillMaxSize().padding(horizontal = marginH), contentAlignment = Alignment.Center) {
                                        Text(galleryString(Res.string.gallery_search_hint), Modifier.widthIn(max = 320.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                                    }
                                    result.galleryInfoList.isEmpty() -> GalleryErrorTip(galleryString(Res.string.gallery_no_galleries), Modifier.widthIn(max = 228.dp))
                                }
                                PullToRefreshDefaults.LoadingIndicator(state = refreshState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = barSpace).offset { IntOffset(0, searchBarOffsetY) })
                                val offset = Modifier.offset { IntOffset(0, searchBarOffsetY) }
                                val feed = entrySearch.feed ?: GalleryLaunchPage.HOMEPAGE
                                val feedTitle = when (feed) {
                                    GalleryLaunchPage.HOMEPAGE -> "EhViewer"
                                    GalleryLaunchPage.TOPLIST -> galleryToplistPeriodLabel(entrySearch.toplistPeriod)
                                    else -> galleryLaunchPageLabel(feed)
                                }
                                GallerySearchBar(
                                    query = query,
                                    onQueryChange = { query = it.take(4096) },
                                    onSearch = { search() },
                                    maxWidth = maxWidth,
                                    title = feedTitle,
                                    hint = galleryString(Res.string.gallery_search_galleries),
                                    leading = {
                                        // Like Android's list, phones lead with the drawer; system Back still leaves an embedded screen.
                                        when {
                                            openNavigation != null -> GalleryNavigationButton(openNavigation)
                                            onBack != null -> IconButton(onClick = onBack) { Icon(GalleryIcons.ArrowBack, contentDescription = galleryString(Res.string.gallery_back)) }
                                            else -> Icon(GalleryIcons.Search, contentDescription = null)
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.TopCenter).then(offset),
                                ) {
                                    if (feed == GalleryLaunchPage.TOPLIST && launchPreferences != null) {
                                        var periodOpen by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { periodOpen = true }, enabled = !launchSaving && !launchReadFailed && !launchSaveFailed) {
                                                Icon(GalleryIcons.MoreVert, contentDescription = galleryString(Res.string.gallery_feed_toplist))
                                            }
                                            DropdownMenu(periodOpen, onDismissRequest = { periodOpen = false }) {
                                                GalleryToplistPeriod.entries.forEach { period ->
                                                    DropdownMenuItem(text = { Text(galleryToplistPeriodLabel(period)) }, onClick = {
                                                        periodOpen = false
                                                        if (period != entrySearch.toplistPeriod) {
                                                            saveLaunch({ current ->
                                                                setToplistPeriod(period)
                                                                current.copy(toplistPeriod = period)
                                                            }, onSaved = { saved ->
                                                                if (entrySearch.feed == GalleryLaunchPage.TOPLIST) {
                                                                    load(entrySearch.copy(cursor = null, previous = false, toplistPeriod = saved.toplistPeriod), replaceResults = true)
                                                                }
                                                            })
                                                        }
                                                    })
                                                }
                                            }
                                        }
                                    } else {
                                        IconButton(onClick = { search() }) { Icon(GalleryIcons.Search, contentDescription = galleryString(Res.string.gallery_search)) }
                                        IconButton(onClick = { filtersOpen = true }) {
                                            val filtered = filters != GallerySearchFilters()
                                            val description = galleryString(if (filtered) Res.string.gallery_filters_applied else Res.string.gallery_filters)
                                            Icon(GalleryIcons.FilterList, contentDescription = description, tint = if (filtered) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                        }
                                    }
                                }
                                val actionRoute = if (loading) entrySearch else failedSearch ?: loadedSearch ?: entrySearch
                                val actionFeed = actionRoute.feed ?: GalleryLaunchPage.HOMEPAGE
                                GalleryHomeActions(
                                    visible = !homeActionsHidden && !filtersOpen,
                                    canShuffle = actionFeed == GalleryLaunchPage.HOMEPAGE,
                                    canJump = actionFeed != GalleryLaunchPage.WHATS_HOT,
                                    toplistPage = if (actionFeed == GalleryLaunchPage.TOPLIST) actionRoute.cursor?.toIntOrNull() ?: 1 else null,
                                    onRefresh = { load(actionRoute.copy(cursor = null, previous = false, range = 0, seekDate = null), replaceResults = true) },
                                    onShuffle = { load(actionRoute.copy(cursor = null, previous = false, range = Random.nextInt(100), seekDate = null), replaceResults = true) },
                                    onSeekDate = { load(actionRoute.copy(cursor = "2", previous = false, range = 0, seekDate = it), replaceResults = true) },
                                    onJumpToPage = { load(actionRoute.copy(cursor = it.toString(), previous = false, range = 0, seekDate = null), replaceResults = true) },
                                    onLast = {
                                        val target = if (actionFeed == GalleryLaunchPage.TOPLIST) "200" else "1"
                                        load(actionRoute.copy(cursor = target, previous = actionFeed != GalleryLaunchPage.TOPLIST, range = 0, seekDate = null), replaceResults = true)
                                    },
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
                                if (launchReadFailed || launchSaveFailed) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(galleryString(Res.string.gallery_launch_preferences_unavailable), Modifier.weight(1f))
                                        TextButton(onClick = { launchAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
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

private fun galleryVisibleCursor(result: GallerySearchResult?, count: Int, first: Int?, firstKey: Any?, last: Int?): Pair<String, Boolean>? {
    if (result == null || first == null || last == null || count != result.galleryInfoList.size) return null
    if (result.galleryInfoList.getOrNull(first)?.gid != firstKey) return null
    val previous = result.prev
    val next = result.next
    return when {
        previous != null && first < 20 -> previous to true
        next != null && last >= result.galleryInfoList.size - 21 -> next to false
        else -> null
    }
}

@Composable
private fun GalleryBrowseLoadState(loading: Boolean, error: GalleryFailure?, onRetry: () -> Unit) {
    if (loading) {
        LinearWavyProgressIndicator()
    } else if (error != null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error.userMessage())
            Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(galleryString(Res.string.gallery_retry)) }
        }
    }
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
    GalleryFailure.SEARCH_QUERY -> galleryString(Res.string.gallery_error_query)
    GalleryFailure.NO_WATCHED_TAGS -> galleryString(Res.string.gallery_error_no_watched_tags)
}
