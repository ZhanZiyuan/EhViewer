package com.ehviewer.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.ehviewer.core.network.gallery.GallerySearchFilters
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercise actual product input dialogs: compiling alone misses Material/Foundation style ABI errors. */
class GalleryInputDialogRenderTest {
    private fun render(content: @Composable () -> Unit) {
        val scene = ImageComposeScene(411, 720, Density(1f)) {
            MaterialTheme(colorScheme = galleryFallbackColorScheme(dark = false)) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        try {
            repeat(3) {
                val image = scene.render(it * 16_000_000L)
                assertEquals(411, image.width)
                assertEquals(720, image.height)
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun readerPageJumpRendersItsNumberInput() = render {
        GalleryReaderPageDialog(0, 3, onJump = { error("Rendering must not navigate") }, onDismiss = {})
    }

    @Test
    fun searchFiltersRenderTheirTextInputsAndCategoryControls() = render {
        GallerySearchFiltersDialog(GallerySearchFilters(), onApply = { error("Rendering must not apply filters") }, onDismiss = {})
    }
}
