package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

class GalleryDetailRatingTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun actualStarsAndCaptionRemainCenteredAndUnclippedAcrossWindowAndFontSizes() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for (width in listOf(320, 411, 1280)) {
                for (font in listOf(1f, 1.8f)) {
                    for (dark in listOf(false, true)) {
                        val scene = ImageComposeScene(width, 600, Density(1f, font)) {
                            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                                Column(Modifier.padding(horizontal = 16.dp)) { GalleryDetailRating(2.69f, 8) }
                            }
                        }
                        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                        try {
                            repeat(3) { scene.render(it * 16_666_667L).close() }
                            val caption = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single {
                                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "MEDIOCRE (2.69, 8)" }
                            }.boundsInRoot
                            assertTrue(caption.left >= 16 && caption.right <= width - 16 && caption.bottom <= 600, "Caption clipped at $width/$font/$dark: $caption")
                            val image = scene.render(100_000_000)
                            val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)) }
                            try {
                                image.readPixels(bitmap)
                                val columns = (0 until width).filter { x -> (0 until caption.top.toInt()).any { y -> bitmap.getColor(x, y) == 0xfff9a825.toInt() } }
                                assertTrue(columns.isNotEmpty(), "The real yellow stars must render")
                                val groups = columns.zipWithNext().count { (a, b) -> b > a + 1 } + 1
                                assertEquals(5, groups, "All five distinct stars must remain visible at $width/$font/$dark")
                                assertTrue(columns.first() >= 24 && columns.last() < width - 24, "Stars clip the card padding")
                                // The original row includes its last 12dp spacer; keep that original alignment.
                                assertTrue(kotlin.math.abs((columns.first() + columns.last()) / 2f - (width / 2f - 6)) <= 3, "Original centered star row moved")
                            } finally {
                                bitmap.close()
                                image.close()
                            }
                        } finally {
                            scene.close()
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
