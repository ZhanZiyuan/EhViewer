package com.ehviewer.shared

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.shared.reader.GalleryPageSource
import com.ehviewer.shared.reader.ReaderPageState
import com.ehviewer.shared.reader.ReaderPageWindow
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_gallery_page
import ehviewer.app.shared.generated.resources.gallery_page_number
import ehviewer.app.shared.generated.resources.gallery_reading_paged
import ehviewer.app.shared.generated.resources.gallery_reading_progress_save_failed
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun GalleryContinuousReader(session: GalleryLibrary, detail: GalleryDetail, initial: String, readerState: ReaderStateStore, onBack: () -> Unit, chrome: GalleryReaderChromeState, onPaged: (String) -> Unit, preferences: ReaderPreferences = ReaderPreferences(), onSettings: () -> Unit = {}, settingsEnabled: Boolean = true, requestPageJump: Boolean = false, onPageJumpHandled: () -> Unit = {}) {
    val first = session.content.pageIndex(initial, detail.gid)
    val list = rememberLazyListState(first)
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val source = remember(session, detail.gid, initial) { GalleryPageSource(session, detail, initial) }
    val window = remember(source) { ReaderPageWindow(detail.pages, scope, load = source::load) }
    val pages by window.pages.collectAsState()
    val ratios = remember(window) { mutableStateMapOf<Int, Float>() }
    var saveFailure by remember { mutableStateOf(false) }
    var saveAttempt by remember { mutableIntStateOf(0) }
    var jumpOrigin by remember(window) { mutableStateOf<Pair<Int, Int>?>(null) }
    var jumpTarget by remember(window) { mutableStateOf<Int?>(null) }
    var pendingPosition by remember(window) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(requestPageJump) {
        if (requestPageJump) {
            jumpTarget = null
            jumpOrigin = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            onPageJumpHandled()
        }
    }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val index = list.firstVisibleItemIndex
    val current = pages[index] as? ReaderPageState.Ready<GalleryMedia>
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
    LaunchedEffect(window) {
        snapshotFlow { if (jumpOrigin == null && pendingPosition == null) list.firstVisibleItemIndex else null }
            .distinctUntilChanged().collect { it?.let(window::focus) }
    }
    LaunchedEffect(index, current?.page?.url, saveAttempt, jumpOrigin, pendingPosition) {
        // A modal keyboard can temporarily backfill earlier items. That is not a reading action.
        if (jumpOrigin != null || pendingPosition != null) return@LaunchedEffect
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
    jumpOrigin?.let { origin ->
        GalleryReaderPageDialog(origin.first, detail.pages, onJump = { jumpTarget = it }, onDismiss = {
            pendingPosition = jumpTarget?.let { it to 0 } ?: origin
            jumpOrigin = null
            jumpTarget = null
        })
    }
    GalleryReaderChrome(
        title = detail.title.orEmpty(), index = index, pages = detail.pages, state = chrome,
        onBack = onBack,
        onJump = {
            jumpTarget = null
            jumpOrigin = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        },
        onSeek = { if (it != index) pendingPosition = it to 0 },
        seekEnabled = pendingPosition == null,
        showPageNumber = preferences.showPageNumber, showSeekBar = preferences.showSeekBar, background = readerBackgroundColor(preferences.background),
        modifier = Modifier.focusRequester(focus),
        onKey = readerKey@{ event ->
            if (event.type != KeyEventType.KeyDown) return@readerKey false
            when (event.key) {
                Key.Escape -> onBack()
                Key.Spacebar -> chrome.toggle()
                Key.PageDown, Key.DirectionDown -> scope.launch { list.animateScrollBy(list.layoutInfo.viewportSize.height.toFloat()) }
                Key.PageUp, Key.DirectionUp -> scope.launch { list.animateScrollBy(-list.layoutInfo.viewportSize.height.toFloat()) }
                else -> return@readerKey false
            }
            true
        },
        status = {
            if (saveFailure) {
                Row {
                    Text(galleryString(Res.string.gallery_reading_progress_save_failed), Modifier.weight(1f))
                    TextButton(onClick = { saveAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
                }
            }
        },
        actions = {
            GalleryReaderAction(galleryString(Res.string.gallery_reading_paged), GalleryIcons.LibraryBooks, current != null && settingsEnabled) { current?.page?.url?.let(onPaged) }
            GalleryReaderAction(galleryString(Res.string.gallery_settings), GalleryIcons.Settings, settingsEnabled, onSettings)
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            LaunchedEffect(pendingPosition, imeBottom, index, viewportHeight) {
                val position = pendingPosition ?: return@LaunchedEffect
                if (imeBottom == 0) {
                    val layout = list.layoutInfo
                    val size = layout.visibleItemsInfo.firstOrNull { it.index == position.first }?.size
                    val offset = readerRestoreOffset(position.second, size, layout.viewportSize.height, position.first == detail.pages - 1)
                    list.requestScrollToItem(position.first, offset)
                    if (index == position.first) pendingPosition = null
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, flingBehavior = readerFling) {
                items(detail.pages, key = { it }) { page ->
                    // Unloaded pages retain a page-shaped extent; tiny placeholders make touch flings skip dozens of pages.
                    val state = pages[page]
                    val visible by remember(list, page) { derivedStateOf { list.layoutInfo.visibleItemsInfo.any { it.index == page } } }
                    val ratio = ((state as? ReaderPageState.Ready)?.page?.value?.let { it.width.toFloat() / it.height } ?: ratios[page] ?: (1f / 1.4f)).coerceIn(0.1f, 10f)
                    // A short last page must retain the requested index instead of backfilling earlier pages.
                    Box(Modifier.fillMaxWidth().heightIn(min = if (page == detail.pages - 1) viewportHeight else 0.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(ratio).pointerInput(chrome) { detectTapGestures(onTap = { chrome.toggle() }) }) {
                            when (state) {
                                is ReaderPageState.Ready -> GalleryMediaImage(state.page.value, galleryString(Res.string.gallery_gallery_page, page + 1), Modifier.fillMaxSize(), visible = visible)
                                is ReaderPageState.Failed -> Surface {
                                    GalleryError(state.failure) {
                                        source.retry(page)
                                        window.retry(page)
                                    }
                                }
                                else -> Column(Modifier.fillMaxWidth()) {
                                    Text(galleryString(Res.string.gallery_page_number, page + 1), color = Color.White)
                                    if (state == ReaderPageState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
