package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.network.download.GalleryDownloadOpen
import com.ehviewer.core.network.download.GalleryDownloads
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_download_update_failed
import ehviewer.app.shared.generated.resources.gallery_downloads
import ehviewer.app.shared.generated.resources.gallery_downloads_empty
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_search
import ehviewer.app.shared.generated.resources.gallery_search_in
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Original Downloads floating search/empty state; filtering never changes the underlying queue. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun GalleryDownloadsScreen(downloads: GalleryDownloads, ready: Boolean, failed: Boolean, retry: () -> Unit, backgroundNote: String?, onOpen: suspend (GalleryDownloadOpen) -> Unit, onBack: () -> Unit, openNavigation: (() -> Unit)? = null) {
    val tasks by downloads.tasks.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var operationFailed by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var appliedQuery by rememberSaveable { mutableStateOf("") }
    val shown = remember(tasks, appliedQuery) {
        val keyword = appliedQuery.trim()
        if (keyword.isEmpty()) tasks else tasks.filter { it.title.contains(keyword, ignoreCase = true) }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyGridState()
    val context = coil3.compose.LocalPlatformContext.current
    val loader = remember(downloads, context) {
        GalleryCoverLoader(context) { request ->
            try {
                downloads.preview(request.gid, request.token) ?: throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw GalleryRequestException(GalleryFailure.LOCAL_STORAGE)
            }
        }
    }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val previews = remember(loader) { GalleryCoverCache(load = loader::get) }
    val scope = rememberCoroutineScope()
    fun search() {
        appliedQuery = query
        listState.requestScrollToItem(0)
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        operationFailed = false
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationFailed = true
            } finally {
                busy = false
            }
        }
    }
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val title = galleryString(Res.string.gallery_downloads)
        val barSpace = SearchBarDefaults.InputFieldHeight + 16.dp
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 1040.dp).fillMaxSize().padding(top = barSpace)) {
            backgroundNote?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            if (failed || operationFailed) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(galleryString(Res.string.gallery_download_update_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        operationFailed = false
                        retry()
                    }, enabled = !busy) { Text(galleryString(Res.string.gallery_retry)) }
                }
            }
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LazyVerticalGrid(columns = GridCells.Adaptive(336.dp), modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = GalleryListMarginV), horizontalArrangement = Arrangement.spacedBy(GalleryListInterval), verticalArrangement = Arrangement.spacedBy(GalleryListInterval)) {
                    items(shown, key = { it.gid }) { task ->
                        GalleryDownloadCard(task, previews, enabled = ready && !busy, previewReady = ready, onAction = {
                            perform {
                                when (task.state) {
                                    GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING -> downloads.pause(task.gid)
                                    GalleryDownloadState.PAUSED, GalleryDownloadState.FAILED -> downloads.resume(task.gid)
                                    GalleryDownloadState.COMPLETE -> onOpen(downloads.open(task.gid))
                                }
                            }
                        }, onDelete = {
                            perform {
                                downloads.cancel(task.gid)
                                previews.invalidate(task.previewRequest.key)
                            }
                        })
                    }
                }
                when {
                    !ready && !failed -> CircularWavyProgressIndicator()
                    ready && !failed && !operationFailed && shown.isEmpty() -> Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(GalleryIcons.DownloadEmpty, null, Modifier.padding(16.dp).size(120.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(galleryString(Res.string.gallery_downloads_empty), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
        GallerySearchBar(
            query,
            { query = it.take(4096) },
            ::search,
            maxWidth,
            title = title,
            hint = galleryString(Res.string.gallery_search_in, title),
            leading = {
                if (openNavigation != null) GalleryNavigationButton(openNavigation) else IconButton(onClick = onBack) { Icon(GalleryIcons.ArrowBack, galleryString(Res.string.gallery_back)) }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            IconButton(onClick = ::search) { Icon(GalleryIcons.Search, galleryString(Res.string.gallery_search)) }
        }
    }
}
