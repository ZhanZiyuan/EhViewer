package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cover_loading
import ehviewer.app.shared.generated.resources.gallery_cover_retry
import ehviewer.app.shared.generated.resources.gallery_cover_unavailable
import ehviewer.app.shared.generated.resources.gallery_downloaded
import kotlinx.coroutines.CancellationException

/**
 * Android 1.15.10's list card (GalleryInfoListItem): borderless outlined card, 0.67 cover, two-line
 * title, then uploader/favorite, rating/language/pages and category/posted rows in labelLarge.
 */
@Composable
internal fun GallerySearchCard(gallery: BaseGalleryInfo, cache: GalleryCoverCache<CoilImage>, modifier: Modifier = Modifier, downloaded: Boolean = false, onOpen: () -> Unit) {
    val cover = gallery.thumbKey?.takeIf { it.isNotBlank() }?.let { GalleryCoverRequest(gallery.gid, gallery.token, it) }
    val height = galleryListCardHeight(LocalDensity.current)
    OutlinedCard(onClick = onOpen, modifier = modifier.fillMaxWidth().height(height), border = galleryCrystalBorder()) {
        Row {
            Card(Modifier.aspectRatio(GALLERY_COVER_RATIO)) { GalleryCoverImage(cover, cache) }
            Column(Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp, bottom = 4.dp)) {
                Text(gallery.title ?: gallery.titleJpn.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(gallery.uploader.orEmpty(), Modifier.alignByBaseline().alpha(if (gallery.disowned) 0.5f else 1f))
                        Spacer(Modifier.weight(1f))
                        if (gallery.favoriteSlot != GalleryInfo.NOT_FAVORITED) {
                            Icon(GalleryIcons.Favorite, contentDescription = null, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                            gallery.favoriteName?.let { Text(it, Modifier.alignByBaseline()) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GalleryRating(gallery.rating, Modifier.padding(top = 1.dp, bottom = 3.dp))
                        Spacer(Modifier.weight(1f))
                        if (downloaded) Icon(GalleryIcons.Download, contentDescription = galleryString(Res.string.gallery_downloaded), modifier = Modifier.size(16.dp))
                        gallery.simpleLanguage?.let { Text(it) }
                        if (gallery.pages != 0) Text("${gallery.pages}P")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val categoryColor = galleryCategoryColor(gallery.category)
                        Text(
                            galleryCategoryName(gallery.category).uppercase(),
                            Modifier.clip(ShapeDefaults.Small).background(categoryColor).padding(vertical = 2.dp, horizontal = 8.dp),
                            color = galleryCategoryTextColor(categoryColor),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(gallery.posted.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
internal fun GalleryCoverImage(request: GalleryCoverRequest?, cache: GalleryCoverCache<CoilImage>) {
    var image by remember(request, cache) { mutableStateOf<CoilImage?>(null) }
    var failed by remember(request, cache) { mutableStateOf(false) }
    var attempt by remember(request, cache) { mutableIntStateOf(0) }
    LaunchedEffect(request, cache, attempt) {
        if (request == null) return@LaunchedEffect
        failed = false
        try {
            image = cache.get(request).value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryRequestException) {
            failed = true
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            val context = LocalPlatformContext.current
            Image(remember(bitmap, context) { bitmap.asPainter(context) }, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (request == null) {
            Text(galleryString(Res.string.gallery_cover_unavailable), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))
        } else if (failed) {
            TextButton(onClick = { attempt++ }) { Text(galleryString(Res.string.gallery_cover_retry), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
        } else {
            val loading = galleryString(Res.string.gallery_cover_loading)
            CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = loading })
        }
    }
}
