package com.ehviewer.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class GalleryTitlesTest {
    @Test
    fun selectsRequestedLanguage() {
        assertEquals("日本語", selectGalleryTitle("English", "日本語", true))
        assertEquals("English", selectGalleryTitle("English", "日本語", false))
    }

    @Test
    fun emptyOrMissingPreferredTitleFallsBack() {
        for (empty in listOf(null, "")) {
            assertEquals("English", selectGalleryTitle("English", empty, true))
            assertEquals("日本語", selectGalleryTitle(empty, "日本語", false))
            assertEquals("", selectGalleryTitle(empty, null, false))
            assertEquals("", selectGalleryTitle(null, empty, true))
        }
    }

    @Test
    fun whitespaceIsPreservedForCompatibility() {
        assertEquals(" ", selectGalleryTitle(" ", "日本語", false))
        assertEquals("\t", selectGalleryTitle("English", "\t", true))
    }
}
