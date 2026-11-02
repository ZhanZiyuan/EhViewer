package com.ehviewer.core.network

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.gallery.GalleryRequestException
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

class GalleryCoverRepositoryTest {
    @Test fun actualRequestsPreserveOriginalPublicRestrictedV1V2MappingRefererAndCookieScope() = kotlinx.coroutines.test.runTest {
        val cases = listOf(
            Triple(false, "aa/bb/cover.jpg", "https://ehgt.org/aa/bb/cover.jpg"),
            Triple(false, "aa/bb/cover.webp", "https://ehgt.org/aa/bb/cover.webp"),
            Triple(true, "aa/bb/cover.jpg", "https://s.exhentai.org/t/aa/bb/cover.jpg"),
            Triple(true, "aa/bb/cover.webp", "https://s.exhentai.org/aa/bb/cover.webp"),
            Triple(true, "aa/owned%20cover.webp?version=1", "https://s.exhentai.org/aa/owned%20cover.webp?version=1"),
            Triple(true, "https://ehgt.org/owned.jpg", "https://ehgt.org/owned.jpg"),
            Triple(true, "https://s.exhentai.org/t/owned.jpg", "https://s.exhentai.org/t/owned.jpg"),
        )
        for ((restricted, key, expected) in cases) {
            val host = if (restricted) "exhentai.org" else "e-hentai.org"
            val cookies = PersistentCookieStorage(TestSessionVault())
            cookies.addCookie(Url("https://$host/"), Cookie("ipb_member_id", "owned-cover-member", domain = host, path = "/"))
            cookies.addCookie(Url("https://$host/"), Cookie("ipb_pass_hash", "owned-cover-secret", domain = host, path = "/"))
            assertEquals(true, cookies.get(Url("https://$host/")).any { it.value == "owned-cover-secret" })
            var calls = 0
            val engine = MockEngine { request ->
                calls++
                assertEquals(expected, request.url.toString())
                assertEquals("https://$host/g/1/0123456789/", request.headers[HttpHeaders.Referrer])
                assertEquals(false, request.headers[HttpHeaders.Cookie].orEmpty().contains("owned-cover-secret"))
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            }
            val client = galleryHttpClient(engine, cookies, "owned-cover-test")
            try {
                val repository = GalleryRepository(client, { error("No HTML decoding expected") }, restricted)
                assertContentEquals(byteArrayOf(1, 2, 3), repository.cover(1, "0123456789", key))
                assertEquals(1, calls)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun malformedMetadataAndNonCoverDestinationsAreRejectedBeforeAnyRequest() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            error("Invalid cover metadata reached an engine")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "owned-cover-test")
        try {
            val repository = GalleryRepository(client, { error("No HTML decoding expected") })
            for (key in listOf("", " ", "../owned.jpg", "aa/../owned.jpg", "aa//owned.jpg", "%2e%2e/owned.jpg", "//evil.example/a.jpg", "http://ehgt.org/a.jpg", "https://evil.example/a.jpg", "https://ehgt.org.evil.example/a.jpg", "https://e-hentai.org/logout.php", "https://127.0.0.1/a.jpg", "https://owned:secret@ehgt.org/a.jpg", "https://ehgt.org:444/a.jpg", "https://ehgt.org/a.jpg#fragment", "https://ehgt.org/")) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.cover(1, "0123456789", key) }.failure)
            }
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.cover(0, "0123456789", "a.jpg") }.failure)
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.cover(1, "invalid", "a.jpg") }.failure)
            assertEquals(0, calls)
        } finally {
            client.close()
            engine.close()
        }
    }
}
