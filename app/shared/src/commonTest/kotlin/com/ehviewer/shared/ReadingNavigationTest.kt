package com.ehviewer.shared

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.reader.ReadingPosition
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.network.download.CachedGalleryPages
import com.ehviewer.core.network.download.DownloadedGalleryContent
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.galleryHttpClient
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import com.ehviewer.shared.reader.CachedReaderStateStore
import com.ehviewer.shared.reader.ReaderStateRepository
import com.ehviewer.shared.reader.readingResumeUrl
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ReadingNavigationTest {
    @Test fun offlineReadingRoundTripPersistsOnlyAnIndexAndResolvesTheActualOnlineTokenAgain() = runTest {
        var persisted: ByteArray? = null
        val state = ReaderStateRepository(object : SessionVault {
            override suspend fun read() = persisted?.copyOf()
            override suspend fun write(bytes: ByteArray) {
                persisted = bytes.copyOf()
            }
            override suspend fun clear() {
                persisted = null
            }
        }) { 1234L }
        state.savePosition(1, 1, "2222222222")
        val offlineState = CachedReaderStateStore(state)
        val cached = DownloadedGalleryContent(object : CachedGalleryPages {
            override val gallery = GalleryDownload(1, "0123456789", "Owned", 6, false, GalleryDownloadState.COMPLETE, 6)
            override suspend fun read(index: Int): ByteArray = error("Navigation does not fetch images")
        })
        val metadata = cached.details(1, "0123456789")
        assertEquals("ehviewer-cache://gallery/1/1", readingResumeUrl(cached, metadata, offlineState.position(1)!!))
        assertEquals("2222222222", state.position(1)?.pageToken)
        offlineState.savePosition(1, 5, "0000000005")
        assertNull(state.position(1)?.pageToken)
        assertFalse(persisted!!.decodeToString().contains("ehviewer-cache"))
        val requests = mutableListOf<String>()
        val window = initial.copy(previewList = listOf(V1GalleryPreview("", 4, "eeeeeeeeee"), V1GalleryPreview("", 5, "ffffffffff")))
        withRepository(window, requests) { content ->
            assertEquals("https://e-hentai.org/s/ffffffffff/1-6", readingResumeUrl(content, initial, state.position(1)!!))
        }
        assertEquals(listOf("https://e-hentai.org/g/1/0123456789/?p=2"), requests)
    }

    private val initial = GalleryDetail(
        galleryInfo = BaseGalleryInfo(gid = 1, token = "0123456789", pages = 6),
        tagGroups = emptyList(),
        comments = GalleryCommentList(emptyList(), false),
        previewList = listOf(V1GalleryPreview("", 0, "1111111111"), V1GalleryPreview("", 1, "2222222222")),
    )

    private suspend fun withRepository(window: GalleryDetail = initial, requests: MutableList<String>, block: suspend (GalleryContentRepository) -> Unit) {
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond("previews")
        }
        val vault = object : SessionVault {
            override suspend fun read(): ByteArray? = null
            override suspend fun write(bytes: ByteArray) = error("No session mutation expected")
            override suspend fun clear() = error("No session mutation expected")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(vault), "reader-test")
        val decoder = object : GalleryDocumentDecoder {
            override fun decodeProfileLink(html: ByteArray): String? = error("No account request expected")
            override fun decodeProfile(html: ByteArray): com.ehviewer.core.network.session.GalleryAccountProfile = error("No account request expected")
            override fun decodeUserConfig(html: ByteArray): List<String> = error("No account request expected")
            override fun decode(html: ByteArray) = GallerySearchResult(null, null, emptyList())
            override fun decodeDetails(html: ByteArray): GalleryDetail {
                assertEquals("previews", html.decodeToString())
                return window
            }
            override fun decodePage(html: ByteArray): GalleryImagePage = error("No image request expected")
        }
        try {
            block(GalleryContentRepository(client, decoder))
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun savedPageTokensAndExistingPreviewsResumeWithoutAnExtraRequest() = runTest {
        val requests = mutableListOf<String>()
        withRepository(requests = requests) { content ->
            assertEquals("https://e-hentai.org/s/aaaaaaaaaa/1-6", readingResumeUrl(content, initial, ReadingPosition(1, 5, "aaaaaaaaaa", 1)))
            assertEquals("https://e-hentai.org/s/2222222222/1-2", readingResumeUrl(content, initial, ReadingPosition(1, 1, null, 1)))
        }
        assertEquals(emptyList(), requests)
    }

    @Test fun indexOnlyAndroidProgressResolvesTheActualPreviewWindowAndToken() = runTest {
        val requests = mutableListOf<String>()
        val window = initial.copy(previewList = listOf(V1GalleryPreview("", 4, "eeeeeeeeee"), V1GalleryPreview("", 5, "ffffffffff")))
        withRepository(window, requests) { content ->
            assertEquals("https://e-hentai.org/s/ffffffffff/1-6", readingResumeUrl(content, initial, ReadingPosition(1, 5, null, 1)))
        }
        assertEquals(listOf("https://e-hentai.org/g/1/0123456789/?p=2"), requests)
    }

    @Test fun crossGalleryStaleAndUnresolvableProgressNeverInventNavigation() = runTest {
        val requests = mutableListOf<String>()
        withRepository(requests = requests) { content ->
            for (position in listOf(ReadingPosition(2, 0, null, 1), ReadingPosition(1, 6, null, 1), ReadingPosition(1, -1, null, 1))) {
                assertFailsWith<GalleryRequestException> { readingResumeUrl(content, initial, position) }
            }
            val gaps = initial.copy(previewList = listOf(V1GalleryPreview("", 1, "2222222222")))
            assertFailsWith<GalleryRequestException> { readingResumeUrl(content, gaps, ReadingPosition(1, 5, null, 1)) }
            assertEquals(emptyList(), requests)
            assertFailsWith<GalleryRequestException> { readingResumeUrl(content, initial, ReadingPosition(1, 5, null, 1)) }
            assertEquals(1, requests.size)
        }
    }
}
