package com.ehviewer.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.collectLatest

/** Original PagerViewer uses this pager and its default snap/fling behavior. */
@Composable
internal fun GalleryReaderPagedCanvas(index: Int, pages: Int, rtl: Boolean, enabled: Boolean, onPageRequested: (Int) -> Boolean, content: @Composable (Int) -> Unit) {
    val current by rememberUpdatedState(index)
    val request by rememberUpdatedState(onPageRequested)
    val canRequest by rememberUpdatedState(enabled)
    val pager = rememberPagerState(initialPage = index) { pages }
    LaunchedEffect(index, pages) {
        if (pager.currentPage != index || pager.settledPage != index) pager.scrollToPage(index)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collectLatest { selected ->
            if (selected != current && (!canRequest || !request(selected))) {
                // A missing/invalid source link cannot invent a new page or progress.
                pager.animateScrollToPage(current)
            }
        }
    }
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        reverseLayout = rtl xor (LocalLayoutDirection.current == LayoutDirection.Rtl),
        userScrollEnabled = enabled,
        key = { it },
        pageContent = { content(it) },
    )
}
