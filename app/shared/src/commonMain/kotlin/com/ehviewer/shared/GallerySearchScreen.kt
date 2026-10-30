package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Search UI shared by the actual applications; requests run only after an explicit user action. */
@Composable
fun GallerySearchScreen(session: GallerySession, readerState: ReaderStateStore, browserFactory: ((String) -> GalleryLoginBrowser)? = null, onReady: () -> Unit = {}) {
    DisposableEffect(session) { onDispose { session.close() } }
    GalleryLibraryScreen(session, readerState, accountContent = { changed, back ->
        GalleryAccountScreen(session, browserFactory, changed, back)
    }, onReady = onReady)
}

/** Android uses the same views while its original session/history adapters remain authoritative. */
@Composable
fun GalleryLibraryScreen(
    session: GalleryLibrary,
    readerState: ReaderStateStore,
    onBack: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    accountContent: (@Composable (onIdentityAction: () -> Unit, onBack: () -> Unit) -> Unit)? = null,
    onReady: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var loadedQuery by remember { mutableStateOf("") }
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
        if (cursor == null) page = null
        request = scope.launch {
            loading = true
            failure = null
            try {
                val result = session.galleries.search(text, cursor, previous)
                if (current == generation) {
                    page = result
                    loadedQuery = text
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
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (account && accountContent != null) {
                        accountContent({
                            request?.cancel()
                            generation++
                            selected = null
                            page = null
                            query = ""
                            loadedQuery = ""
                            failure = null
                            loading = false
                        }, { account = false })
                        return@Column
                    }
                    val gallery = selected
                    if (gallery != null) {
                        GalleryDetailScreen(session, gallery, readerState, onBack = { selected = null })
                        return@Column
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        onBack?.let { back -> TextButton(onClick = back) { Text("Back") } }
                        Text("EhViewer", style = MaterialTheme.typography.headlineMedium)
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
