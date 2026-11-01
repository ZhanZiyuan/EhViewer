package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
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
import ehviewer.app.shared.generated.resources.gallery_favorite_add
import ehviewer.app.shared.generated.resources.gallery_favorite_edit
import ehviewer.app.shared.generated.resources.gallery_gallery_metadata
import ehviewer.app.shared.generated.resources.gallery_history_unavailable
import ehviewer.app.shared.generated.resources.gallery_page_number
import ehviewer.app.shared.generated.resources.gallery_pages
import ehviewer.app.shared.generated.resources.gallery_previews_browse
import ehviewer.app.shared.generated.resources.gallery_rating
import ehviewer.app.shared.generated.resources.gallery_read_gallery
import ehviewer.app.shared.generated.resources.gallery_read_start
import ehviewer.app.shared.generated.resources.gallery_refresh
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_uploaded_by
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Detail and reader routes retain the original search and detail state when navigating back. */
@Composable
internal fun GalleryDetailScreen(session: GalleryLibrary, gallery: BaseGalleryInfo, readerState: ReaderStateStore, downloads: GalleryDownloads?, onDownloads: () -> Unit, backLabel: String? = null, onBack: () -> Unit) {
    var detail by remember(gallery.gid) { mutableStateOf<GalleryDetail?>(null) }
    var failure by remember(gallery.gid) { mutableStateOf<GalleryFailure?>(null) }
    var loading by remember(gallery.gid) { mutableStateOf(false) }
    var attempt by remember(gallery.gid) { mutableIntStateOf(0) }
    var reader by remember(gallery.gid) { mutableStateOf<String?>(null) }
    var browsingPreviews by remember(gallery.gid) { mutableStateOf(false) }
    var previewPage by remember(gallery.gid) { mutableIntStateOf(0) }
    val previewsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var saved by remember(gallery.gid) { mutableStateOf<ReadingPosition?>(null) }
    var stateFailure by remember { mutableStateOf(false) }
    var stateAttempt by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    var addingDownload by remember { mutableStateOf(false) }
    var downloadFailed by remember { mutableStateOf(false) }
    var editingFavorite by remember(gallery.gid, session.restricted) { mutableStateOf(false) }
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
            detail = session.details(gallery.gid, gallery.token)
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
        }, onBack = { browsingPreviews = false })
        return
    }
    if (editingFavorite) {
        GalleryFavoriteDialog(session.favorites, gallery.gid, gallery.token, onUpdated = {
            editingFavorite = false
            attempt++
        }, onDismiss = { editingFavorite = false })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(backLabel ?: galleryString(Res.string.gallery_back_search)) }
            TextButton(onClick = { attempt++ }, enabled = !loading) { Text(galleryString(Res.string.gallery_refresh)) }
        }
        Text(document?.title ?: gallery.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { GalleryError(it) { attempt++ } }
        if (stateFailure) {
            Row {
                Text(galleryString(Res.string.gallery_history_unavailable), Modifier.weight(1f))
                TextButton(onClick = { stateAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
            }
        }
        if (document != null) {
            LazyColumn(Modifier.weight(1f), state = previewsState) {
                item {
                    Text(galleryString(Res.string.gallery_gallery_metadata, document.pages, document.size.orEmpty(), document.language.orEmpty()), Modifier.padding(vertical = 8.dp))
                    document.uploader?.let { Text(galleryString(Res.string.gallery_uploaded_by, it)) }
                    Text(galleryString(Res.string.gallery_rating, document.rating, document.ratingCount))
                    Button(onClick = { editingFavorite = true }) { Text(if (document.favoriteSlot in 0..9) galleryString(Res.string.gallery_favorite_edit) else galleryString(Res.string.gallery_favorite_add)) }
                    if (downloads != null) {
                        Button(onClick = {
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
                        }, enabled = !addingDownload) { Text(if (addingDownload) galleryString(Res.string.gallery_download_adding) else galleryString(Res.string.gallery_download_add)) }
                        if (downloadFailed) Text(galleryString(Res.string.gallery_download_add_failed))
                    }
                    document.previewList.firstOrNull()?.let { first ->
                        val resume = saved?.takeIf { it.index in 0 until document.pages }
                        Button(onClick = {
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
                        }, enabled = !opening, modifier = Modifier.padding(vertical = 12.dp)) { Text(if (resume == null) galleryString(Res.string.gallery_read_gallery) else galleryString(Res.string.gallery_continue_reading, resume.index + 1)) }
                        if (resume != null) TextButton(onClick = { reader = session.content.pageUrl(document.gid, first.pToken, first.position).toString() }) { Text(galleryString(Res.string.gallery_read_start)) }
                    }
                }
                items(document.tagGroups) { group ->
                    Text("${group.namespace.value}: ${group.tags.joinToString { it.text }}", Modifier.padding(vertical = 6.dp))
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(galleryString(Res.string.gallery_pages), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                        TextButton(onClick = { browsingPreviews = true }) { Text(galleryString(Res.string.gallery_previews_browse)) }
                    }
                }
                items(document.previewList, key = { it.position }) { preview ->
                    ListItem(
                        headlineContent = { Text(galleryString(Res.string.gallery_page_number, preview.position + 1)) },
                        modifier = Modifier.clickable { reader = session.content.pageUrl(document.gid, preview.pToken, preview.position).toString() },
                    )
                    HorizontalDivider()
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
