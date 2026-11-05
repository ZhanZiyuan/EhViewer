package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import com.ehviewer.core.domain.download.GalleryDownloadFailure
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.domain.reader.ReadingPosition
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.download.GalleryDownloads
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.shared.reader.readingResumeUrl
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back_search
import ehviewer.app.shared.generated.resources.gallery_continue_reading
import ehviewer.app.shared.generated.resources.gallery_download_add
import ehviewer.app.shared.generated.resources.gallery_download_add_failed
import ehviewer.app.shared.generated.resources.gallery_download_adding
import ehviewer.app.shared.generated.resources.gallery_download_downloading
import ehviewer.app.shared.generated.resources.gallery_download_failed
import ehviewer.app.shared.generated.resources.gallery_download_paused
import ehviewer.app.shared.generated.resources.gallery_download_queued
import ehviewer.app.shared.generated.resources.gallery_download_storage_failed
import ehviewer.app.shared.generated.resources.gallery_downloaded
import ehviewer.app.shared.generated.resources.gallery_favorite_add
import ehviewer.app.shared.generated.resources.gallery_favorite_edit
import ehviewer.app.shared.generated.resources.gallery_history_unavailable
import ehviewer.app.shared.generated.resources.gallery_pages
import ehviewer.app.shared.generated.resources.gallery_previews_browse
import ehviewer.app.shared.generated.resources.gallery_read_gallery
import ehviewer.app.shared.generated.resources.gallery_read_start
import ehviewer.app.shared.generated.resources.gallery_refresh
import ehviewer.app.shared.generated.resources.gallery_retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Detail and reader routes retain the original search and detail state when navigating back. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GalleryDetailScreen(session: GalleryLibrary, gallery: BaseGalleryInfo, readerState: ReaderStateStore, downloads: GalleryDownloads?, covers: GalleryCoverCache<CoilImage>, onDownloads: () -> Unit, onCategory: (Int) -> Unit, onUploader: (String) -> Unit, onTag: (String) -> Unit, backLabel: String? = null, onBack: () -> Unit) {
    var detail by remember(gallery.gid) { mutableStateOf<GalleryDetail?>(null) }
    var failure by remember(gallery.gid) { mutableStateOf<GalleryFailure?>(null) }
    var loading by remember(gallery.gid) { mutableStateOf(false) }
    var attempt by remember(gallery.gid) { mutableIntStateOf(0) }
    var reader by remember(gallery.gid) { mutableStateOf<String?>(null) }
    var browsingPreviews by remember(gallery.gid) { mutableStateOf(false) }
    var previewPage by remember(gallery.gid) { mutableIntStateOf(0) }
    val previewsState = rememberLazyGridState()
    // Keep the title's collapse state alongside the grid when a reader/preview route opens.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val previewCache = rememberGalleryPreviewCache(session, gallery.gid, gallery.token)
    val scope = rememberCoroutineScope()
    var saved by remember(gallery.gid) { mutableStateOf<ReadingPosition?>(null) }
    var stateFailure by remember { mutableStateOf(false) }
    var stateAttempt by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    var addingDownload by remember { mutableStateOf(false) }
    var downloadFailed by remember { mutableStateOf(false) }
    var editingFavorite by remember(gallery.gid, session.restricted) { mutableStateOf(false) }
    var information by remember(gallery.gid) { mutableStateOf(false) }
    LaunchedEffect(gallery.gid, reader, stateAttempt) {
        if (reader == null) {
            try {
                saved = readerState.position(gallery.gid)
                stateFailure = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ReaderStateException) {
                stateFailure = true
            }
        }
    }
    LaunchedEffect(gallery.gid, gallery.token, attempt) {
        loading = true
        failure = null
        try {
            val loaded = session.details(gallery.gid, gallery.token)
            previewPageCount(loaded) // Reject inconsistent/duplicate positions before creating grid keys.
            detail = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        } finally {
            loading = false
        }
    }
    val document = detail
    val page = reader
    BackHandler {
        when {
            page != null -> reader = null
            browsingPreviews -> browsingPreviews = false
            else -> onBack()
        }
    }
    if (page != null && document != null) {
        GalleryReaderScreen(session, document, page, readerState, onBack = { reader = null })
        return
    }
    if (browsingPreviews && document != null) {
        GalleryPreviewScreen(session, document, previewPage, onPage = { previewPage = it }, onOpen = {
            reader = session.content.pageUrl(document.gid, it.pToken, it.position).toString()
        }, onBack = { browsingPreviews = false }, cache = previewCache)
        return
    }
    if (editingFavorite) {
        GalleryFavoriteDialog(session.favorites, gallery.gid, gallery.token, onUpdated = {
            editingFavorite = false
            attempt++
        }, onDismiss = { editingFavorite = false })
    }
    if (information && document != null) {
        val knownThumb = document.thumbKey?.takeIf(String::isNotBlank) ?: gallery.thumbKey
        val thumbUrl = remember(knownThumb, session.restricted) {
            try {
                knownThumb?.let { session.galleries.coverUrl(it).toString() }.orEmpty()
            } catch (_: GalleryRequestException) {
                ""
            }
        }
        GalleryDetailInformation(document, session.galleries.galleryUrl(document.gid, document.token).toString(), thumbUrl, onDismiss = { information = false })
    }
    val first = document?.previewList?.firstOrNull()
    val resume = saved?.takeIf { document != null && it.index in 0 until document.pages }
    val existingDownload = downloads?.tasks?.collectAsState()?.value?.firstOrNull { it.gid == gallery.gid }
    GalleryLinkActions(session.galleries.galleryUrl(gallery.gid, gallery.token).toString()) { copy, open, share ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = galleryPreviewColumns(maxWidth)
            Column(Modifier.fillMaxSize()) {
                GalleryDetailAppBar(
                    title = document?.title ?: gallery.title.orEmpty(),
                    backLabel = backLabel ?: galleryString(Res.string.gallery_back_search),
                    onBack = onBack,
                    onRefresh = { attempt++ },
                    refreshEnabled = !loading,
                    scrollBehavior = scrollBehavior,
                    onShare = share,
                    onCopyLink = copy,
                    onOpenLink = open,
                    onInformation = { information = true },
                    informationEnabled = document != null,
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { GalleryError(it) { attempt++ } }
                if (stateFailure) {
                    Row {
                        Text(galleryString(Res.string.gallery_history_unavailable), Modifier.weight(1f))
                        TextButton(onClick = { stateAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
                    }
                }
                LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection), state = previewsState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth()) {
                            GalleryDetailHeaderSection(
                                document ?: gallery,
                                covers,
                                onInformation = { information = true },
                                onCategory = { onCategory((document ?: gallery).category) },
                                onUploader = { (document ?: gallery).uploader?.let(onUploader) },
                                download = { modifier ->
                                    val label = when {
                                        addingDownload -> galleryString(Res.string.gallery_download_adding)
                                        existingDownload == null -> galleryString(Res.string.gallery_download_add)
                                        else -> galleryString(
                                            when (existingDownload.state) {
                                                GalleryDownloadState.QUEUED -> Res.string.gallery_download_queued
                                                GalleryDownloadState.DOWNLOADING -> Res.string.gallery_download_downloading
                                                GalleryDownloadState.PAUSED -> Res.string.gallery_download_paused
                                                GalleryDownloadState.FAILED -> if (existingDownload.failure == GalleryDownloadFailure.STORAGE) Res.string.gallery_download_storage_failed else Res.string.gallery_download_failed
                                                GalleryDownloadState.COMPLETE -> Res.string.gallery_downloaded
                                            },
                                        )
                                    }
                                    FilledTonalButton(onClick = {
                                        if (existingDownload != null) {
                                            onDownloads()
                                        } else if (document != null && downloads != null) {
                                            addingDownload = true
                                            downloadFailed = false
                                            scope.launch {
                                                try {
                                                    downloads.enqueue(document, session.restricted)
                                                    onDownloads()
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    downloadFailed = true
                                                } finally {
                                                    addingDownload = false
                                                }
                                            }
                                        }
                                    }, enabled = !addingDownload && downloads != null && (document != null || existingDownload != null), modifier = modifier) {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                read = { modifier ->
                                    Button(onClick = {
                                        if (document != null && first != null) {
                                            opening = true
                                            scope.launch {
                                                try {
                                                    reader = if (resume != null) readingResumeUrl(session.content, document, resume) else session.content.pageUrl(document.gid, first.pToken, first.position).toString()
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (error: GalleryRequestException) {
                                                    failure = error.failure
                                                } finally {
                                                    opening = false
                                                }
                                            }
                                        }
                                    }, enabled = !opening && document != null && first != null, modifier = modifier) {
                                        Text(if (resume == null || resume.index == 0) galleryString(Res.string.gallery_read_gallery) else galleryString(Res.string.gallery_continue_reading, resume.index + 1), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                fallbackCoverKey = gallery.thumbKey,
                            )
                            if (downloadFailed) Text(galleryString(Res.string.gallery_download_add_failed), color = MaterialTheme.colorScheme.error)
                            if (document != null) {
                                if (resume != null && resume.index > 0 && first != null) TextButton(onClick = { reader = session.content.pageUrl(document.gid, first.pToken, first.position).toString() }) { Text(galleryString(Res.string.gallery_read_start)) }
                                Button(onClick = { editingFavorite = true }) { Text(if (document.favoriteSlot in 0..9) galleryString(Res.string.gallery_favorite_edit) else galleryString(Res.string.gallery_favorite_add)) }
                                GalleryDetailRating(document.rating, document.ratingCount, Modifier.padding(vertical = 16.dp))
                            }
                        }
                    }
                    if (document != null) {
                        items(document.tagGroups, span = { GridItemSpan(maxLineSpan) }) { group ->
                            GalleryDetailTagGroup(group, onTag)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(galleryString(Res.string.gallery_pages), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                                TextButton(onClick = { browsingPreviews = true }) { Text(galleryString(Res.string.gallery_previews_browse)) }
                            }
                        }
                        items(document.previewList, key = { it.position }) { preview ->
                            GalleryPreviewCell(preview, previewCache, onOpen = { reader = session.content.pageUrl(document.gid, preview.pToken, preview.position).toString() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GalleryError(failure: GalleryFailure, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(failure.userMessage(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text(galleryString(Res.string.gallery_retry)) }
    }
}
