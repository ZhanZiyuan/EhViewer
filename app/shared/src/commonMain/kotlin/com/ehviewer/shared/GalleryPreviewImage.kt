package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_preview_page
import ehviewer.app.shared.generated.resources.gallery_preview_retry
import kotlinx.coroutines.CancellationException

@Composable
internal fun rememberGalleryPreviewCache(session: GalleryLibrary, gid: Long, token: String): GalleryPreviewCache<ImageBitmap> = remember(session, gid, token) {
    GalleryPreviewCache { preview ->
        val bitmap = decodeGalleryImage(session.content.preview(preview, gid, token))
        PreviewBitmap(bitmap, bitmap.width, bitmap.height)
    }
}

internal fun galleryPreviewContentScale(preview: GalleryPreview, width: Int, height: Int): ContentScale = if (preview is V2GalleryPreview) galleryImageContentScale(preview.clipWidth, preview.clipHeight) else galleryImageContentScale(width, height)

/** Original Android CropDefaults: keep square, wide and unusually tall images fully visible. */
internal fun galleryImageContentScale(width: Int, height: Int): ContentScale = if (width.toFloat() / height in 0.5f..0.8f) ContentScale.Crop else ContentScale.Fit

@Composable
internal fun GalleryPreviewImage(preview: GalleryPreview, cache: GalleryPreviewCache<ImageBitmap>, modifier: Modifier = Modifier.height(180.dp)) {
    var painter by remember(preview, cache) { mutableStateOf<BitmapPainter?>(null) }
    var failure by remember(preview, cache) { mutableStateOf<GalleryFailure?>(null) }
    var attempt by remember(preview, cache) { mutableIntStateOf(0) }
    var scale by remember(preview, cache) { mutableStateOf(ContentScale.Fit) }
    LaunchedEffect(preview, cache, attempt) {
        failure = null
        try {
            val bitmap = cache.get(preview)
            val region = previewRegion(preview, bitmap.width, bitmap.height)
            scale = galleryPreviewContentScale(preview, bitmap.width, bitmap.height)
            painter = BitmapPainter(bitmap.value, IntOffset(region.x, 0), IntSize(region.width, region.height))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val image = painter
        val error = failure
        if (image != null) {
            Image(image, galleryString(Res.string.gallery_preview_page, preview.position + 1), Modifier.fillMaxSize(), contentScale = scale)
        } else if (error != null) {
            TextButton(onClick = { attempt++ }) { Text(galleryString(Res.string.gallery_preview_retry, error.userMessage())) }
        } else {
            CircularProgressIndicator()
        }
    }
}
