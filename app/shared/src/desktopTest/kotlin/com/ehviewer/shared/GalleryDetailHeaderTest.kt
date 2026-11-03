package com.ehviewer.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import coil3.Image as CoilImage
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GalleryDetailHeaderTest {
    private fun detail() = GalleryDetail(
        galleryInfo = BaseGalleryInfo(gid = 1, token = "0123456789", title = "A gallery", category = 4, uploader = "Long uploader name ".repeat(30), pages = 12345),
        apiUid = 900,
        apiKey = "private-api-credential",
        language = "English",
        size = "32.0 MiB",
        tagGroups = emptyList(),
        comments = GalleryCommentList(emptyList(), false),
        previewList = emptyList(),
    )

    @Test
    fun phoneAndWideActionsRemainReachableAtLargeFontSizes() {
        for (width in listOf(360, 411, 1280)) {
            for (fontScale in listOf(1f, 1.8f)) {
                var read = Rect.Zero
                var download = Rect.Zero
                val cache = GalleryCoverCache<CoilImage>(load = { error("No thumbnail was supplied") })
                val scene = ImageComposeScene(width, 720, Density(1f, fontScale)) {
                    MaterialTheme {
                        GalleryDetailHeaderSection(detail(), cache, {}, {}, {}, download = {
                            FilledTonalButton({}, modifier = it.onGloballyPositioned { download = it.boundsInRoot() }) { Text("Download", maxLines = 1) }
                        }, read = {
                            Button({}, modifier = it.onGloballyPositioned { read = it.boundsInRoot() }) { Text("Read", maxLines = 1) }
                        })
                    }
                }
                try {
                    repeat(3) { scene.render(it * 16_000_000L) }
                } finally {
                    scene.close()
                }
                for (bounds in listOf(read, download)) {
                    assertTrue(bounds.width > 0 && bounds.height > 0, "Action missing at $width/$fontScale: $bounds")
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 720, "Action clipped at $width/$fontScale: $bounds")
                }
                assertFalse(read.overlaps(download), "Overlapping actions at $width/$fontScale")
                if (width == 1280) {
                    assertTrue(read.center.x > width / 2, "Wide-window actions must sit beside the header")
                    assertTrue(download.top > read.bottom, "Wide-window actions must stack")
                } else {
                    assertTrue(abs(read.top - download.top) <= 1, "Phone actions must share a row")
                    assertTrue(abs(read.width - download.width) <= 2, "Phone actions must share available width")
                }
            }
        }
    }

    @Test
    fun informationIncludesTheOriginalMetadataWithoutApiCredentials() {
        val fields = galleryInformationValues(detail(), "https://e-hentai.org/g/1/0123456789/", "https://ehgt.org/cover.webp")
        assertEquals(21, fields.size)
        assertTrue(fields.any { it.second == "12345" })
        assertFalse(fields.any { it.second.contains("private-api-credential") || it.second == "900" })
    }
}
