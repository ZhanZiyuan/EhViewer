package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlinx.coroutines.CancellationException

@Composable
internal fun GalleryPreviewImage(preview: GalleryPreview, cache: GalleryPreviewCache<ImageBitmap>) {
    var painter by remember(preview, cache) { mutableStateOf<BitmapPainter?>(null) }
    var failure by remember(preview, cache) { mutableStateOf<GalleryFailure?>(null) }
    var attempt by remember(preview, cache) { mutableIntStateOf(0) }
    LaunchedEffect(preview, cache, attempt) {
        failure = null
        try {
            val bitmap = cache.get(preview)
            val region = previewRegion(preview, bitmap.width, bitmap.height)
            painter = BitmapPainter(bitmap.value, IntOffset(region.x, 0), IntSize(region.width, region.height))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        }
    }
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        val image = painter
        val error = failure
        if (image != null) {
            Image(image, "Preview of page ${preview.position + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
        } else if (error != null) {
            TextButton(onClick = { attempt++ }) { Text("${error.userMessage()} Retry preview") }
        } else {
            CircularProgressIndicator()
        }
    }
}
