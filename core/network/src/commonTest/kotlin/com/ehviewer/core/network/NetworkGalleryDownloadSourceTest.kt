package com.ehviewer.core.network

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.network.download.NetworkGalleryDownloadSource
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.session.GalleryAccountProfile
import com.ehviewer.core.network.session.PersistentCookieStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class NetworkGalleryDownloadSourceTest {
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
    private fun detail() = GalleryDetail(
        galleryInfo = BaseGalleryInfo(gid = 1, token = "0123456789", pages = 3),
        tagGroups = emptyList(),
        comments = GalleryCommentList(emptyList(), false),
        previewList = listOf(V1GalleryPreview("https://ehgt.org/preview.png", 0, "1111111111")),
    )
    private fun pageUrl(host: String, index: Int) = "https://$host/s/${(index + 1).toString().repeat(10)}/1-${index + 1}"
    private fun decoder(host: String, gallery: GalleryDetail = detail()) = object : GalleryDocumentDecoder {
        override fun decodeProfileLink(html: ByteArray): String? = error("No account request expected")
        override fun decodeProfile(html: ByteArray): GalleryAccountProfile = error("No account request expected")
        override fun decodeUserConfig(html: ByteArray): List<String> = error("No account request expected")
        override fun decode(html: ByteArray) = GallerySearchResult(null, null, emptyList())
        override fun decodeDetails(html: ByteArray): GalleryDetail {
            val window = html.decodeToString().removePrefix("detail:").toIntOrNull() ?: return gallery
            return gallery.copy(previewList = listOf(V1GalleryPreview("https://ehgt.org/preview.png", window, (window + 1).toString().repeat(10))))
        }
        override fun decodePage(html: ByteArray): GalleryImagePage {
            val index = html.decodeToString().substringAfter("page:").toInt()
            return GalleryImagePage(
                "https://ehgt.org/image-$index.png",
                null,
                null,
                null,
                (index + 1).takeIf { it < 3 }?.let { pageUrl(host, it) },
                (index - 1).takeIf { it >= 0 }?.let { pageUrl(host, it) },
            )
        }
    }
    private fun task(restricted: Boolean = false) = GalleryDownload(1, "0123456789", "Owned", 3, restricted)

    private fun replacementDecoder(key: Boolean = true): GalleryDocumentDecoder {
        val base = decoder("e-hentai.org")
        return object : GalleryDocumentDecoder by base {
            override fun decodePage(html: ByteArray) = base.decodePage("page:0".encodeToByteArray()).copy(
                imageUrl = "https://${if (html.decodeToString() == "replacement") "replacement" else "primary"}.hath.network/image.png",
                skipHathKey = "owned-replacement-key".takeIf { key },
            )
        }
    }

    @Test fun identityChangeDuringMetadataOrPageStopsEveryLaterRequest() = runTest {
        for (changedPath in listOf("/g/", "/s/")) {
            var generation = 0
            val requests = mutableListOf<HttpRequestData>()
            val engine = MockEngine { request ->
                requests += request
                if (request.url.encodedPath.startsWith(changedPath)) generation++
                when {
                    request.url.encodedPath.startsWith("/g/") -> respond("detail")
                    request.url.encodedPath.startsWith("/s/") -> respond("page:0")
                    else -> respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                val source = NetworkGalleryDownloadSource(task(), GalleryContentRepository(client, decoder("e-hentai.org"))) {
                    if (generation != 0) throw CancellationException("Session changed")
                }
                assertFailsWith<CancellationException> { source.load(0) }
                assertEquals(if (changedPath == "/g/") 1 else 2, requests.size)
                assertTrue(requests.none { it.url.host == "ehgt.org" })
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun aFailedHathNodeGetsOneOfficialReplacementWithScopedCookiesAndReferrers() = runTest {
        for (disconnect in listOf(false, true)) {
            val requests = mutableListOf<HttpRequestData>()
            val engine = MockEngine { request ->
                requests += request
                when {
                    request.url.encodedPath.startsWith("/g/") -> respond("detail")
                    request.url.encodedPath.startsWith("/s/") -> respond(if (request.url.parameters["nl"] == "owned-replacement-key") "replacement" else "primary")
                    request.url.host == "primary.hath.network" -> {
                        if (disconnect) error("test-only disconnected transport")
                        respond("test-only unavailable node", HttpStatusCode.ServiceUnavailable)
                    }
                    else -> respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
            }
            val cookies = PersistentCookieStorage(TestSessionVault())
            cookies.addCookie(Url("https://e-hentai.org/"), Cookie("ipb_pass_hash", "test-only-secret", path = "/", domain = "e-hentai.org"))
            val client = galleryHttpClient(engine, cookies, "test")
            try {
                val source = NetworkGalleryDownloadSource(task(), GalleryContentRepository(client, replacementDecoder())) {}
                val result = source.load(0)
                assertContentEquals(png, result.bytes)
                assertEquals("1111111111", result.token)
                val documents = requests.filter { it.url.encodedPath.startsWith("/s/") }
                assertEquals(listOf(null, "owned-replacement-key"), documents.map { it.url.parameters["nl"] })
                val images = requests.filter { it.url.host.endsWith(".hath.network") }
                assertEquals(listOf("primary.hath.network", "replacement.hath.network"), images.map { it.url.host })
                images.forEach {
                    assertNull(it.headers[HttpHeaders.Cookie])
                    assertEquals(pageUrl("e-hentai.org", 0), it.headers[HttpHeaders.Referrer])
                }
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun identityChangeDuringTheFailedImagePreventsTheReplacementRequest() = runTest {
        var generation = 0
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.startsWith("/g/") -> respond("detail")
                request.url.encodedPath.startsWith("/s/") -> respond("primary")
                else -> {
                    generation++
                    respond("test-only unavailable node", HttpStatusCode.ServiceUnavailable)
                }
            }
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val source = NetworkGalleryDownloadSource(task(), GalleryContentRepository(client, replacementDecoder())) {
                if (generation != 0) throw CancellationException("Session changed")
            }
            assertFailsWith<CancellationException> { source.load(0) }
            assertEquals(3, requests.size)
            assertTrue(requests.none { it.url.parameters["nl"] != null })
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun deniedOversizedAndRepeatedFailuresCannotBecomeAnAutomaticRetryLoop() = runTest {
        val cases = listOf(
            Triple(401, true, GalleryFailure.AUTHENTICATION),
            Triple(403, true, GalleryFailure.CHALLENGE),
            Triple(429, true, GalleryFailure.RATE_LIMIT),
            Triple(200, true, GalleryFailure.RESPONSE_TOO_LARGE),
            Triple(503, false, GalleryFailure.SERVER),
            Triple(503, true, GalleryFailure.SERVER),
        )
        for ((status, key, failure) in cases) {
            val requests = mutableListOf<HttpRequestData>()
            val engine = MockEngine { request ->
                requests += request
                when {
                    request.url.encodedPath.startsWith("/g/") -> respond("detail")
                    request.url.encodedPath.startsWith("/s/") -> respond(if (request.url.parameters["nl"] != null) "replacement" else "primary")
                    else -> respond(
                        "test-only image failure",
                        HttpStatusCode.fromValue(status),
                        headersOf(HttpHeaders.ContentType to listOf("image/png"), HttpHeaders.ContentLength to listOf((33 * 1024 * 1024).toString())),
                    )
                }
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                val source = NetworkGalleryDownloadSource(task(), GalleryContentRepository(client, replacementDecoder(key))) {}
                assertEquals(failure, assertFailsWith<GalleryRequestException> { source.load(0) }.failure)
                val expectedAttempts = if (status == 503 && key) 2 else 1
                assertEquals(expectedAttempts, requests.count { it.url.encodedPath.startsWith("/s/") })
                assertEquals(expectedAttempts, requests.count { it.url.host.endsWith(".hath.network") })
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun resumedTaskResolvesTheRealRemainingPreviewWindowWithoutFetchingCompletedImages() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.startsWith("/g/") -> respond("detail:${request.url.parameters["p"] ?: "0"}")
                request.url.encodedPath.startsWith("/s/") -> respond("page:2")
                else -> respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            }
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val source = NetworkGalleryDownloadSource(task().copy(finished = 2), GalleryContentRepository(client, decoder("e-hentai.org"))) {}
            assertContentEquals(png, source.load(2).bytes)
            assertEquals(listOf(null, "2"), requests.filter { it.url.encodedPath.startsWith("/g/") }.map { it.url.parameters["p"] })
            assertEquals(listOf("/s/3333333333/1-3"), requests.filter { it.url.encodedPath.startsWith("/s/") }.map { it.url.encodedPath })
            assertEquals(listOf("/image-2.png"), requests.filter { it.url.host == "ehgt.org" }.map { it.url.encodedPath })
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun realKtorTransportFollowsPageLinksAndKeepsReferrersAndIdentityCookiesScoped() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.startsWith("/g/") -> respond("detail", headers = headersOf(HttpHeaders.ContentType, "text/html"))
                request.url.encodedPath.startsWith("/s/") -> respond("page:${request.url.encodedPath.substringAfterLast('-').toInt() - 1}", headers = headersOf(HttpHeaders.ContentType, "text/html"))
                else -> respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            }
        }
        val cookies = PersistentCookieStorage(TestSessionVault())
        cookies.addCookie(Url("https://e-hentai.org/"), Cookie("ipb_pass_hash", "test-only-secret", path = "/", domain = "e-hentai.org"))
        val client = galleryHttpClient(engine, cookies, "EhViewer-owned-test")
        try {
            val source = NetworkGalleryDownloadSource(task(), GalleryContentRepository(client, decoder("e-hentai.org"))) {}
            (0..2).forEach { index ->
                val page = source.load(index)
                assertEquals((index + 1).toString().repeat(10), page.token)
                assertContentEquals(png, page.bytes)
            }
            assertEquals(1, requests.count { it.url.encodedPath.startsWith("/g/") })
            assertEquals(3, requests.count { it.url.encodedPath.startsWith("/s/") })
            val images = requests.filter { it.url.host == "ehgt.org" }
            assertEquals(3, images.size)
            images.forEachIndexed { index, request ->
                assertEquals(pageUrl("e-hentai.org", index), request.headers[HttpHeaders.Referrer])
                assertNull(request.headers[HttpHeaders.Cookie])
            }
            assertTrue(requests.first().headers[HttpHeaders.Cookie].orEmpty().contains("ipb_pass_hash"))
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun identityChangeDuringAnActualImageResponseCancelsBeforeAnyBytesAreReturned() = runTest {
        var generation = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.startsWith("/g/") -> respond("detail")
                request.url.encodedPath.startsWith("/s/") -> respond("page:0")
                else -> {
                    generation++
                    respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
            }
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val source = NetworkGalleryDownloadSource(task(), GalleryContentRepository(client, decoder("e-hentai.org"))) {
                if (generation != 0) throw CancellationException("Session changed")
            }
            assertFailsWith<CancellationException> { source.load(0) }
            assertEquals(1, generation)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun malformedPreviewWindowCannotResolveAnotherPageOrDownloadAnImage() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("detail")
        }
        val malformed = detail().copy(previewList = listOf(V1GalleryPreview("https://ehgt.org/p.png", 2, "3333333333")))
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            val source = NetworkGalleryDownloadSource(task(), GalleryContentRepository(client, decoder("e-hentai.org", malformed))) {}
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { source.load(1) }.failure)
            assertEquals(1, requests)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun restrictedTaskUsesItsFixedSiteAndDoesNotBorrowPublicSiteIdentity() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.startsWith("/g/") -> respond("detail")
                request.url.encodedPath.startsWith("/s/") -> respond("page:0")
                else -> respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            }
        }
        val cookies = PersistentCookieStorage(TestSessionVault())
        cookies.addCookie(Url("https://e-hentai.org/"), Cookie("ipb_pass_hash", "test-only-secret", path = "/", domain = "e-hentai.org"))
        val client = galleryHttpClient(engine, cookies, "test")
        try {
            val source = NetworkGalleryDownloadSource(task(true), GalleryContentRepository(client, decoder("exhentai.org"), true)) {}
            assertContentEquals(png, source.load(0).bytes)
            assertTrue(requests.filter { it.url.host != "ehgt.org" }.all { it.url.host == "exhentai.org" })
            assertTrue(requests.all { it.headers[HttpHeaders.Cookie] == null })
        } finally {
            client.close()
            engine.close()
        }
    }
}
