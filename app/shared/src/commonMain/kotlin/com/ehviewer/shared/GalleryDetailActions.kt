package com.ehviewer.shared

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_detail_not_favorited
import ehviewer.app.shared.generated.resources.gallery_detail_similar
import ehviewer.app.shared.generated.resources.gallery_favorite_add
import ehviewer.app.shared.generated.resources.gallery_favorite_edit
import ehviewer.app.shared.generated.resources.gallery_info_favorite_name
import ehviewer.app.shared.generated.resources.gallery_torrent_count

/** Compact original detail actions, backed by the existing confirmed-state favorite editor. */
@Composable
internal fun GalleryDetailActions(detail: GalleryDetail, onFavorite: () -> Unit, onSimilar: (GallerySimilarSearch) -> Unit, onTorrents: () -> Unit) {
    val favorited = detail.favoriteSlot != GalleryInfo.NOT_FAVORITED
    val similar = similarGallerySearch(detail)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconToggleButton(
                checked = favorited,
                // Do not optimistically toggle: only the server-confirmed refreshed detail changes it.
                onCheckedChange = { onFavorite() },
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, checkedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(if (favorited) GalleryIcons.Favorite else GalleryIcons.FavoriteBorder, galleryString(if (favorited) Res.string.gallery_favorite_edit else Res.string.gallery_favorite_add))
            }
            Text(if (favorited) detail.favoriteName ?: galleryString(Res.string.gallery_info_favorite_name) else galleryString(Res.string.gallery_detail_not_favorited))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(onClick = { similar?.let(onSimilar) }, enabled = similar != null, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Icon(GalleryIcons.Search, galleryString(Res.string.gallery_detail_similar))
            }
            Text(galleryString(Res.string.gallery_detail_similar))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val label = galleryString(Res.string.gallery_torrent_count, detail.torrentCount)
            FilledIconButton(onClick = onTorrents, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Icon(GalleryIcons.Magnet, label)
            }
            Text(label)
        }
    }
}
