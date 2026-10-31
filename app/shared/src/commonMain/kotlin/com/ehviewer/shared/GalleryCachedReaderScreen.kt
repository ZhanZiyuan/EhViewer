package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
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
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.download.CachedGalleryPages
import com.ehviewer.core.network.download.DownloadedGalleryContent
import com.ehviewer.shared.reader.CachedReaderStateStore
import kotlinx.coroutines.CancellationException

/** Reuse paged/continuous UI and preferences; local keys must never overwrite service resume tokens. */
@Composable
internal fun GalleryCachedReaderScreen(library: GalleryLibrary, pages: CachedGalleryPages, readerState: ReaderStateStore, onBack: () -> Unit) {
    val content = remember(pages) { DownloadedGalleryContent(pages) }
    val offline = remember(library, content) {
        object : GalleryLibrary by library {
            override val content = content
            override suspend fun details(gid: Long, token: String) = content.details(gid, token)
        }
    }
    val state = remember(readerState) { CachedReaderStateStore(readerState) }
    var ready by remember(pages) { mutableStateOf<Pair<GalleryDetail, String>?>(null) }
    var failed by remember(pages) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(content, state, attempt) {
        failed = false
        try {
            val gallery = pages.gallery
            val index = state.position(gallery.gid)?.index?.takeIf { it in 0 until gallery.pages } ?: 0
            ready = content.details(gallery.gid, gallery.token) to content.localUrl(index).toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val opened = ready
    if (opened != null) {
        GalleryReaderScreen(offline, opened.first, opened.second, state, onBack)
    } else {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = onBack) { Text("Back") }
            if (failed) {
                Text("This download could not be opened. Saved pages have been kept.")
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            } else {
                LinearProgressIndicator()
            }
        }
    }
}
