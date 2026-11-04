package com.ehviewer.shared

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_refresh

/** v1.15.10's two-line, collapsing detail title; host safe-drawing insets apply exactly once. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GalleryDetailAppBar(title: String, backLabel: String, onBack: () -> Unit, onRefresh: () -> Unit, refreshEnabled: Boolean, scrollBehavior: TopAppBarScrollBehavior) {
    MediumFlexibleTopAppBar(
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(GalleryIcons.ArrowBack, backLabel) }
        },
        actions = {
            IconButton(onClick = onRefresh, enabled = refreshEnabled, modifier = Modifier.size(48.dp)) { Icon(GalleryIcons.Refresh, galleryString(Res.string.gallery_refresh)) }
        },
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets(0),
    )
}
