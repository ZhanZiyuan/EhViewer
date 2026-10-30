package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GallerySession
import com.ehviewer.shared.reader.GalleryPageSource
import com.ehviewer.shared.reader.ReaderPageState
import com.ehviewer.shared.reader.ReaderPageWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun GalleryContinuousReader(session: GallerySession, detail: GalleryDetail, initial: String, readerState: ReaderStateStore, onBack: () -> Unit, onPaged: (String) -> Unit) {
    val first = session.content.pageIndex(initial, detail.gid)
    val list = rememberLazyListState(first)
    val scope = rememberCoroutineScope()
    val source = remember(session, detail.gid, initial) { GalleryPageSource(session, detail, initial) }
    val window = remember(source) { ReaderPageWindow(detail.pages, scope, load = source::load) }
    val pages by window.pages.collectAsState()
    val ratios = remember(window) { mutableStateMapOf<Int, Float>() }
    var saveFailure by remember { mutableStateOf(false) }
    var saveAttempt by remember { mutableIntStateOf(0) }
    val index = list.firstVisibleItemIndex
    val current = pages[index] as? ReaderPageState.Ready<ImageBitmap>
    val defaultFling = ScrollableDefaults.flingBehavior()
    val readerFling = remember(defaultFling, list) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val limit = list.layoutInfo.viewportSize.height.coerceAtLeast(1) * 3f
                return with(defaultFling) { performFling(initialVelocity.coerceIn(-limit, limit)) }
            }
        }
    }
    DisposableEffect(window) { onDispose { window.close() } }
    LaunchedEffect(pages) {
        pages.forEach { (index, state) ->
            if (state is ReaderPageState.Ready) ratios[index] = state.page.value.width.toFloat() / state.page.value.height
        }
        while (ratios.size > 1024) ratios.remove(ratios.keys.first { it !in pages })
    }
    LaunchedEffect(window) { snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect(window::focus) }
    LaunchedEffect(index, current?.page?.url, saveAttempt) {
        val ready = current ?: return@LaunchedEffect
        try {
            readerState.savePosition(detail.gid, index, ready.page.url.substringAfter("/s/").substringBefore('/'))
            saveFailure = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ReaderStateException) {
            saveFailure = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("${index + 1} / ${detail.pages}")
            TextButton(onClick = { current?.page?.url?.let(onPaged) }, enabled = current != null) { Text("Paged reading") }
        }
        if (saveFailure) {
            Row {
                Text("Reading progress could not be saved.", Modifier.weight(1f))
                TextButton(onClick = { saveAttempt++ }) { Text("Retry") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, flingBehavior = readerFling) {
            items(detail.pages, key = { it }) { page ->
                // Unloaded pages retain a page-shaped extent; tiny placeholders make touch flings skip dozens of pages.
                val state = pages[page]
                val ratio = ((state as? ReaderPageState.Ready)?.page?.value?.let { it.width.toFloat() / it.height } ?: ratios[page] ?: (1f / 1.4f)).coerceIn(0.1f, 10f)
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).aspectRatio(ratio)) {
                    when (state) {
                        is ReaderPageState.Ready -> Image(state.page.value, "Gallery page ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        is ReaderPageState.Failed -> GalleryError(state.failure) {
                            source.retry(page)
                            window.retry(page)
                        }
                        else -> Column(Modifier.fillMaxWidth()) {
                            Text("Page ${page + 1}")
                            if (state == ReaderPageState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
