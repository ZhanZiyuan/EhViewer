package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_info
import ehviewer.app.shared.generated.resources.gallery_link_copy
import ehviewer.app.shared.generated.resources.gallery_link_open
import ehviewer.app.shared.generated.resources.gallery_link_share
import ehviewer.app.shared.generated.resources.gallery_more_options
import ehviewer.app.shared.generated.resources.gallery_refresh

/** v1.15.10's two-line, collapsing detail title; host safe-drawing insets apply exactly once. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GalleryDetailAppBar(title: String, backLabel: String, onBack: () -> Unit, onRefresh: () -> Unit, refreshEnabled: Boolean, scrollBehavior: TopAppBarScrollBehavior, onShare: () -> Unit, onCopyLink: () -> Unit, onOpenLink: () -> Unit, onInformation: () -> Unit, informationEnabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    BoxWithConstraints {
        val expanded = maxWidth >= 840.dp
        MediumFlexibleTopAppBar(
            title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(GalleryIcons.ArrowBack, backLabel) }
            },
            actions = {
                IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) { Icon(GalleryIcons.Share, galleryString(Res.string.gallery_link_share)) }
                if (expanded) IconButton(onClick = onRefresh, enabled = refreshEnabled, modifier = Modifier.size(48.dp)) { Icon(GalleryIcons.Refresh, galleryString(Res.string.gallery_refresh)) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(GalleryIcons.MoreVert, galleryString(Res.string.gallery_more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!expanded) {
                            DropdownMenuItem(text = { Text(galleryString(Res.string.gallery_refresh)) }, leadingIcon = { Icon(GalleryIcons.Refresh, null) }, enabled = refreshEnabled, onClick = {
                                menu = false
                                onRefresh()
                            })
                        }
                        DropdownMenuItem(text = { Text(galleryString(Res.string.gallery_link_copy)) }, onClick = {
                            menu = false
                            onCopyLink()
                        })
                        DropdownMenuItem(text = { Text(galleryString(Res.string.gallery_link_open)) }, onClick = {
                            menu = false
                            onOpenLink()
                        })
                        DropdownMenuItem(text = { Text(galleryString(Res.string.gallery_info)) }, enabled = informationEnabled, onClick = {
                            menu = false
                            onInformation()
                        })
                    }
                }
            },
            scrollBehavior = scrollBehavior,
            windowInsets = WindowInsets(0),
        )
    }
}
