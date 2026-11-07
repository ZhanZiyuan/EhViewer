package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.GalleryLocalFavorites
import com.ehviewer.core.domain.GalleryLocalFavoritesException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.BaseGalleryInfo
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_local_favorites
import ehviewer.app.shared.generated.resources.gallery_local_favorites_unavailable
import ehviewer.app.shared.generated.resources.gallery_no_galleries
import ehviewer.app.shared.generated.resources.gallery_retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryLocalFavoritesScreen(
    store: GalleryLocalFavorites,
    covers: GalleryCoverCache<CoilImage>,
    downloadedGids: Set<Long>,
    presentation: GalleryListPresentation,
    readerState: ReaderStateStore,
    openNavigation: (() -> Unit)?,
    onOpen: (BaseGalleryInfo) -> Unit,
    onBack: () -> Unit,
) {
    var rows by remember(store) { mutableStateOf(emptyList<BaseGalleryInfo>()) }
    var offset by remember(store) { mutableIntStateOf(0) }
    var loading by remember(store) { mutableStateOf(false) }
    var complete by remember(store) { mutableStateOf(false) }
    var failed by remember(store) { mutableStateOf(false) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pageSize = 48
    fun load() {
        if (complete || loading) return
        loading = true
        failed = false
        scope.launch {
            try {
                val page = store.page(offset, pageSize)
                offset += page.size
                rows = (rows + page).distinctBy { it.gid }
                complete = page.size < pageSize
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GalleryLocalFavoritesException) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    val nearEnd by remember(state, store) {
        derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= rows.size - 8 }
    }
    LaunchedEffect(store, nearEnd, offset) {
        if (nearEnd && !failed) load()
    }
    BackHandler(onBack = onBack)
    val window = LocalWindowInfo.current.containerSize
    val margin = with(LocalDensity.current) { galleryListMarginH(window.width.toDp(), window.height.toDp()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(galleryString(Res.string.gallery_local_favorites)) },
            navigationIcon = { openNavigation?.let { GalleryNavigationButton(it) } },
            windowInsets = WindowInsets(0),
        )
        if (rows.isEmpty() && !failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (complete) Text(galleryString(Res.string.gallery_no_galleries)) else CircularWavyProgressIndicator()
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(horizontal = margin, vertical = GalleryListMarginV),
                verticalArrangement = Arrangement.spacedBy(GalleryListInterval),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(rows, key = { it.gid }) { gallery ->
                    GallerySearchCard(gallery, covers, downloaded = gallery.gid in downloadedGids, thumbnailSize = presentation.detailThumbnailSize, presentation = presentation, readerState = readerState) { onOpen(gallery) }
                }
                if (failed) {
                    item("local-favorites-retry") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(galleryString(Res.string.gallery_local_favorites_unavailable), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = ::load) { Text(galleryString(Res.string.gallery_retry)) }
                        }
                    }
                }
                if (loading) item("local-favorites-progress") { CircularWavyProgressIndicator() }
            }
        }
    }
}
