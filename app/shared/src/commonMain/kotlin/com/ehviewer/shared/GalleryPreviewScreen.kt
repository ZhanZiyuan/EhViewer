package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlinx.coroutines.CancellationException

internal fun previewPageCount(detail: GalleryDetail): Int {
    if (detail.previewList.isEmpty()) return 0
    checkPreviewWindow(detail, detail, 0)
    return ((detail.pages.toLong() + detail.previewList.size - 1) / detail.previewList.size).toInt()
}

/** A changed site layout must never silently open a fabricated page/token pair. */
internal fun checkPreviewWindow(first: GalleryDetail, window: GalleryDetail, page: Int) {
    val width = first.previewList.size
    val start = page.toLong() * width
    val end = minOf(start + width, first.pages.toLong())
    if (width <= 0 || first.pages <= 0 || page < 0 || start >= first.pages ||
        window.gid != first.gid || window.token != first.token || window.pages != first.pages ||
        window.previewList.map { it.position.toLong() } != (start until end).toList()
    ) {
        throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    }
}

@Composable
internal fun GalleryPreviewScreen(session: GalleryLibrary, detail: GalleryDetail, page: Int, onPage: (Int) -> Unit, onOpen: (GalleryPreview) -> Unit, onBack: () -> Unit) {
    var previews by remember(detail) { mutableStateOf(detail.previewList) }
    var loadedPage by remember(detail) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val grid = rememberLazyGridState()
    val cache = remember(session, detail.gid, detail.token) {
        GalleryPreviewCache<ImageBitmap> { preview ->
            val bitmap = decodeGalleryImage(session.content.preview(preview, detail.gid, detail.token))
            PreviewBitmap(bitmap, bitmap.width, bitmap.height)
        }
    }
    var count by remember(detail) { mutableIntStateOf(0) }
    LaunchedEffect(detail, page, attempt) {
        loading = true
        failure = null
        try {
            count = previewPageCount(detail)
            if (count > 0) {
                val window = if (page == 0) detail else session.content.details(detail.gid, detail.token, page)
                checkPreviewWindow(detail, window, page)
                previews = window.previewList
                loadedPage = page
                grid.scrollToItem(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back to details") }
            Text("Page previews")
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { GalleryError(it) { attempt++ } }
        if (count == 0 && !loading && failure == null) Text("No page previews are available.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onPage(loadedPage - 1) }, enabled = !loading && loadedPage > 0) { Text("Previous previews") }
            Text(if (count == 0) "0 / 0" else "${loadedPage + 1} / $count")
            TextButton(onClick = { onPage(loadedPage + 1) }, enabled = !loading && loadedPage + 1 < count) { Text("Next previews") }
        }
        LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f), state = grid) {
            items(previews, key = { it.position }) { preview ->
                Column(Modifier.padding(4.dp).clickable(enabled = !loading) { onOpen(preview) }) {
                    GalleryPreviewImage(preview, cache)
                    Text("Page ${preview.position + 1}", Modifier.padding(4.dp))
                }
            }
        }
    }
}
