package com.ehviewer.shared

import androidx.compose.material3.CardDefaults
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import coil3.PlatformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Actual Coil decode and common Compose pixels, rather than a second copy of the crop predicate. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class GalleryCoverPresentationTest {
    private val red = 0xffff0000.toInt()
    private val green = 0xff00ff00.toInt()
    private val blue = 0xff0000ff.toInt()

    private fun ownedEdgeBands(width: Int, height: Int): ByteArray {
        val surface = checkNotNull(Surface.makeRasterN32Premul(width, height))
        try {
            surface.canvas.clear(green)
            Paint().use { paint ->
                paint.color = red
                surface.canvas.drawRect(if (width >= height) Rect.makeXYWH(0f, 0f, width * 0.2f, height.toFloat()) else Rect.makeXYWH(0f, 0f, width.toFloat(), height * 0.2f), paint)
                paint.color = blue
                surface.canvas.drawRect(if (width >= height) Rect.makeXYWH(width * 0.8f, 0f, width * 0.2f, height.toFloat()) else Rect.makeXYWH(0f, height * 0.8f, width.toFloat(), height * 0.2f), paint)
            }
            surface.makeImageSnapshot().use { image ->
                checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { return it.bytes }
            }
        } finally {
            surface.close()
        }
    }

    @Test fun decodedSquareWideAndTallCoversRetainTheirRealEdgePixelsAndOriginalCardBackground() = runTest {
        for ((width, height) in listOf(100 to 100, 200 to 100, 100 to 300, 100 to 200)) {
            for (dark in listOf(false, true)) {
                val request = GalleryCoverRequest(1, "0123456789", "owned-$width-$height")
                var loads = 0
                val loader = GalleryCoverLoader(PlatformContext.INSTANCE) {
                    loads++
                    ownedEdgeBands(width, height)
                }
                try {
                    val cache = GalleryCoverCache(load = loader::get)
                    cache.get(request)
                    val scheduler = TestCoroutineScheduler()
                    var background = 0
                    val scene = ImageComposeScene(67, 100, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
                        GalleryTheme(dark, null) {
                            background = CardDefaults.cardColors().containerColor.toArgb()
                            GalleryCoverImage(request, cache)
                        }
                    }
                    try {
                        repeat(8) { frame ->
                            scheduler.runCurrent()
                            scene.render(frame * 16_666_667L).close()
                            scheduler.runCurrent()
                        }
                        Bitmap().use { pixels ->
                            pixels.allocN32Pixels(67, 100)
                            scene.render(9 * 16_666_667L).use { it.readPixels(pixels) }
                            assertEquals(green, pixels.getColor(33, 50), "Actual decoded center: $width/$height/$dark")
                            when {
                                width >= height -> {
                                    assertEquals(red, pixels.getColor(3, 50), "The original left edge must remain visible")
                                    assertEquals(blue, pixels.getColor(63, 50), "The original right edge must remain visible")
                                    assertEquals(background, pixels.getColor(33, 3), "Letterbox must match the original filled Card")
                                }
                                height == 300 -> {
                                    assertEquals(red, pixels.getColor(33, 3), "The original top edge must remain visible")
                                    assertEquals(blue, pixels.getColor(33, 97), "The original bottom edge must remain visible")
                                    assertEquals(background, pixels.getColor(3, 50))
                                }
                                else -> assertEquals(green, pixels.getColor(3, 50), "Ordinary portrait covers still fill the original crop frame")
                            }
                        }
                        assertEquals(1, loads, "Displaying a cached cover must not refetch it")
                    } finally {
                        scene.close()
                    }
                } finally {
                    loader.close()
                }
            }
        }
    }
}
