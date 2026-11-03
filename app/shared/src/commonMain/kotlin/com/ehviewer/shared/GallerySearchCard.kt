package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cover_loading
import ehviewer.app.shared.generated.resources.gallery_cover_retry
import ehviewer.app.shared.generated.resources.gallery_cover_unavailable
import ehviewer.app.shared.generated.resources.gallery_page_count
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/** One shared compact card: narrow screens have one column; wider screens use the same content. */
@Composable
internal fun GallerySearchCard(gallery: BaseGalleryInfo, cache: GalleryCoverCache<CoilImage>, onOpen: () -> Unit) {
    val cover = gallery.thumbKey?.takeIf { it.isNotBlank() }?.let { GalleryCoverRequest(gallery.gid, gallery.token, it) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryCoverImage(cover, cache)
            Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(gallery.title ?: gallery.titleJpn.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(galleryString(Res.string.gallery_page_count, gallery.pages), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    gallery.simpleLanguage?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    if (gallery.rating in 0f..5f) Text("★ ${(gallery.rating * 10).roundToInt() / 10f}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun GalleryCoverImage(request: GalleryCoverRequest?, cache: GalleryCoverCache<CoilImage>) {
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
    Box(Modifier.width(88.dp).height(128.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
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
