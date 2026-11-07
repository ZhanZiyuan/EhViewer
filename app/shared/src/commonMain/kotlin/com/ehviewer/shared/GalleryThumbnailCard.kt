package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo

/** v1.15.10 GalleryInfoGridItem: proportional cover, category badge and favorite corner. */
@Composable
internal fun GalleryThumbnailCard(
    gallery: BaseGalleryInfo,
    covers: GalleryCoverCache<CoilImage>,
    modifier: Modifier = Modifier,
    presentation: GalleryListPresentation = GalleryListPresentation(),
    readerState: ReaderStateStore? = null,
    onOpen: () -> Unit,
) {
    val request = gallery.thumbKey?.takeIf { it.isNotBlank() }?.let { GalleryCoverRequest(gallery.gid, gallery.token, it) }
    var ratio by remember(gallery.gid, request) {
        mutableFloatStateOf(
            if (gallery.thumbWidth > 0 && gallery.thumbHeight > 0) {
                (gallery.thumbWidth.toFloat() / gallery.thumbHeight).coerceIn(0.5f, 1.5f)
            } else {
                GALLERY_COVER_RATIO
            },
        )
    }
    ElevatedCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = gallery.title ?: gallery.titleJpn.orEmpty()
        },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
            GalleryCoverImage(request, covers, onDecoded = { width, height ->
                if (width > 0 && height > 0) ratio = (width.toFloat() / height).coerceIn(0.5f, 1.5f)
            })
            val category = galleryCategoryColor(gallery.category)
            Badge(
                modifier = Modifier.align(Alignment.TopEnd).widthIn(min = 32.dp).height(24.dp),
                containerColor = category,
                contentColor = galleryCategoryTextColor(category),
            ) {
                val language = gallery.simpleLanguage
                galleryCardPageCount(gallery, presentation, readerState)?.let { count ->
                    Text(count)
                    if (language != null) Spacer(Modifier.width(4.dp))
                }
                if (language != null) Text(language)
            }
            if (gallery.favoriteSlot != GalleryInfo.NOT_FAVORITED) {
                Icon(GalleryIcons.Favorite, contentDescription = null, tint = Color(0xffff3040), modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
            }
        }
    }
}
