package com.ehviewer.core.network

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryListDecoder
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parseClientCookiesHeader
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GalleryRepositoryTest {
    private val result = GallerySearchResult(null, "42", listOf(BaseGalleryInfo(gid = 1, token = "1234567890", title = "Test gallery")))
    private val decoder = GalleryListDecoder { bytes ->
        assertEquals("test-html", bytes.decodeToString())
        result
    }

    @Test fun searchEncodesTheQueryAndUsesTheRealSharedCookieAndHeaderConfiguration() = runTest {
        val cookies = PersistentCookieStorage(TestSessionVault())
        cookies.addCookie(Url("https://e-hentai.org/"), io.ktor.http.Cookie("ipb_member_id", "test-member", path = "/"))
        val engine = MockEngine { request ->
            assertEquals("https", request.url.protocol.name)
            assertEquals("e-hentai.org", request.url.host)
            assertEquals("tag:日本語 & next=evil", request.url.parameters["f_search"])
            assertEquals("42", request.url.parameters["next"])
            assertEquals("https://e-hentai.org/", request.headers[HttpHeaders.Referrer])
            assertEquals("EhViewer-test", request.headers[HttpHeaders.UserAgent])
            val sent = parseClientCookiesHeader(request.headers.getAll(HttpHeaders.Cookie).orEmpty().joinToString("; "))
            assertEquals("test-member", sent["ipb_member_id"])
            assertEquals("1", sent["nw"])
            respond("test-html", HttpStatusCode.OK)
        }
        val client = galleryHttpClient(engine, cookies, "EhViewer-test")
        try {
            assertEquals(result, GalleryRepository(client, decoder).search("tag:日本語 & next=evil", "42"))
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun previousPageUsesItsOwnCursorParameter() = runTest {
        val engine = MockEngine { request ->
            assertEquals("12-13", request.url.parameters["prev"])
            assertEquals(null, request.url.parameters["next"])
            respond("test-html", HttpStatusCode.OK)
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            GalleryRepository(client, decoder).search(cursor = "12-13", previous = true)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun unavailableSecureStorageDoesNotBecomeAnOfflineErrorOrSendARequest() = runTest {
        val vault = object : SessionVault {
            override suspend fun read(): ByteArray? = error("test-only-secret-path")
            override suspend fun write(bytes: ByteArray) = error("No write expected")
            override suspend fun clear() = error("No clear expected")
        }
        var requested = false
        val engine = MockEngine {
            requested = true
            respond("test-html", HttpStatusCode.OK)
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(vault), "EhViewer-test")
        try {
            val error = assertFailsWith<GalleryRequestException> { GalleryRepository(client, decoder).search() }
            assertEquals(GalleryFailure.SESSION_STORAGE, error.failure)
            assertFalse(requested)
            assertFalse(error.toString().contains("test-only-secret"))
            assertEquals(null, error.cause)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun serviceErrorsHaveActionableCategoriesWithoutPrivateResponseOrUrlText() = runTest {
        for ((status, expected) in listOf(401 to GalleryFailure.AUTHENTICATION, 403 to GalleryFailure.CHALLENGE, 429 to GalleryFailure.RATE_LIMIT, 503 to GalleryFailure.SERVER, 404 to GalleryFailure.INVALID_RESPONSE)) {
            val engine = MockEngine { respond("test-only-secret", HttpStatusCode.fromValue(status)) }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
            try {
                val error = assertFailsWith<GalleryRequestException> { GalleryRepository(client, decoder).search("private-query") }
                assertEquals(expected, error.failure)
                assertFalse(error.toString().contains("private-query"))
                assertFalse(error.toString().contains("test-only-secret"))
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun networkAndParserFailuresAreDistinctAndDoNotExposeTheirCauses() = runTest {
        for ((offline, expected) in listOf(true to GalleryFailure.OFFLINE, false to GalleryFailure.INVALID_RESPONSE)) {
            val engine = MockEngine { if (offline) error("test-only-secret") else respond("test-html", HttpStatusCode.OK) }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
            try {
                val parser = GalleryListDecoder { error("test-only-secret") }
                val error = assertFailsWith<GalleryRequestException> { GalleryRepository(client, parser).search() }
                assertEquals(expected, error.failure)
                assertEquals(null, error.cause)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun cancellationIsNeverConvertedIntoOfflineOrParserFailure() = runTest {
        for (inParser in listOf(true, false)) {
            val engine = MockEngine { if (!inParser) throw CancellationException("cancelled") else respond("test-html", HttpStatusCode.OK) }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
            try {
                val parser = GalleryListDecoder { throw CancellationException("cancelled") }
                assertFailsWith<CancellationException> { GalleryRepository(client, parser).search() }
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun oversizedHtmlCannotReachTheNativeParser() = runTest {
        val engine = MockEngine { respond("x".repeat(8 * 1024 * 1024 + 1), HttpStatusCode.OK) }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val parser = GalleryListDecoder { error("Parser must not run") }
            assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { GalleryRepository(client, parser).search() }.failure)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun oversizedStreamingResponsesStopBeforeDownloadingTheEntireBody() = runTest {
        val channel = ByteChannel(autoFlush = true)
        var produced = 0
        val producer = backgroundScope.launch {
            try {
                val chunk = ByteArray(8192) { 'x'.code.toByte() }
                repeat(2048) {
                    channel.writeFully(chunk)
                    produced++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!channel.isClosedForRead) throw error
            } finally {
                channel.close()
            }
        }
        val engine = MockEngine { respond(channel, HttpStatusCode.OK) }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { GalleryRepository(client, decoder).search() }.failure)
        } finally {
            producer.cancelAndJoin()
            client.close()
            engine.close()
        }
        assertTrue(produced < 2048, "The bounded request must not buffer the complete response")
    }

    @Test fun invalidCursorAndGalleryIdentityAreRejectedBeforeAnyRequest() = runTest {
        val engine = MockEngine { error("No request expected") }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val repository = GalleryRepository(client, decoder)
            assertFailsWith<IllegalArgumentException> { repository.search(cursor = "1&evil=2") }
            assertFailsWith<IllegalArgumentException> { repository.galleryUrl(0, "1234567890") }
            assertFailsWith<IllegalArgumentException> { repository.galleryUrl(1, "../../evil") }
            assertEquals("https://e-hentai.org/g/1/1234567890/", repository.galleryUrl(1, "1234567890").toString())
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun redirectToLoginIsAnExpiredSessionAndForeignRedirectIsRejected() = runTest {
        for ((target, expected) in listOf("https://forums.e-hentai.org/login.php" to GalleryFailure.AUTHENTICATION, "https://example.org/" to GalleryFailure.INVALID_RESPONSE)) {
            val engine = MockEngine { request ->
                if (request.url.host == "e-hentai.org") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target)) else respond("test-html", HttpStatusCode.OK)
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
            try {
                assertEquals(expected, assertFailsWith<GalleryRequestException> { GalleryRepository(client, decoder).search() }.failure)
            } finally {
                client.close()
                engine.close()
            }
        }
    }
}
