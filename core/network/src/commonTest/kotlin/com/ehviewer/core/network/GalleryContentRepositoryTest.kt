package com.ehviewer.core.network

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.session.PersistentCookieStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GalleryContentRepositoryTest {
    @Test fun actualForumLoginRedirectIsAuthenticationBeforeAnyLoginPageIsFetched() = runTest {
        val visited = mutableListOf<String>()
        val engine = MockEngine { request ->
            visited += request.url.host
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://forums.e-hentai.org/index.php?act=Login&CODE=00"))
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val error = assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder()).details(1, "0123456789") }
            assertEquals(GalleryFailure.AUTHENTICATION, error.failure)
            assertEquals(listOf("e-hentai.org"), visited)
        } finally {
            client.close()
            engine.close()
        }
    }

    private val details = GalleryDetail(
        galleryInfo = BaseGalleryInfo(gid = 1, token = "0123456789", pages = 2),
        tagGroups = emptyList(),
        comments = GalleryCommentList(emptyList(), false),
        previewList = listOf(V1GalleryPreview("https://ehgt.org/preview.jpg", 0, "1111111111")),
    )
    private val image = GalleryImagePage("https://node.hath.network/image.png", null, null, null, "https://e-hentai.org/s/2222222222/1-2", null)
    private fun decoder(detail: GalleryDetail = details, page: GalleryImagePage = image) = object : GalleryDocumentDecoder {
        override fun decode(html: ByteArray) = GallerySearchResult(null, null, emptyList())
        override fun decodeDetails(html: ByteArray): GalleryDetail {
            assertEquals("detail", html.decodeToString())
            return detail
        }
        override fun decodePage(html: ByteArray): GalleryImagePage {
            assertEquals("page", html.decodeToString())
            return page
        }
    }

    @Test fun metadataAndPreviewPaginationUseTheSameAuthenticatedServiceAndIdentity() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/g/1/0123456789/", request.url.encodedPath)
            assertEquals("2", request.url.parameters["p"])
            assertEquals("https://e-hentai.org/", request.headers[HttpHeaders.Referrer])
            respond("detail")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertEquals(details, GalleryContentRepository(client, decoder()).details(1, "0123456789", 2))
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun aDifferentGalleryOrInvalidPreviewCannotBecomeAnAcceptedDetail() = runTest {
        val engine = MockEngine { respond("detail") }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            for (wrong in listOf(details.copy(galleryInfo = BaseGalleryInfo(gid = 2, token = "0123456789", pages = 2)), details.copy(previewList = listOf(V1GalleryPreview("", 2, "1111111111"))), details.copy(previewList = details.previewList + details.previewList))) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder(wrong)).details(1, "0123456789") }.failure)
            }
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun nativePageLinksMustRemainWithinTheSelectedGalleryBeforeImageFetching() = runTest {
        val engine = MockEngine { respond("page") }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val repository = GalleryContentRepository(client, decoder())
            val page = repository.pageUrl(1, "1111111111", 0).toString()
            assertEquals("https://e-hentai.org/s/1111111111/1-1", page)
            assertEquals(image, repository.page(page, 1))
            for (link in listOf("https://evil.example/s/1111111111/1-1", "https://e-hentai.org/s/1111111111/2-1", "$page?token=private", "$page#fragment", "https://member:secret@e-hentai.org/s/1111111111/1-1")) {
                assertFailsWith<GalleryRequestException> { repository.pageIndex(link, 1) }
            }
            val wrong = image.copy(nextPageUrl = "https://e-hentai.org/s/1111111111/2-2")
            assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder(page = wrong)).page(page, 1) }
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun imageBytesUseThePageRefererWithoutForwardingLoginCookiesToTheCdn() = runTest {
        val bytes = byteArrayOf(0, 1, -1, 42)
        val cookies = PersistentCookieStorage(TestSessionVault())
        cookies.addCookie(Url("https://e-hentai.org/"), Cookie("ipb_pass_hash", "test-only-secret", path = "/", domain = "e-hentai.org"))
        val engine = MockEngine { request ->
            assertEquals("node.hath.network", request.url.host)
            assertEquals("https://e-hentai.org/s/1111111111/1-1", request.headers[HttpHeaders.Referrer])
            assertFalse(request.headers[HttpHeaders.Cookie].orEmpty().contains("test-only-secret"))
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }
        val client = galleryHttpClient(engine, cookies, "test")
        try {
            assertContentEquals(bytes, GalleryContentRepository(client, decoder()).image(image, "https://e-hentai.org/s/1111111111/1-1", 1))
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun hostileAndDowngradeRedirectsAreRejectedBeforeTheDestinationIsRequested() = runTest {
        for (destination in listOf("https://hath.network.evil.example/stolen", "http://node.hath.network/image", "https://127.0.0.1/private", "file:///private")) {
            var count = 0
            val engine = MockEngine {
                count++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, destination))
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                val error = assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder()).image(image, "https://e-hentai.org/s/1111111111/1-1", 1) }
                assertEquals(GalleryFailure.INVALID_RESPONSE, error.failure)
                assertEquals(1, count)
                assertEquals(null, error.cause)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun safeRelativeRedirectsResolveAgainstTheRequestedImageAndLoopsAreBounded() = runTest {
        var count = 0
        val engine = MockEngine { request ->
            count++
            if (request.url.encodedPath == "/image.png") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/actual.png")) else respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertContentEquals(byteArrayOf(1), GalleryContentRepository(client, decoder()).image(image, "https://e-hentai.org/s/1111111111/1-1", 1))
            assertEquals(2, count)
        } finally {
            client.close()
            engine.close()
        }
        var loops = 0
        val loopEngine = MockEngine {
            loops++
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "/image.png"))
        }
        val loopClient = galleryHttpClient(loopEngine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertFailsWith<GalleryRequestException> { GalleryContentRepository(loopClient, decoder()).image(image, "https://e-hentai.org/s/1111111111/1-1", 1) }
            assertEquals(6, loops)
        } finally {
            loopClient.close()
            loopEngine.close()
        }
    }

    @Test fun htmlChallengesAndOversizedStreamsNeverBecomeSuccessfulImages() = runTest {
        val channel = ByteChannel(autoFlush = true)
        var produced = 0
        val producer = backgroundScope.launch {
            try {
                repeat(8192) {
                    channel.writeFully(ByteArray(8192))
                    produced++
                }
            } catch (error: Exception) {
                if (!channel.isClosedForRead) throw error
            } finally {
                channel.close()
            }
        }
        val engine = MockEngine { respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg")) }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder()).image(image, "https://e-hentai.org/s/1111111111/1-1", 1) }.failure)
            assertTrue(produced < 8192)
        } finally {
            producer.cancelAndJoin()
            client.close()
            engine.close()
        }
        val htmlEngine = MockEngine { respond("private-challenge", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val htmlClient = galleryHttpClient(htmlEngine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val error = assertFailsWith<GalleryRequestException> { GalleryContentRepository(htmlClient, decoder()).image(image, "https://e-hentai.org/s/1111111111/1-1", 1) }
            assertEquals(GalleryFailure.INVALID_RESPONSE, error.failure)
            assertFalse(error.toString().contains("private-challenge"))
        } finally {
            htmlClient.close()
            htmlEngine.close()
        }
    }

    @Test fun cancelledPageRequestsAndParsersRemainCancelled() = runTest {
        for (parser in listOf(false, true)) {
            val engine = MockEngine { if (!parser) throw CancellationException("cancelled") else respond("page") }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            val cancelling = object : GalleryDocumentDecoder by decoder() {
                override fun decodePage(html: ByteArray): GalleryImagePage = throw CancellationException("cancelled")
            }
            try {
                assertFailsWith<CancellationException> { GalleryContentRepository(client, cancelling).page("https://e-hentai.org/s/1111111111/1-1", 1) }
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun manualImageRetryUsesTheActualPageReloadKeyWithoutChangingGalleryIdentity() = runTest {
        var count = 0
        val engine = MockEngine { request ->
            count++
            assertEquals("/s/1111111111/1-1", request.url.encodedPath)
            assertEquals("alternate-hath-key", request.url.parameters["nl"])
            respond("page")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val repository = GalleryContentRepository(client, decoder())
            assertEquals(image, repository.page("https://e-hentai.org/s/1111111111/1-1", 1, "alternate-hath-key"))
            assertFailsWith<GalleryRequestException> { repository.page("https://e-hentai.org/s/1111111111/1-1", 1, "invalid\nkey") }
            assertEquals(1, count)
        } finally {
            client.close()
            engine.close()
        }
    }
}
