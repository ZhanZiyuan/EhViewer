package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.domain.GalleryNetworkPreferences
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
import ehviewer.app.shared.generated.resources.gallery_account
import ehviewer.app.shared.generated.resources.gallery_appearance_unavailable
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_back_history
import ehviewer.app.shared.generated.resources.gallery_back_search
import ehviewer.app.shared.generated.resources.gallery_download_background
import ehviewer.app.shared.generated.resources.gallery_downloads
import ehviewer.app.shared.generated.resources.gallery_error_authentication
import ehviewer.app.shared.generated.resources.gallery_error_challenge
import ehviewer.app.shared.generated.resources.gallery_error_local_storage
import ehviewer.app.shared.generated.resources.gallery_error_offline
import ehviewer.app.shared.generated.resources.gallery_error_rate_limit
import ehviewer.app.shared.generated.resources.gallery_error_response
import ehviewer.app.shared.generated.resources.gallery_error_server
import ehviewer.app.shared.generated.resources.gallery_error_session_storage
import ehviewer.app.shared.generated.resources.gallery_error_too_large
import ehviewer.app.shared.generated.resources.gallery_filters
import ehviewer.app.shared.generated.resources.gallery_filters_applied
import ehviewer.app.shared.generated.resources.gallery_history
import ehviewer.app.shared.generated.resources.gallery_local_library
import ehviewer.app.shared.generated.resources.gallery_next
import ehviewer.app.shared.generated.resources.gallery_no_galleries
import ehviewer.app.shared.generated.resources.gallery_page_count
import ehviewer.app.shared.generated.resources.gallery_previous
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_search
import ehviewer.app.shared.generated.resources.gallery_search_galleries
import ehviewer.app.shared.generated.resources.gallery_search_hint
import ehviewer.app.shared.generated.resources.gallery_settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val searchFiltersSaver = Saver<GallerySearchFilters, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<GallerySearchFilters>(it) }.getOrNull() },
)

/** Search UI shared by the actual applications; requests run only after an explicit user action. */
@Composable
fun GallerySearchScreen(session: GallerySession, readerState: ReaderStateStore, appearance: GalleryAppearanceStore, history: GalleryHistoryStore, downloads: GalleryDownloads, language: GalleryLanguageSettings, backgroundDownloadsNote: Boolean = false, archiveContent: (@Composable (onBack: () -> Unit) -> Unit)? = null, browserFactory: ((String) -> GalleryLoginBrowser)? = null, onReady: () -> Unit = {}) {
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
    GalleryLibraryScreen(library, readerState, appearance, history, downloads, language, networkPreferences = session.networkPreferences, backgroundDownloadsNote = backgroundDownloadsNote, archiveContent = archiveContent, accountContent = { changed, back ->
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
    backgroundDownloadsNote: Boolean = false,
    onPlatformDownloadOpen: ((Long) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    accountContent: (@Composable (onIdentityAction: () -> Unit, onBack: () -> Unit) -> Unit)? = null,
    archiveContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    onReady: () -> Unit = {},
) {
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
    var loadedQuery by remember { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = searchFiltersSaver) { mutableStateOf(GallerySearchFilters()) }
    var loadedFilters by remember { mutableStateOf(GallerySearchFilters()) }
    var filtersOpen by remember { mutableStateOf(false) }
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
    val resultsState = rememberLazyListState()

    fun load(cursor: String? = null, previous: Boolean = false) {
        request?.cancel()
        val current = ++generation
        val text = if (cursor == null) query else loadedQuery
        val appliedFilters = if (cursor == null) filters else loadedFilters
        if (cursor == null) page = null
        request = scope.launch {
            loading = true
            failure = null
            try {
                val result = session.galleries.search(text, cursor, previous, appliedFilters)
                if (current == generation) {
                    page = result
                    loadedQuery = text
                    loadedFilters = appliedFilters
                    resultsState.scrollToItem(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GalleryRequestException) {
                if (current == generation) failure = error.failure
            } finally {
                if (current == generation) loading = false
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            request?.cancel()
        }
    }
    val theme = themeMode ?: GalleryThemeMode.SYSTEM
    val dark = theme == GalleryThemeMode.DARK || theme == GalleryThemeMode.SYSTEM && isSystemInDarkTheme()
    CompositionLocalProvider(LocalGalleryLanguage provides languageTag) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (settingsOpen) {
                            GallerySettingsScreen(networkPreferences, appearance, readerState, themeMode, appearanceFailed, language, languageTag, languageReady, languageFailed, onLanguageChanged = { languageTag = it }, onRetryLanguage = { languageAttempt++ }, onModeChanged = { themeMode = it }, onRetryAppearance = { appearanceAttempt++ }, onBack = {
                                settingsOpen = false
                                appearanceAttempt++
                                languageAttempt++
                            })
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
                                loadedQuery = ""
                                loadedFilters = GallerySearchFilters()
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
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            onBack?.let { back -> TextButton(onClick = back) { Text(galleryString(Res.string.gallery_back)) } }
                            Text("EhViewer", style = MaterialTheme.typography.headlineMedium)
                            TextButton(onClick = { downloadsOpen = true }) { Text(galleryString(Res.string.gallery_downloads)) }
                            if (archiveContent != null) TextButton(onClick = { archivesOpen = true }) { Text(galleryString(Res.string.gallery_local_library)) }
                            if (onAccount != null || accountContent != null) {
                                TextButton(onClick = {
                                    request?.cancel()
                                    generation++
                                    loading = false
                                    if (onAccount != null) onAccount() else account = true
                                }) { Text(galleryString(Res.string.gallery_account)) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                query,
                                { query = it.take(4096) },
                                Modifier.weight(1f),
                                label = { Text(galleryString(Res.string.gallery_search_galleries)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { load() }),
                            )
                            Button(onClick = { load() }) { Text(galleryString(Res.string.gallery_search)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { filtersOpen = true }) { Text(galleryString(Res.string.gallery_filters)) }
                            if (filters != GallerySearchFilters()) Text(galleryString(Res.string.gallery_filters_applied), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { historyOpen = true }) { Text(galleryString(Res.string.gallery_history)) }
                            TextButton(onClick = { settingsOpen = true }) { Text(galleryString(Res.string.gallery_settings)) }
                        }
                        if (appearanceFailed) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(galleryString(Res.string.gallery_appearance_unavailable), Modifier.weight(1f))
                                TextButton(onClick = { appearanceAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
                            }
                        }
                        if (filtersOpen) {
                            GallerySearchFiltersDialog(filters, onApply = { applied ->
                                filters = applied
                                filtersOpen = false
                                load()
                            }, onDismiss = { filtersOpen = false })
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val error = failure
                        if (error != null) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(error.userMessage(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { load() }) { Text(galleryString(Res.string.gallery_retry)) }
                            }
                        }
                        val result = page
                        if (result == null && !loading && error == null) Text(galleryString(Res.string.gallery_search_hint), Modifier.padding(vertical = 24.dp))
                        if (result != null) {
                            if (result.galleryInfoList.isEmpty()) Text(galleryString(Res.string.gallery_no_galleries), Modifier.padding(vertical = 24.dp))
                            LazyColumn(Modifier.weight(1f), state = resultsState) {
                                items(result.galleryInfoList, key = { it.gid }) { gallery ->
                                    ListItem(
                                        headlineContent = { Text(gallery.title ?: gallery.titleJpn.orEmpty()) },
                                        supportingContent = { Text(galleryString(Res.string.gallery_page_count, gallery.pages)) },
                                        modifier = Modifier.clickable { selected = gallery },
                                    )
                                    HorizontalDivider()
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { load(result.prev, previous = true) }, enabled = result.prev != null && !loading) { Text(galleryString(Res.string.gallery_previous)) }
                                TextButton(onClick = { load(result.next) }, enabled = result.next != null && !loading) { Text(galleryString(Res.string.gallery_next)) }
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
