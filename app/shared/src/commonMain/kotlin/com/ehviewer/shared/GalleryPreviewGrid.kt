package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.GalleryPreview
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_page_number

/** Preserve v1.15.10's three phone columns and 0.67 thumbnail ratio; widen the grid on large surfaces. */
internal fun galleryPreviewColumns(width: Dp): Int = if (width < 600.dp) 3 else (width / 128.dp).toInt().coerceIn(3, 12)

@Composable
internal fun GalleryPreviewCell(preview: GalleryPreview, cache: GalleryPreviewCache<ImageBitmap>, onOpen: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val page = galleryString(Res.string.gallery_page_number, preview.position + 1)
    Column(modifier.padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedCard(onClick = onOpen, enabled = enabled, border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(Color.Transparent)), modifier = Modifier.fillMaxWidth().aspectRatio(0.67f).semantics { stateDescription = page }) {
            GalleryPreviewImage(preview, cache, Modifier.fillMaxSize())
        }
        Text("${preview.position + 1}", style = MaterialTheme.typography.bodyLarge)
    }
}
