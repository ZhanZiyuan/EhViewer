package com.ehviewer.shared

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class GalleryListStyleTest {
    @Test
    fun categoryNamesMatchAndroid() {
        val android = mapOf(
            0x1 to "misc", 0x2 to "doujinshi", 0x4 to "manga", 0x8 to "artistcg", 0x10 to "gamecg", 0x20 to "imageset",
            0x40 to "cosplay", 0x80 to "asianporn", 0x100 to "non-h", 0x200 to "western", 0x400 to "private", 0x800 to "unknown",
        )
        android.forEach { (category, name) -> assertEquals(name, galleryCategoryName(category)) }
        assertEquals("unknown", galleryCategoryName(0x3))
    }

    @Test
    fun listCardsUseAndroidHeights() {
        // 420dpi phone, 320dpi tablet and the floor/cap at other densities.
        assertEquals(360 / 2.625f, galleryListCardHeight(Density(2.625f)).value, 0.001f)
        assertEquals(180.dp, galleryListCardHeight(Density(2f)))
        assertEquals(120.dp, galleryListCardHeight(Density(3.5f)))
        assertEquals(180.dp, galleryListCardHeight(Density(1f)))
    }
}
