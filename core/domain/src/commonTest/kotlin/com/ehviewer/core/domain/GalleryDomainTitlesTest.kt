package com.ehviewer.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GalleryDomainTitlesTest {
    @Test
    fun preferredMetadataRetainsUnicodeAndWhitespace() {
        for (title in listOf("マンガ 📚", "Cafe\u0301", "  title\n", "\t")) {
            assertEquals(title, selectGalleryTitle(title, "fallback", false))
            assertEquals(title, selectGalleryTitle("fallback", title, true))
        }
        assertNotEquals("Café", selectGalleryTitle("Cafe\u0301", null, false))
    }

    @Test
    fun missingPreferredMetadataUsesTheOtherLanguage() {
        for (missing in listOf(null, "")) {
            assertEquals("日本語 📚", selectGalleryTitle(missing, "日本語 📚", false))
            assertEquals("English", selectGalleryTitle("English", missing, true))
        }
    }

    @Test
    fun absentMetadataProducesTheExistingEmptyTitle() {
        for (title in listOf(null, "")) {
            for (japanese in listOf(null, "")) {
                for (preferJapanese in listOf(false, true)) {
                    assertEquals("", selectGalleryTitle(title, japanese, preferJapanese))
                }
            }
        }
    }
}
