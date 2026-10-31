package com.ehviewer.core.network

import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.V1GalleryPreview
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
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class GalleryPreviewRepositoryTest {
    private val preview = V1GalleryPreview("https://ehgt.org/preview.jpg", 0, "1111111111")
    private val decoder = object : GalleryDocumentDecoder {
        override fun decode(html: ByteArray): GallerySearchResult = error("No HTML decoding expected")
        override fun decodeDetails(html: ByteArray): GalleryDetail = error("No HTML decoding expected")
        override fun decodePage(html: ByteArray): GalleryImagePage = error("No HTML decoding expected")
        override fun decodeProfileLink(html: ByteArray): String? = error("No HTML decoding expected")
        override fun decodeProfile(html: ByteArray): GalleryAccountProfile = error("No HTML decoding expected")
        override fun decodeUserConfig(html: ByteArray): List<String> = error("No HTML decoding expected")
    }

    @Test fun publicAndRestrictedPreviewsUseRealGalleryRefererAndDoNotLeakIdentityToCdn() = runTest {
        for (restricted in listOf(false, true)) {
            val host = if (restricted) "exhentai.org" else "e-hentai.org"
            val cookies = PersistentCookieStorage(TestSessionVault())
            cookies.addCookie(Url("https://$host/"), Cookie("ipb_pass_hash", "test-only-secret", domain = host, path = "/"))
            val engine = MockEngine { request ->
                assertEquals("ehgt.org", request.url.host)
                assertEquals("https://$host/g/1/0123456789/", request.headers[HttpHeaders.Referrer])
                assertFalse(request.headers[HttpHeaders.Cookie].orEmpty().contains("test-only-secret"))
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            }
            val client = galleryHttpClient(engine, cookies, "test")
            try {
                assertContentEquals(byteArrayOf(1, 2, 3), GalleryContentRepository(client, decoder, restricted).preview(preview, 1, "0123456789"))
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun hostilePreviewUrlsAreRejectedBeforeAnyDestinationIsContacted() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            error("Untrusted destination was requested")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            for (url in listOf("http://ehgt.org/a", "https://ehgt.org.evil.example/a", "https://127.0.0.1/a", "file:///private", "https://member:secret@ehgt.org/a", "https://ehgt.org:444/a")) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).preview(preview.copy(url = url), 1, "0123456789") }.failure)
            }
            assertEquals(0, calls)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun thumbnailRedirectsCannotEscapeTheTrustedImageService() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/private"))
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).preview(preview, 1, "0123456789") }.failure)
            assertEquals(1, calls)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun challengeAndOversizedThumbnailResponsesAreRecoverableFailures() = runTest {
        for ((headers, expected) in listOf(headersOf(HttpHeaders.ContentType, "text/html") to GalleryFailure.INVALID_RESPONSE, headersOf(HttpHeaders.ContentLength, (32L * 1024 * 1024 + 1).toString()) to GalleryFailure.RESPONSE_TOO_LARGE)) {
            val engine = MockEngine { respond("not an image", HttpStatusCode.OK, headers) }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                assertEquals(expected, assertFailsWith<GalleryRequestException> { GalleryContentRepository(client, decoder).preview(preview, 1, "0123456789") }.failure)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun leavingTheDetailCancelsTheActualThumbnailRequest() = runTest {
        val engine = MockEngine { throw CancellationException("cancelled") }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertFailsWith<CancellationException> { GalleryContentRepository(client, decoder).preview(preview, 1, "0123456789") }
        } finally {
            client.close()
            engine.close()
        }
    }
}
