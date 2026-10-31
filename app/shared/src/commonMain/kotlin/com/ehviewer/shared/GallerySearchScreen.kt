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
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.GalleryHistoryStore
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
import com.ehviewer.shared.settings.GalleryThemeMode
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
fun GallerySearchScreen(session: GallerySession, readerState: ReaderStateStore, appearance: GalleryAppearanceStore, history: GalleryHistoryStore, downloads: GalleryDownloads, backgroundDownloadsNote: String? = null, browserFactory: ((String) -> GalleryLoginBrowser)? = null, onReady: () -> Unit = {}) {
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
    GalleryLibraryScreen(library, readerState, appearance, history, downloads, backgroundDownloadsNote = backgroundDownloadsNote, accountContent = { changed, back ->
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
    backgroundDownloadsNote: String? = null,
    onPlatformDownloadOpen: ((Long) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    accountContent: (@Composable (onIdentityAction: () -> Unit, onBack: () -> Unit) -> Unit)? = null,
    onReady: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var loadedQuery by remember { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = searchFiltersSaver) { mutableStateOf(GallerySearchFilters()) }
    var loadedFilters by remember { mutableStateOf(GallerySearchFilters()) }
    var filtersOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
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
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (settingsOpen) {
                        GallerySettingsScreen(appearance, readerState, themeMode, appearanceFailed, onModeChanged = { themeMode = it }, onRetryAppearance = { appearanceAttempt++ }, onBack = {
                            settingsOpen = false
                            appearanceAttempt++
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
                    if (downloadsOpen) {
                        val opened = cached
                        if (opened != null) {
                            GalleryCachedReaderScreen(session, opened, readerState, onBack = { cached = null })
                        } else {
                            GalleryDownloadsScreen(downloads, downloadsReady, downloadsFailed, retry = { downloadsAttempt++ }, backgroundNote = backgroundDownloadsNote, onOpen = {
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
                        GalleryDetailScreen(session, gallery, readerState, downloads.takeIf { downloadsReady }, onDownloads = { downloadsOpen = true }, backLabel = if (historyOpen) "Back to history" else "Back to search", onBack = { selected = null })
                        return@Column
                    }
                    if (historyOpen) {
                        GalleryHistoryScreen(history, onOpen = { selected = it }, onBack = { historyOpen = false })
                        return@Column
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        onBack?.let { back -> TextButton(onClick = back) { Text("Back") } }
                        Text("EhViewer", style = MaterialTheme.typography.headlineMedium)
                        TextButton(onClick = { downloadsOpen = true }) { Text("Downloads") }
                        if (onAccount != null || accountContent != null) {
                            TextButton(onClick = {
                                request?.cancel()
                                generation++
                                loading = false
                                if (onAccount != null) onAccount() else account = true
                            }) { Text("Account") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            query,
                            { query = it.take(4096) },
                            Modifier.weight(1f),
                            label = { Text("Search galleries") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { load() }),
                        )
                        Button(onClick = { load() }) { Text("Search") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { filtersOpen = true }) { Text("Filters") }
                        if (filters != GallerySearchFilters()) Text("Filters applied", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { historyOpen = true }) { Text("History") }
                        TextButton(onClick = { settingsOpen = true }) { Text("Settings") }
                    }
                    if (appearanceFailed) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Appearance settings are unavailable.", Modifier.weight(1f))
                            TextButton(onClick = { appearanceAttempt++ }) { Text("Retry") }
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
                            TextButton(onClick = { load() }) { Text("Retry") }
                        }
                    }
                    val result = page
                    if (result == null && !loading && error == null) Text("Search by title or tags, or leave the search empty to browse.", Modifier.padding(vertical = 24.dp))
                    if (result != null) {
                        if (result.galleryInfoList.isEmpty()) Text("No galleries found.", Modifier.padding(vertical = 24.dp))
                        LazyColumn(Modifier.weight(1f), state = resultsState) {
                            items(result.galleryInfoList, key = { it.gid }) { gallery ->
                                ListItem(
                                    headlineContent = { Text(gallery.title ?: gallery.titleJpn.orEmpty()) },
                                    supportingContent = { Text("${gallery.pages} pages") },
                                    modifier = Modifier.clickable { selected = gallery },
                                )
                                HorizontalDivider()
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { load(result.prev, previous = true) }, enabled = result.prev != null && !loading) { Text("Previous") }
                            TextButton(onClick = { load(result.next) }, enabled = result.next != null && !loading) { Text("Next") }
                        }
                    }
                }
            }
        }
    }
    SideEffect(onReady)
}

internal fun GalleryFailure.userMessage(): String = when (this) {
    GalleryFailure.AUTHENTICATION -> "Sign in to continue."
    GalleryFailure.CHALLENGE -> "The site requires browser verification."
    GalleryFailure.RATE_LIMIT -> "Too many requests. Please try again later."
    GalleryFailure.OFFLINE -> "Unable to connect. Check your connection and try again."
    GalleryFailure.SERVER -> "The site is temporarily unavailable."
    GalleryFailure.INVALID_RESPONSE -> "The site returned a page that could not be read."
    GalleryFailure.RESPONSE_TOO_LARGE -> "The content exceeds the loading limit."
    GalleryFailure.SESSION_STORAGE -> "Secure session storage is unavailable. Unlock it and try again."
    GalleryFailure.LOCAL_STORAGE -> "Gallery data could not be saved. Check device storage and try again."
}
