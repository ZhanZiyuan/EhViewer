package com.ehviewer.shared

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ehviewer.core.network.gallery.GalleryFailure
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/** Renders the shared ErrorTip headlessly and checks Android's centered 120dp tertiary icon. */
class GalleryErrorTipTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun queryAdviceUsesTheOriginalErrorPageAndWrapsAtLargeFontsWithoutClipping() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for (width in listOf(320, 411, 1280)) {
                val scene = ImageComposeScene(width, 1000, Density(1f, 1.8f)) {
                    GalleryTheme(false, null) { GalleryErrorTip(GalleryFailure.SEARCH_QUERY.userMessage(), Modifier.widthIn(max = 228.dp)) }
                }
                try {
                    repeat(8) { scene.render(it * 16_666_667L).close() }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    val text = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.startsWith("You provided too many") == true }
                    assertEquals("You provided too many keywords. Use fewer keywords, or combine keywords with quotes.", text.config[SemanticsProperties.Text].single().text)
                    val bounds = text.boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 1000, "Query advice clipped at $width: $bounds")
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(before)
        }
    }

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
