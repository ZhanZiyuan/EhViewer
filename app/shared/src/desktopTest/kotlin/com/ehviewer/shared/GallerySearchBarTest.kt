package com.ehviewer.shared

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the real shared search bar headlessly and checks Android's SearchBarScreen geometry. */
class GallerySearchBarTest {
    private fun bar(width: Int): Pair<Int, IntSize> {
        var left = -1
        var size = IntSize.Zero
        val scene = ImageComposeScene(width, 720, Density(1f)) {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    GallerySearchBar(
                        "",
                        {},
                        {},
                        maxWidth,
                        filtered = false,
                        onFilters = {},
                        leading = {},
                        modifier = Modifier.align(Alignment.TopCenter).onGloballyPositioned {
                            left = it.positionInRoot().x.roundToInt()
                            size = it.size
                        },
                    )
                }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L) }
        } finally {
            scene.close()
        }
        return left to size
    }

    @Test
    fun phonesKeepSixteenDpMarginsAndWideWindowsCapAt720() {
        for ((window, expected) in listOf(411 to 379, 600 to 568, 752 to 720, 1280 to 720)) {
            val (left, size) = bar(window)
            assertEquals(expected, size.width, "bar width in a $window window")
            assertEquals((window - expected) / 2, left, "bar is centered in a $window window")
            assertTrue(size.height >= 56, "bar height ${size.height} in a $window window")
        }
    }
}
