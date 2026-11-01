package com.ehviewer.core.network

import com.ehviewer.core.model.GalleryDetail
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GalleryOriginalImageTest {
    private val pageUrl = "https://e-hentai.org/s/1111111111/1-1"
    private val page = GalleryImagePage("https://ehgt.org/display.png", null, "https://e-hentai.org/fullimg/1/3333333333", null, null, null)
    private val decoder = object : GalleryDocumentDecoder {
        override fun decode(html: ByteArray): GallerySearchResult = error("No list request expected")
        override fun decodeDetails(html: ByteArray): GalleryDetail = error("No detail request expected")
        override fun decodePage(html: ByteArray) = page
        override fun decodeProfileLink(html: ByteArray): String? = error("No account request expected")
        override fun decodeProfile(html: ByteArray): com.ehviewer.core.network.session.GalleryAccountProfile = error("No account request expected")
        override fun decodeUserConfig(html: ByteArray): List<String> = error("No account request expected")
    }

    @Test fun bothServicesResolveTheActualOriginalRedirectWithPageRefererAndCookieIsolation() = runTest {
        for (host in listOf("e-hentai.org", "exhentai.org")) {
            val visited = mutableListOf<String>()
            val cookies = PersistentCookieStorage(TestSessionVault())
            cookies.addCookie(Url("https://$host/"), Cookie("ipb_pass_hash", "owned-original-test", domain = host, path = "/"))
            val bytes = byteArrayOf(1, 7, 9, -1)
            val referrer = pageUrl.replace("e-hentai.org", host)
            val engine = MockEngine { request ->
                visited += request.url.host
                assertEquals(referrer, request.headers[HttpHeaders.Referrer])
                if (request.url.host == host) {
                    assertEquals("/fullimg/1/3333333333", request.url.encodedPath)
                    assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("owned-original-test"))
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://node.hath.network:32002/original.png"))
                } else {
                    assertEquals("node.hath.network", request.url.host)
                    assertFalse(request.headers[HttpHeaders.Cookie].orEmpty().contains("owned-original-test"))
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }
            }
            val client = galleryHttpClient(engine, cookies, "test")
            try {
                assertContentEquals(bytes, GalleryContentRepository(client, decoder, host == "exhentai.org").originalImage(page.copy(originImageUrl = page.originImageUrl!!.replace("e-hentai.org", host)), referrer, 1))
                assertEquals(listOf(host, "node.hath.network"), visited)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun foreignAndMalformedOriginalLinksNeverSendAnHttpRequest() = runTest {
        var requests = 0
        val display = byteArrayOf(1, 2)
        val engine = MockEngine { request ->
            requests++
            when (request.url.encodedPath) {
                "/s/1111111111/1-1" -> respond("page")
                "/display.png" -> respond(display, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                else -> error("Invalid original link reached transport")
            }
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val repository = GalleryContentRepository(client, decoder)
            for (link in listOf(null, "http://e-hentai.org/fullimg/1/3333333333", "https://evil.example/fullimg/1/3333333333", "https://exhentai.org/fullimg/1/3333333333", "https://e-hentai.org:8443/fullimg/1/3333333333", "https://user:secret@e-hentai.org/fullimg/1/3333333333", "https://e-hentai.org/fullimg/2/3333333333", "https://e-hentai.org/fullimg/1/not-a-token", "https://e-hentai.org/fullimg/1/3333333333?private=value", "https://e-hentai.org/fullimg/1/3333333333#fragment")) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.originalImage(page.copy(originImageUrl = link), pageUrl, 1) }.failure)
            }
            assertEquals(0, requests)
            // An unused optional original link must not regress normal display-image reading.
            val displayOnly = GalleryContentRepository(
                client,
                object : GalleryDocumentDecoder by decoder {
                    override fun decodePage(html: ByteArray) = page.copy(originImageUrl = "https://evil.example/fullimg/1/3333333333")
                },
            )
            assertContentEquals(display, displayOnly.image(displayOnly.page(pageUrl, 1), pageUrl, 1))
            assertEquals(2, requests)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun selectedPageIdentityIsValidatedBeforeTheOriginalRequest() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            error("Foreign page reached transport")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            for (link in listOf(pageUrl.replace("/1-1", "/2-1"), pageUrl.replace("e-hentai.org", "evil.example"), "$pageUrl?private=value")) {
                assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).originalImage(page, link, 1) }
            }
            assertEquals(0, requests)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun anUntrustedRedirectIsRejectedBeforeASecondRequest() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/original.png"))
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).originalImage(page, pageUrl, 1) }.failure)
            assertEquals(1, requests)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun loginRedirectsBecomeAuthenticationWithoutFetchingTheLoginDocument() = runTest {
        for (location in listOf("https://e-hentai.org/bounce_login.php", "https://forums.e-hentai.org/login.php", "https://forums.e-hentai.org/index.php?act=Login&CODE=00")) {
            var requests = 0
            val engine = MockEngine {
                requests++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                assertEquals(GalleryFailure.AUTHENTICATION, assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).originalImage(page, pageUrl, 1) }.failure)
                assertEquals(1, requests)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun errorsHtmlAndOversizedOriginalsNeverFallBackSilentlyOrBecomeImages() = runTest {
        for ((status, expected) in listOf(401 to GalleryFailure.AUTHENTICATION, 403 to GalleryFailure.CHALLENGE, 429 to GalleryFailure.RATE_LIMIT, 503 to GalleryFailure.SERVER, 200 to GalleryFailure.INVALID_RESPONSE, 201 to GalleryFailure.RESPONSE_TOO_LARGE)) {
            var requests = 0
            val engine = MockEngine {
                requests++
                val headers = if (status == 201) headersOf(HttpHeaders.ContentType to listOf("image/png"), HttpHeaders.ContentLength to listOf((33L * 1024 * 1024).toString())) else headersOf(HttpHeaders.ContentType, "text/html")
                respond("private service response", HttpStatusCode.fromValue(status), headers)
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                val error = assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).originalImage(page, pageUrl, 1) }
                assertEquals(expected, error.failure)
                assertFalse(error.toString().contains("private service response"))
                assertEquals(1, requests)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun originalRedirectLoopsRetainTheExistingSixRequestBound() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, page.originImageUrl!!))
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).originalImage(page, pageUrl, 1) }
            assertEquals(6, requests)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun cancellingAnOriginalReadDoesNotPublishAnImageOrStartAnotherRequest() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val started = CompletableDeferred<Unit>()
        var requests = 0
        var published = false
        val engine = MockEngine {
            requests++
            started.complete(Unit)
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val reading = launch {
                GalleryContentRepository(client, decoder).originalImage(page, pageUrl, 1)
                published = true
            }
            started.await()
            reading.cancelAndJoin()
            assertTrue(reading.isCancelled)
            assertFalse(published)
            assertEquals(1, requests)
        } finally {
            channel.cancel(null)
            client.close()
            engine.close()
        }
    }
}
