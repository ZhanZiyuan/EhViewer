package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

class GalleryReaderChromeTest {
    @Test
    fun overlayControlsLeaveTheCanvasUnchangedAndReachableAtLargeFontSizes() {
        for (width in listOf(360, 411, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                val state = GalleryReaderChromeState()
                var canvas = Rect.Zero
                val buttons = mutableMapOf<Int, Rect>()
                val scene = ImageComposeScene(width, 720, Density(1f, font)) {
                    MaterialTheme {
                        GalleryReaderChrome("A long gallery title ".repeat(12), 12344, 12345, state, {}, {}, {}, navigation = {
                            repeat(2) { IconButton({}) {} }
                        }, actions = {
                            repeat(4) { index ->
                                Box(Modifier.onGloballyPositioned { buttons[index] = it.boundsInRoot() }) { GalleryReaderAction("Action $index", GalleryIcons.LibraryBooks) {} }
                            }
                        }) { Box(Modifier.fillMaxSize().onGloballyPositioned { canvas = it.boundsInRoot() }) }
                    }
                }
                try {
                    repeat(3) { scene.render(it * 16_000_000L).close() }
                    assertEquals(Rect(0f, 0f, width.toFloat(), 720f), canvas, "Hidden controls at $width/$font")
                    val image = scene.render(500_000_000)
                    try {
                        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)) }
                        try {
                            image.readPixels(bitmap)
                            assertEquals(0xff000000.toInt(), bitmap.getColor(width / 2, 360), "Default reading background")
                        } finally {
                            bitmap.close()
                        }
                    } finally {
                        image.close()
                    }
                    state.visible = true
                    // Advance real animation frames through the original 200ms slide transition.
                    repeat(30) { scene.render(1_000_000_000 + it * 16_666_667L).close() }
                    assertEquals(Rect(0f, 0f, width.toFloat(), 720f), canvas, "Showing controls must not resize images at $width/$font")
                    assertEquals(4, buttons.size)
                    for ((_, bounds) in buttons) assertTrue(bounds.width >= 48 && bounds.height >= 48 && bounds.left >= 0 && bounds.right <= width && bounds.bottom <= 720, "Clipped action at $width/$font: $bounds")
                    for (index in 0..2) assertFalse(buttons.getValue(index).overlaps(buttons.getValue(index + 1)), "Overlapping actions at $width/$font")
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun enteringTheReaderDropsTheRealLibraryContentWidthCapAndRestoresItOnExit() {
        var reading by mutableStateOf(false)
        var width = 0
        val chrome = GalleryReaderChromeState()
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            MaterialTheme {
                GalleryNavigationShell(GalleryDestination.entries, GalleryDestination.GALLERIES, {}) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(galleryPageModifier(searchBarPage = false, galleryIsImmersive()).onGloballyPositioned { width = it.size.width }) {
                            if (reading) {
                                GalleryImmersive()
                                GalleryReaderChrome("Gallery", 0, 1, chrome, {}, {}, {}, actions = {}) {}
                            }
                        }
                    }
                }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L).close() }
            val original = width
            assertTrue(original in 1..1040)
            reading = true
            repeat(4) { scene.render(100_000_000 + it * 16_000_000L).close() }
            assertEquals(1280, width)
            reading = false
            repeat(4) { scene.render(200_000_000 + it * 16_000_000L).close() }
            assertEquals(original, width)
        } finally {
            scene.close()
        }
    }
}
