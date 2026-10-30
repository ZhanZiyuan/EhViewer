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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlinx.coroutines.CancellationException

/** Detail and reader routes retain the original search and detail state when navigating back. */
@Composable
internal fun GalleryDetailScreen(session: GallerySession, gallery: BaseGalleryInfo, onBack: () -> Unit) {
    var detail by remember(gallery.gid) { mutableStateOf<GalleryDetail?>(null) }
    var failure by remember(gallery.gid) { mutableStateOf<GalleryFailure?>(null) }
    var loading by remember(gallery.gid) { mutableStateOf(false) }
    var attempt by remember(gallery.gid) { mutableIntStateOf(0) }
    var reader by remember(gallery.gid) { mutableStateOf<String?>(null) }
    val previewsState = rememberLazyListState()
    LaunchedEffect(gallery.gid, gallery.token, attempt) {
        loading = true
        failure = null
        try {
            detail = session.content.details(gallery.gid, gallery.token)
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
    BackHandler { if (page != null) reader = null else onBack() }
    if (page != null && document != null) {
        GalleryReaderScreen(session, document, page, onBack = { reader = null })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back to search") }
            TextButton(onClick = { attempt++ }, enabled = !loading) { Text("Refresh") }
        }
        Text(document?.title ?: gallery.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { GalleryError(it) { attempt++ } }
        if (document != null) {
            LazyColumn(Modifier.weight(1f), state = previewsState) {
                item {
                    Text("${document.pages} pages · ${document.size.orEmpty()} · ${document.language.orEmpty()}", Modifier.padding(vertical = 8.dp))
                    document.uploader?.let { Text("Uploaded by $it") }
                    Text("Rating ${document.rating} (${document.ratingCount})")
                    document.previewList.firstOrNull()?.let { first ->
                        Button(onClick = { reader = session.content.pageUrl(document.gid, first.pToken, first.position).toString() }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Read gallery") }
                    }
                }
                items(document.tagGroups) { group ->
                    Text("${group.namespace.value}: ${group.tags.joinToString { it.text }}", Modifier.padding(vertical = 6.dp))
                }
                item { Text("Pages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                items(document.previewList, key = { it.position }) { preview ->
                    ListItem(
                        headlineContent = { Text("Page ${preview.position + 1}") },
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
        TextButton(onClick = retry) { Text("Retry") }
    }
}
