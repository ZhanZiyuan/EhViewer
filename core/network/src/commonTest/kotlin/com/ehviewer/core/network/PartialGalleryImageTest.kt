package com.ehviewer.core.network

import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.domain.download.GalleryImageCheckpoint
import com.ehviewer.core.domain.download.GalleryImageIdentity
import com.ehviewer.core.domain.download.GalleryImageProgress
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GalleryRequests
import com.ehviewer.core.network.session.PersistentCookieStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Cookie
import io.ktor.http.Headers
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

class PartialGalleryImageTest {
    private val initial = Url("https://owned.hath.network/image?private-signed-query")
    private val referer = Url("https://e-hentai.org/s/1111111111/1-1")
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)
    private fun identity(target: Url = initial) = GalleryImageIdentity("1111111111", initial.toString().encodeUtf8().sha256().hex(), target.toString().encodeUtf8().sha256().hex(), "\"owned\"", bytes.size.toLong())
    private fun headers(length: Int = bytes.size, etag: String? = "\"owned\"", range: String? = null, encoding: String? = null, type: String = "image/png") = Headers.build {
        append(HttpHeaders.ContentType, type)
        append(HttpHeaders.ContentLength, length.toString())
        etag?.let { append(HttpHeaders.ETag, it) }
        range?.let { append(HttpHeaders.ContentRange, it) }
        encoding?.let { append(HttpHeaders.ContentEncoding, it) }
    }

    private class Checkpoint : GalleryImageCheckpoint {
        var state = GalleryImageProgress()
        var data = byteArrayOf()
        var failWrite = false
        override suspend fun progress() = state
        override suspend fun reset(identity: GalleryImageIdentity?) {
            data = byteArrayOf()
            state = GalleryImageProgress(identity)
        }
        override suspend fun append(bytes: ByteArray) {
            if (failWrite) throw GalleryDownloadStorageException()
            data += bytes
            state = state.copy(bytes = data.size.toLong(), chunks = data.asList().chunked(GalleryImageProgress.CHUNK_BYTES).map { it.toByteArray().toByteString().sha256().hex() }).also { it.validate() }
        }
        override suspend fun finish() {
            state = state.copy(complete = true).also { it.validate() }
        }
        override suspend fun readComplete(): ByteArray {
            check(state.complete)
            return data.copyOf()
        }
        override suspend fun discard() {
            reset(null)
        }
    }

    private suspend fun fetch(engine: MockEngine, checkpoint: Checkpoint, url: Url = initial, before: suspend () -> Unit = {}): ByteArray {
        val cookies = PersistentCookieStorage(TestSessionVault())
        cookies.addCookie(Url("https://e-hentai.org/"), Cookie("ipb_pass_hash", "owned-secret", domain = "e-hentai.org", path = "/"))
        val client = galleryHttpClient(engine, cookies, "test")
        try {
            return GalleryRequests(client, "e-hentai.org", before).partialImage(url, referer, "1111111111", checkpoint)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun actualCancelledBodyRetainsItsTailAndResumesWithAnExactConditionalRange() = runTest {
        val checkpoint = Checkpoint()
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully(bytes.copyOf(3))
        val consumed = CompletableDeferred<Unit>()
        var boundaries = 0
        val first = MockEngine { respond(channel, HttpStatusCode.OK, headers()) }
        val request = async { fetch(first, checkpoint) { if (++boundaries == 5) consumed.complete(Unit) } }
        consumed.await() // The next read boundary follows consumption of the prefilled body prefix.
        assertFalse(checkpoint.state.complete)
        request.cancelAndJoin()
        assertEquals(3L, checkpoint.state.bytes)
        val requests = mutableListOf<HttpRequestData>()
        val second = MockEngine { data ->
            requests += data
            respond(bytes.copyOfRange(3, 5), HttpStatusCode.PartialContent, headers(2, range = "bytes 3-4/5"))
        }
        assertContentEquals(bytes, fetch(second, checkpoint))
        assertEquals("bytes=3-", requests.single().headers[HttpHeaders.Range])
        assertEquals("\"owned\"", requests.single().headers[HttpHeaders.IfRange])
        assertEquals("identity", requests.single().headers[HttpHeaders.AcceptEncoding])
        assertEquals(referer.toString(), requests.single().headers[HttpHeaders.Referrer])
        assertNull(requests.single().headers[HttpHeaders.Cookie])
        assertTrue(checkpoint.state.complete)
    }

    @Test fun a200ResponseReplacesThePrefixEvenWhenTheValidatorChangesOrRangesAreIgnored() = runTest {
        for (etag in listOf("\"owned\"", "\"changed\"", "W/\"weak\"", null)) {
            val checkpoint = Checkpoint().apply {
                reset(identity())
                append(byteArrayOf(9, 9))
            }
            val engine = MockEngine {
                assertEquals("bytes=2-", it.headers[HttpHeaders.Range])
                respond(bytes, headers = headers(etag = etag))
            }
            assertContentEquals(bytes, fetch(engine, checkpoint))
            if (etag?.startsWith("\"") == true) assertEquals(etag, checkpoint.state.identity?.etag) else assertNull(checkpoint.state.identity)
        }
    }

    @Test fun weakMissingAndInvalidValidatorsNeverCreateAResumableCheckpoint() = runTest {
        for (etag in listOf(null, "W/\"weak\"", "unquoted", "\"embedded\"quote\"", "\"" + "x".repeat(513) + "\"")) {
            val checkpoint = Checkpoint()
            assertContentEquals(bytes, fetch(MockEngine { respond(bytes, headers = headers(etag = etag)) }, checkpoint))
            assertEquals(GalleryImageProgress(), checkpoint.state)
        }
        val checkpoint = Checkpoint()
        assertContentEquals(bytes, fetch(MockEngine { respond(bytes, headers = headersOf(HttpHeaders.ETag, "\"owned\"")) }, checkpoint))
        assertNull(checkpoint.state.identity)
    }

    @Test fun changedSignedSourceStartsOverWithoutSendingItsPreviousRange() = runTest {
        val checkpoint = Checkpoint().apply {
            reset(identity())
            append(bytes.copyOf(2))
        }
        val changed = Url("https://owned.hath.network/changed?different-private-query")
        assertContentEquals(
            bytes,
            fetch(
                MockEngine {
                    assertNull(it.headers[HttpHeaders.Range])
                    respond(bytes, headers = headers())
                },
                checkpoint,
                changed,
            ),
        )
        assertEquals(changed.toString().encodeUtf8().sha256().hex(), checkpoint.state.identity?.sourceSha256)
        assertFalse(checkpoint.state.toString().contains("private"))
    }

    @Test fun changedFinalTargetOrValidatorAndMalformedOrMultipartRangesNeverAppend() = runTest {
        val bad = listOf(
            headers(3, range = "bytes 1-3/5"), headers(3, range = "bytes 2-4/6"), headers(2, range = "bytes 2-4/5"),
            headers(3, range = "bytes 2-3/5"), headers(3, range = "bytes 2-4/*"), headers(3, range = "bytes 2-4/999999999999999999999999"),
            headers(3, range = null), headers(3, etag = "\"changed\"", range = "bytes 2-4/5"),
            headers(3, range = "bytes 2-4/5", type = "multipart/byteranges; boundary=owned"), headers(3, range = "bytes 2-4/5", encoding = "gzip"),
        )
        for (responseHeaders in bad) {
            val checkpoint = Checkpoint().apply {
                reset(identity())
                append(bytes.copyOf(2))
            }
            val failure = assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond(bytes.copyOfRange(2, 5), HttpStatusCode.PartialContent, responseHeaders) }, checkpoint) }
            assertEquals(GalleryFailure.INVALID_RESPONSE, failure.failure)
            assertEquals(0L, checkpoint.state.bytes)
        }
        val checkpoint = Checkpoint().apply {
            reset(identity(Url("https://other.hath.network/image")))
            append(bytes.copyOf(2))
        }
        assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond(bytes.copyOfRange(2, 5), HttpStatusCode.PartialContent, headers(3, range = "bytes 2-4/5")) }, checkpoint) }
        assertNull(checkpoint.state.identity)
    }

    @Test fun shortBodyKeepsOnlyAnIncompletePrefixAndAnExcessBodyCannotBecomeComplete() = runTest {
        val short = Checkpoint()
        val failure = assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond(bytes.copyOf(3), headers = headers()) }, short) }
        assertEquals(GalleryFailure.INVALID_RESPONSE, failure.failure)
        assertEquals(3L, short.state.bytes)
        assertFalse(short.state.complete)
        val excess = Checkpoint()
        assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond(bytes + 6, headers = headers()) }, excess) }
        assertEquals(0L, excess.state.bytes)
        assertFalse(excess.state.complete)
    }

    @Test fun aFullPrefixWithoutValidatedEofRestartsWhileACompleteCheckpointNeedsNoImageRequest() = runTest {
        val incomplete = Checkpoint().apply {
            reset(identity())
            append(bytes)
        }
        assertContentEquals(
            bytes,
            fetch(
                MockEngine {
                    assertNull(it.headers[HttpHeaders.Range])
                    respond(bytes, headers = headers())
                },
                incomplete,
            ),
        )
        val complete = Checkpoint().apply {
            reset(identity())
            append(bytes)
            finish()
        }
        assertContentEquals(bytes, fetch(MockEngine { error("Complete image must not be fetched again") }, complete))
        assertFailsWith<CancellationException> { fetch(MockEngine { error("Session changed") }, complete) { throw CancellationException("Session changed") } }
    }

    @Test fun redirectsKeepHostCookieAndSessionBoundariesAndRejectUnsafeDestinations() = runTest {
        val final = Url("https://owned.hath.network/actual.png")
        val resumed = Checkpoint().apply {
            reset(identity(final))
            append(bytes.copyOf(2))
        }
        val safeRequests = mutableListOf<HttpRequestData>()
        assertContentEquals(
            bytes,
            fetch(
                MockEngine { data ->
                    safeRequests += data
                    if (data.url == initial) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/actual.png")) else respond(bytes.copyOfRange(2, 5), HttpStatusCode.PartialContent, headers(3, etag = null, range = "bytes 2-4/5"))
                },
                resumed,
            ),
        )
        assertEquals(listOf(initial, final), safeRequests.map { it.url })
        assertTrue(safeRequests.all { it.headers[HttpHeaders.Range] == "bytes=2-" && it.headers[HttpHeaders.Cookie] == null && it.headers[HttpHeaders.Referrer] == referer.toString() })
        val requests = mutableListOf<HttpRequestData>()
        var generation = false
        val checkpoint = Checkpoint()
        val redirect = MockEngine { data ->
            requests += data
            generation = true
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://ehgt.org/next.png"))
        }
        assertFailsWith<CancellationException> { fetch(redirect, checkpoint) { if (generation) throw CancellationException("Session changed") } }
        assertEquals(1, requests.size)
        for (destination in listOf("http://ehgt.org/a", "https://untrusted.invalid/a", "https://user:password@ehgt.org/a", "https://ehgt.org:8443/a", "https://forums.e-hentai.org/index.php?act=Login")) {
            var count = 0
            val failure = assertFailsWith<GalleryRequestException> {
                fetch(
                    MockEngine {
                        count++
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, destination))
                    },
                    Checkpoint(),
                )
            }
            assertEquals(if (destination.contains("Login")) GalleryFailure.AUTHENTICATION else GalleryFailure.INVALID_RESPONSE, failure.failure)
            assertEquals(1, count)
        }
        var loops = 0
        assertFailsWith<GalleryRequestException> {
            fetch(
                MockEngine {
                    loops++
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/loop"))
                },
                Checkpoint(),
            )
        }
        assertEquals(6, loops)
    }

    @Test fun statusAndStorageFailuresRemainExplicitAndDoNotExposePrivateUrls() = runTest {
        for ((code, expected) in mapOf(401 to GalleryFailure.AUTHENTICATION, 403 to GalleryFailure.CHALLENGE, 429 to GalleryFailure.RATE_LIMIT, 503 to GalleryFailure.SERVER, 416 to GalleryFailure.INVALID_RESPONSE)) {
            val failure = assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond("private-body", HttpStatusCode.fromValue(code)) }, Checkpoint()) }
            assertEquals(expected, failure.failure)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("private"))
        }
        val checkpoint = Checkpoint().apply { failWrite = true }
        assertFailsWith<GalleryDownloadStorageException> { fetch(MockEngine { respond(bytes, headers = headers()) }, checkpoint) }
    }

    @Test fun unsolicitedRangesTransformedImagesAndOversizedDeclarationsAreRejected() = runTest {
        val unsolicited = Checkpoint()
        assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond(bytes, HttpStatusCode.PartialContent, headers(range = "bytes 0-4/5")) }, unsolicited) }
        for (encoding in listOf("gzip", "br")) assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond(bytes, headers = headers(encoding = encoding)) }, Checkpoint()) }
        val failure = assertFailsWith<GalleryRequestException> { fetch(MockEngine { respond(bytes, headers = headers(32 * 1024 * 1024 + 1)) }, Checkpoint()) }
        assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, failure.failure)
    }
}
