package com.ehviewer.shared

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/** Renders the shared ErrorTip headlessly and checks Android's centered 120dp tertiary icon. */
class GalleryErrorTipTest {
    @Test
    fun theSadAndroidIsCenteredAboveTheMessageInTheTertiaryColor() {
        val tertiary = galleryFallbackColorScheme(dark = false).tertiary.toArgb()
        val scene = ImageComposeScene(411, 720, Density(1f)) {
            MaterialTheme(colorScheme = galleryFallbackColorScheme(dark = false)) {
                GalleryErrorTip("No galleries were found.", Modifier.widthIn(max = 228.dp))
            }
        }
        val image = try {
            scene.render()
        } finally {
            scene.close()
        }
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)) }
        image.readPixels(bitmap)
        val icon = (0 until image.height).flatMap { y -> (0 until image.width).filter { x -> bitmap.getColor(x, y) == tertiary }.map { x -> x to y } }
        assertTrue(icon.isNotEmpty(), "tertiary icon pixels")
        val left = icon.minOf { it.first }
        val right = icon.maxOf { it.first }
        val top = icon.minOf { it.second }
        val bottom = icon.maxOf { it.second }
        // The 24-unit path spans about 20 units horizontally, so a 120dp icon draws about 100px wide.
        assertTrue(right - left in 90..120, "icon width ${right - left}")
        assertTrue(kotlin.math.abs((left + right) / 2 - 411 / 2) <= 2, "icon centered: $left..$right")
        assertTrue(bottom < 720 / 2 + 40, "icon sits above the vertically centered message: $top..$bottom")
    }
}
