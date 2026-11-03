package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.model.BaseGalleryInfo
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_clear_all
import ehviewer.app.shared.generated.resources.gallery_clear_all_history
import ehviewer.app.shared.generated.resources.gallery_history
import ehviewer.app.shared.generated.resources.gallery_history_empty
import ehviewer.app.shared.generated.resources.gallery_history_remove
import ehviewer.app.shared.generated.resources.gallery_history_unavailable
import ehviewer.app.shared.generated.resources.gallery_no_galleries
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_search_in
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Android's HistoryScreen: a floating search bar titled History over one column of list cards. Cards
 * swipe away to forget an entry, Clear all asks first, and an empty history shows the large icon.
 * The search filters the loaded entries by title as the user types.
 */
@Composable
internal fun GalleryHistoryScreen(store: GalleryHistoryStore, covers: GalleryCoverCache<CoilImage>, downloadedGids: Set<Long>, leading: @Composable () -> Unit, onOpen: (BaseGalleryInfo) -> Unit, onBack: () -> Unit) {
    var entries by remember(store) { mutableStateOf<List<BaseGalleryInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, attempt) {
        loading = true
        failed = false
        try {
            entries = store.recent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryHistoryException) {
            failed = true
        } finally {
            loading = false
        }
    }

    fun change(update: suspend () -> Unit) {
        scope.launch {
            try {
                update()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GalleryHistoryException) {
                failed = true
            }
        }
    }

    fun forget(gallery: BaseGalleryInfo) = change {
        store.forget(gallery.gid)
        entries = entries.filterNot { it.gid == gallery.gid }
    }

    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val window = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current
        val marginH = with(density) { galleryListMarginH(window.width.toDp(), window.height.toDp()) }
        val barSpace = SearchBarDefaults.InputFieldHeight + 16.dp
        val barSpacePx = with(density) { barSpace.roundToPx() }
        val barConnection = remember(barSpacePx) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    searchBarOffsetY = (searchBarOffsetY + consumed.y).roundToInt().coerceIn(-barSpacePx, 0)
                    return Offset.Zero
                }
            }
        }
        val shown = remember(entries, keyword) {
            val term = keyword.trim()
            if (term.isEmpty()) entries else entries.filter { it.title.orEmpty().contains(term, ignoreCase = true) || it.titleJpn.orEmpty().contains(term, ignoreCase = true) }
        }
        val removeLabel = galleryString(Res.string.gallery_history_remove)
        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(barConnection),
            contentPadding = PaddingValues(top = barSpace),
            verticalArrangement = Arrangement.spacedBy(GalleryListInterval),
        ) {
            items(shown, key = { it.gid }) { gallery ->
                val dismissState = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {},
                    enableDismissFromStartToEnd = false,
                    onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) forget(gallery) },
                ) {
                    GallerySearchCard(
                        gallery,
                        covers,
                        Modifier.padding(horizontal = marginH).semantics {
                            customActions = listOf(CustomAccessibilityAction(removeLabel) { forget(gallery).let { true } })
                        },
                        downloaded = gallery.gid in downloadedGids,
                    ) { onOpen(gallery) }
                }
            }
        }
        when {
            loading -> Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            failed -> Surface(Modifier.fillMaxSize()) {
                GalleryErrorTip(galleryString(Res.string.gallery_history_unavailable), Modifier.widthIn(max = 228.dp).clip(ShapeDefaults.Small).clickable(onClickLabel = galleryString(Res.string.gallery_retry), role = Role.Button) { attempt++ })
            }
            shown.isEmpty() -> Column(
                Modifier.padding(top = barSpace).padding(horizontal = marginH).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(GalleryIcons.History, contentDescription = null, modifier = Modifier.padding(16.dp).size(120.dp), tint = MaterialTheme.colorScheme.primary)
                Text(galleryString(if (keyword.isBlank()) Res.string.gallery_history_empty else Res.string.gallery_no_galleries), style = MaterialTheme.typography.headlineMedium)
            }
        }
        val title = galleryString(Res.string.gallery_history)
        GallerySearchBar(
            query = keyword,
            onQueryChange = { keyword = it.take(4096) },
            onSearch = {},
            maxWidth = maxWidth,
            title = title,
            hint = galleryString(Res.string.gallery_search_in, title),
            leading = leading,
            modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, searchBarOffsetY) },
        ) {
            IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty() && !loading) {
                Icon(GalleryIcons.ClearAll, contentDescription = galleryString(Res.string.gallery_clear_all))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    change {
                        store.clear()
                        entries = emptyList()
                    }
                }) { Text(galleryString(Res.string.gallery_clear_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(galleryString(Res.string.gallery_cancel)) } },
            text = { Text(galleryString(Res.string.gallery_clear_all_history)) },
        )
    }
}
