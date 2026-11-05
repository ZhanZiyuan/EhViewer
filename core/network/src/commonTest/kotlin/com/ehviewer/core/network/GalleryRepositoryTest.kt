package com.ehviewer.core.network

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryListDecoder
import com.ehviewer.core.network.gallery.GalleryQueryError
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
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import io.ktor.http.parseClientCookiesHeader
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GalleryRepositoryTest {
    private val result = GallerySearchResult(null, "42", listOf(BaseGalleryInfo(gid = 1, token = "1234567890", title = "Test gallery")))
    private val decoder = GalleryListDecoder { bytes ->
        assertEquals("test-html", bytes.decodeToString())
        result
    }

    @Test fun recognizedQueryWarningsRemainTypedErrorsAndAnEditedQueryCanRecover() = runTest {
        for (restricted in listOf(false, true)) {
            var requests = 0
            var warning = true
            val engine = MockEngine {
                requests++
                respond("owned listing")
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
            try {
                val repository = GalleryRepository(
                    client,
                    GalleryListDecoder {
                        if (warning) GallerySearchResult(null, null, emptyList(), GalleryQueryError.TOO_MANY_KEYWORDS) else result
                    },
                    restricted,
                )
                val routes = listOf<suspend () -> GallerySearchResult>({ repository.search("owned long query") }, { repository.uploader("Owned Uploader") }, { repository.tag("language:english") })
                for (route in routes) assertEquals(GalleryFailure.SEARCH_QUERY, assertFailsWith<GalleryRequestException> { route() }.failure)
                assertEquals(3, requests, "A query warning must neither succeed empty nor automatically retry")
                warning = false
                assertEquals(result, repository.search("Alice Wonderland"))
                assertEquals(4, requests)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun inconsistentQueryWarningPayloadsStayInvalidRatherThanDisplayingAdvice() = runTest {
        val engine = MockEngine { respond("owned listing") }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            for (bad in listOf(
                result.copy(queryError = GalleryQueryError.TOO_MANY_KEYWORDS),
                GallerySearchResult("1", null, emptyList(), GalleryQueryError.TOO_MANY_KEYWORDS),
                GallerySearchResult(null, "2", emptyList(), GalleryQueryError.TOO_MANY_KEYWORDS),
            )) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { GalleryRepository(client, GalleryListDecoder { bad }).search("owned") }.failure)
            }
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun everyListingRouteRestoresLanguageBadgesWithoutInventingOrReplacingMetadata() = runTest {
        val engine = MockEngine { respond("owned language listing") }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val repository = GalleryRepository(
                client,
                GalleryListDecoder {
                    GallerySearchResult(
                        null,
                        null,
                        listOf(
                            BaseGalleryInfo(gid = 1, title = "[Chinese] owned", simpleTags = listOf("language:english")),
                            BaseGalleryInfo(gid = 2, title = "[Español] owned", simpleTags = listOf("other:comic")),
                            BaseGalleryInfo(gid = 3, title = "Unspecified owned gallery"),
                            BaseGalleryInfo(gid = 4, title = "[English] owned", simpleTags = listOf("language:english"), simpleLanguage = "FR"),
                        ),
                    )
                },
            )
            for (route in listOf<suspend () -> GallerySearchResult>({ repository.search("owned") }, { repository.uploader("Owned Uploader") }, { repository.tag("language:english") })) {
                val page = route()
                assertEquals(listOf("EN", "ES", null, "FR"), page.galleryInfoList.map { it.simpleLanguage })
                assertNull(page.prev)
                assertNull(page.next)
                assertEquals(listOf(1L, 2L, 3L, 4L), page.galleryInfoList.map { it.gid })
            }
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun cancelledTagRequestsDoNotInvokeTheDecoderOrReturnEmptySuccess() = runTest {
        val started = CompletableDeferred<Unit>()
        var decoded = 0
        val engine = MockEngine {
            started.complete(Unit)
            awaitCancellation()
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val repository = GalleryRepository(
                client,
                GalleryListDecoder {
                    decoded++
                    result
                },
            )
            val request = launch { repository.tag("parody:alice in wonderland") }
            started.await()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertEquals(0, decoded)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun failedTagRequestsPreserveTheRecoverableServerFailure() = runTest {
        var decoded = 0
        val engine = MockEngine { respond("Owned unavailable tag page", HttpStatusCode.ServiceUnavailable) }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val repository = GalleryRepository(
                client,
                GalleryListDecoder {
                    decoded++
                    result
                },
            )
            assertEquals(GalleryFailure.SERVER, assertFailsWith<GalleryRequestException> { repository.tag("parody:alice in wonderland") }.failure)
            assertEquals(0, decoded)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun literalNamespacedTagsRemainOnePathSegmentOnEitherService() = runTest {
        val name = "parody:Alice / 日本語 ?x=1#quoted\""
        for (restricted in listOf(false, true)) {
            val engine = MockEngine { request ->
                assertEquals(if (restricted) "exhentai.org" else "e-hentai.org", request.url.host)
                assertEquals(listOf("tag", name), request.url.pathSegments.filter { it.isNotEmpty() })
                assertTrue("%2F" in request.url.encodedPath)
                assertEquals("12-13", request.url.parameters["prev"])
                assertEquals(null, request.url.parameters["next"])
                assertEquals(null, request.url.parameters["f_search"])
                assertEquals(null, request.url.parameters["x"])
                assertEquals("", request.url.fragment)
                respond("test-html", HttpStatusCode.OK)
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
            try {
                assertEquals(result, GalleryRepository(client, decoder, restricted).tag(name, "12-13", previous = true))
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun invalidTagNamesAndCursorsNeverReachTheTransport() = runTest {
        var sent = 0
        val engine = MockEngine {
            sent++
            respond("test-html", HttpStatusCode.OK)
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val repository = GalleryRepository(client, decoder)
            for (name in listOf("", "english", ":english", "unknown:english", "language:", "language: ", "artist:a\nb", "artist:a\u007fb", "artist:" + "a".repeat(4096))) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.tag(name) }.failure)
            }
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.tag("language:english", "../private") }.failure)
            assertEquals(0, sent)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun tagPagingPreservesFiltersAndRejectsMalformedResponseCursors() = runTest {
        val engine = MockEngine { request ->
            assertEquals("42", request.url.parameters["next"])
            assertEquals(null, request.url.parameters["prev"])
            assertEquals("5", request.url.parameters["f_srdd"])
            respond("test-html", HttpStatusCode.OK)
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val filters = com.ehviewer.core.network.gallery.GallerySearchFilters(minimumRating = 5)
            assertEquals(result, GalleryRepository(client, decoder).tag("language:english", "42", filters = filters))
            val invalidDecoder = GalleryListDecoder { result.copy(next = "../private") }
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { GalleryRepository(client, invalidDecoder).tag("language:english", "42", filters = filters) }.failure)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun uploaderNamesRemainOneEncodedPathSegmentOnEitherService() = runTest {
        val name = "Owner / 日本語 ?x=1#quoted\""
        for (restricted in listOf(false, true)) {
            val engine = MockEngine { request ->
                assertEquals(if (restricted) "exhentai.org" else "e-hentai.org", request.url.host)
                assertEquals(listOf("uploader", name), request.url.pathSegments.filter { it.isNotEmpty() })
                assertTrue("%2F" in request.url.encodedPath, "A slash in the uploader must not create a new route")
                assertEquals("12-13", request.url.parameters["prev"])
                assertEquals(null, request.url.parameters["next"])
                assertEquals(null, request.url.parameters["f_search"])
                assertEquals(null, request.url.parameters["x"])
                assertEquals("", request.url.fragment)
                respond("test-html", HttpStatusCode.OK)
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
            try {
                assertEquals(result, GalleryRepository(client, decoder, restricted).uploader(name, "12-13", previous = true))
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun invalidUploaderNamesAndCursorsNeverReachTheTransport() = runTest {
        var sent = 0
        val engine = MockEngine {
            sent++
            respond("test-html", HttpStatusCode.OK)
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            val repository = GalleryRepository(client, decoder)
            for (name in listOf("", " ", ".", "..", "a\nb", "a".repeat(257))) {
                assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.uploader(name) }.failure)
            }
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.uploader("Valid owner", "../private") }.failure)
            assertEquals(0, sent)
        } finally {
            client.close()
            engine.close()
        }
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

    @Test fun malformedServiceCursorsAreRecoverableErrorsBeforeTheyCanBecomeNavigationRequests() = runTest {
        for (cursor in listOf("../private-query", "42&next=7", "1--2", "9".repeat(65), "", "42-")) {
            for (previous in listOf(false, true)) {
                var requests = 0
                val engine = MockEngine {
                    requests++
                    respond("test-html", HttpStatusCode.OK)
                }
                val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
                try {
                    val parsed = result.copy(prev = cursor.takeIf { previous }, next = cursor.takeUnless { previous })
                    val error = assertFailsWith<GalleryRequestException> { GalleryRepository(client, GalleryListDecoder { parsed }).search("owned-query") }
                    assertEquals(GalleryFailure.INVALID_RESPONSE, error.failure)
                    assertEquals(null, error.cause)
                    if (cursor.isNotEmpty()) assertFalse(error.toString().contains(cursor))
                    assertFalse(error.toString().contains("owned-query"))
                    assertEquals(1, requests)
                } finally {
                    client.close()
                    engine.close()
                }
            }
        }
    }

    @Test fun oversizedInputCursorsNeverSendARequest() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("test-html", HttpStatusCode.OK)
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "EhViewer-test")
        try {
            assertFailsWith<IllegalArgumentException> { GalleryRepository(client, decoder).search(cursor = "9".repeat(65)) }
            assertEquals(0, requests)
            GalleryRepository(client, decoder).search(cursor = "9".repeat(31) + "-" + "8".repeat(32))
            assertEquals(1, requests)
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
