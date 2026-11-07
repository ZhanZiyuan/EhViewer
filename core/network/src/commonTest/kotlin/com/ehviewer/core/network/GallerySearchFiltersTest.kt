package com.ehviewer.core.network

import com.ehviewer.core.network.gallery.GalleryCategory
import com.ehviewer.core.network.gallery.GalleryListDecoder
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.gallery.GallerySearchFilters
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.session.PersistentCookieStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class GallerySearchFiltersTest {
    private val decoder = GalleryListDecoder { GallerySearchResult(null, null, emptyList()) }

    private suspend fun request(filters: GallerySearchFilters, restricted: Boolean = false, cursor: String? = null, previous: Boolean = false): Url {
        var actual: Url? = null
        val engine = MockEngine {
            actual = it.url
            respond("<!DOCTYPE html><html></html>")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            GalleryRepository(client, decoder, restricted).search("日本語 & next=untrusted", cursor, previous, filters)
            return requireNotNull(actual)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun explicitFiltersUseTheOriginalServiceProtocolAndEncodedQuery() = runTest {
        val filters = GallerySearchFilters(
            includedCategories = GalleryCategory.MANGA.mask or GalleryCategory.NON_H.mask,
            minimumRating = 4, minimumPages = 12, maximumPages = 89,
            showExpunged = true, onlyWithTorrents = true,
            disableLanguageFilter = true, disableUploaderFilter = true, disableTagFilter = true,
        )
        val url = request(filters)
        assertEquals("https", url.protocol.name)
        assertEquals("e-hentai.org", url.host)
        assertEquals("日本語 & next=untrusted", url.parameters["f_search"])
        assertNull(url.parameters["next"])
        assertEquals("763", url.parameters["f_cats"])
        assertEquals("1", url.parameters["advsearch"])
        for (flag in listOf("f_sh", "f_sto", "f_sfl", "f_sfu", "f_sft")) assertEquals("on", url.parameters[flag])
        assertEquals("4", url.parameters["f_srdd"])
        assertEquals("12", url.parameters["f_spf"])
        assertEquals("89", url.parameters["f_spt"])
    }

    @Test fun defaultFiltersPreserveSitePreferencesAndDoNotEnableAdvancedSearch() = runTest {
        val url = request(GallerySearchFilters())
        assertEquals(setOf("f_search"), url.parameters.names())
    }

    @Test fun categoriesHaveIndependentBitsAndAllSelectionDoesNotInventAnAdvancedFlag() = runTest {
        assertEquals(GallerySearchFilters.ALL_CATEGORIES, GalleryCategory.entries.fold(0) { mask, category -> mask or category.mask })
        assertEquals(10, GalleryCategory.entries.map { it.mask }.toSet().size)
        for (category in GalleryCategory.entries) {
            val url = request(GallerySearchFilters(includedCategories = category.mask))
            assertEquals((0x3ff xor category.mask).toString(), url.parameters["f_cats"])
            assertNull(url.parameters["advsearch"])
        }
        assertEquals("0", request(GallerySearchFilters(includedCategories = 0x3ff)).parameters["f_cats"])
    }

    @Test fun independentNumericBoundsAndCursorsRemainScopedToTheSelectedService() = runTest {
        val next = request(GallerySearchFilters(minimumPages = 1), restricted = true, cursor = "42-43")
        assertEquals("exhentai.org", next.host)
        assertEquals("42-43", next.parameters["next"])
        assertNull(next.parameters["prev"])
        assertEquals("1", next.parameters["f_spf"])
        assertNull(next.parameters["f_spt"])
        val previous = request(GallerySearchFilters(maximumPages = Int.MAX_VALUE, minimumRating = 2), cursor = "12", previous = true)
        assertEquals("12", previous.parameters["prev"])
        assertNull(previous.parameters["next"])
        assertNull(previous.parameters["f_spf"])
        assertEquals(Int.MAX_VALUE.toString(), previous.parameters["f_spt"])
        assertEquals("2", previous.parameters["f_srdd"])
    }

    @Test fun invalidSelectionsAndRangesFailBeforeTheyCanBecomeRequests() {
        for (mask in listOf(-1, 0, 0x400, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { GallerySearchFilters(includedCategories = mask) }
        for (rating in listOf(-1, 0, 1, 6, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { GallerySearchFilters(minimumRating = rating) }
        for (pages in listOf(Int.MIN_VALUE, -1, 0)) {
            assertFailsWith<IllegalArgumentException> { GallerySearchFilters(minimumPages = pages) }
            assertFailsWith<IllegalArgumentException> { GallerySearchFilters(maximumPages = pages) }
        }
        assertFailsWith<IllegalArgumentException> { GallerySearchFilters(minimumPages = 12, maximumPages = 11) }
        assertFailsWith<IllegalArgumentException> { GallerySearchFilters().copy(minimumRating = 6) }
    }

    @Test fun savedUiFiltersRoundTripAndCorruptNumericSelectionsAreRejected() {
        val filters = GallerySearchFilters(includedCategories = 0x100, minimumRating = 5, minimumPages = 1, maximumPages = 1, showExpunged = true, disableTagFilter = true)
        assertEquals(filters, Json.decodeFromString<GallerySearchFilters>(Json.encodeToString(filters)))
        assertEquals(GallerySearchFilters(), Json.decodeFromString<GallerySearchFilters>("{}"))
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString<GallerySearchFilters>("{\"minimumRating\":6}") }
    }
}
