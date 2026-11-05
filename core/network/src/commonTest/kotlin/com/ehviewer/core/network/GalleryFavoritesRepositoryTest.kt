package com.ehviewer.core.network

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryFavoriteNote
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.gallery.GalleryTorrent
import com.ehviewer.core.network.session.GalleryAccountProfile
import com.ehviewer.core.network.session.PersistentCookieStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
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
import kotlinx.coroutines.test.runTest
import okio.IOException

/** Controlled transport/decoders isolate protocol and account races; real Rust has separate tests. */
class GalleryFavoritesRepositoryTest {
    private class Fixture(val restricted: Boolean = false) {
        val host = if (restricted) "exhentai.org" else "e-hentai.org"
        val durable = TestSessionVault()
        val cookies = PersistentCookieStorage(durable)
        var epoch = 0L
        var category: Int? = null
        var note = ""
        var maximum = 200
        var categories = List(10) { "Shelf $it" }
        val requests = mutableListOf<HttpRequestData>()
        val posts = mutableListOf<Parameters>()
        val rawForms = mutableListOf<String>()
        var intercept: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null }
        var beforeCookies: suspend (Url) -> Unit = {}
        var afterCookies: suspend () -> Unit = {}
        private val decoder = object : GalleryDocumentDecoder {
            override fun decodeProfileLink(html: ByteArray): String? = error("No forum request expected")
            override fun decodeProfile(html: ByteArray): GalleryAccountProfile = error("No profile request expected")
            override fun decode(html: ByteArray): GallerySearchResult = error("No search request expected")
            override fun decodeTorrents(html: ByteArray): List<GalleryTorrent> = error("Torrent parsing is not requested by this regression")
            override fun decodePage(html: ByteArray): GalleryImagePage = error("No page request expected")
            override fun decodeUserConfig(html: ByteArray): List<String> {
                check(html.decodeToString() == "owned-config")
                return categories
            }
            override fun decodeFavoriteNote(html: ByteArray): GalleryFavoriteNote {
                val text = html.decodeToString()
                check(text.startsWith("owned-note:"))
                val fields = text.removePrefix("owned-note:").split(':', limit = 2)
                return GalleryFavoriteNote(fields[1], fields[0].toInt())
            }
            override fun decodeDetails(html: ByteArray): GalleryDetail {
                val text = html.decodeToString()
                check(text.startsWith("owned-detail:"))
                return GalleryDetail(
                    galleryInfo = BaseGalleryInfo(gid = 1, token = "0123456789", pages = 1, favoriteSlot = text.substringAfter(':').toInt()),
                    tagGroups = emptyList(),
                    comments = GalleryCommentList(emptyList(), false),
                    previewList = emptyList(),
                )
            }
        }
        private val engine = MockEngine { request ->
            requests += request
            assertEquals(host, request.url.host)
            if (request.method == HttpMethod.Post) {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                rawForms += body
                posts += parseQueryString(body)
            }
            intercept(request)?.let { return@MockEngine it }
            if (request.method == HttpMethod.Post) {
                val form = posts.last()
                category = form["favcat"]!!.toIntOrNull()
                note = if (category == null) "" else form["favnote"]!!
                respond("owned-accepted")
            } else {
                when (request.url.encodedPath) {
                    "/uconfig.php" -> respond("owned-config")
                    "/gallerypopups.php" -> respond("owned-note:$maximum:$note")
                    "/g/1/0123456789/" -> respond("owned-detail:${category ?: GalleryInfo.NOT_FAVORITED}")
                    else -> error("Unexpected owned request")
                }
            }
        }
        private val client = galleryHttpClient(
            engine,
            object : CookiesStorage by cookies {
                override suspend fun get(requestUrl: Url): List<Cookie> {
                    beforeCookies(requestUrl)
                    val attached = cookies.get(requestUrl)
                    afterCookies()
                    return attached
                }
            },
            "favorite-test",
        )
        val repository = GalleryFavoritesRepository(client, cookies, decoder, restricted) { epoch }
        suspend fun signIn(member: String = "owned-member") {
            for (site in listOf("e-hentai.org", "exhentai.org")) {
                cookies.addCookie(Url("https://$site/"), Cookie("ipb_member_id", member, path = "/"))
                cookies.addCookie(Url("https://$site/"), Cookie("ipb_pass_hash", "owned-fixture-secret", path = "/"))
            }
        }
        suspend fun inspect() = repository.inspect(1, "0123456789")
        fun close() {
            client.close()
            engine.close()
        }
    }

    private suspend fun owned(restricted: Boolean = false, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(restricted)
        try {
            fixture.signIn()
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    @Test fun originalFormEncodingAndServerConfirmationCoverAddEditAndRemove() = runTest {
        owned { fixture ->
            val before = fixture.durable.bytes!!.copyOf()
            val initial = fixture.inspect()
            assertNull(initial.category)
            assertEquals(List(10) { "Shelf $it" }, initial.categories)
            val text = "日本語 + & = <note>\nsecond"
            val added = fixture.repository.update(initial, 7, text)
            assertEquals(7, added.category)
            assertEquals(text, added.note.note)
            assertEquals(setOf("favcat", "favnote", "apply", "update"), fixture.posts.single().names())
            assertEquals("7", fixture.posts.single()["favcat"])
            assertEquals(text, fixture.posts.single()["favnote"])
            assertEquals("Apply Changes", fixture.posts.single()["apply"])
            assertEquals("1", fixture.posts.single()["update"])
            assertTrue(fixture.rawForms.single().contains("%E6%97%A5"))
            assertTrue(fixture.rawForms.single().contains("%2B"))
            val post = fixture.requests.single { it.method == HttpMethod.Post }
            assertEquals("https://e-hentai.org", post.headers[HttpHeaders.Origin])
            assertEquals("https://e-hentai.org/gallerypopups.php?gid=1&t=0123456789&act=addfav", post.headers[HttpHeaders.Referrer])
            assertTrue(post.headers[HttpHeaders.Cookie].orEmpty().contains("ipb_member_id=owned-member"))
            val edited = fixture.repository.update(added, 3, "New note")
            assertEquals(3, edited.category)
            val removed = fixture.repository.update(edited, null, edited.note.note)
            assertNull(removed.category)
            assertEquals("favdel", fixture.posts.last()["favcat"])
            assertEquals(3, fixture.posts.size)
            assertTrue(fixture.requests.filter { it.method == HttpMethod.Get }.all { it.headers[HttpHeaders.CacheControl] == "no-cache, no-store" })
            assertContentEquals(before, fixture.durable.bytes)
        }
    }

    @Test fun anonymousEditorFailsBeforeAnyPrivateHttpRequest() = runTest {
        val fixture = Fixture()
        try {
            assertEquals(GalleryFailure.AUTHENTICATION, assertFailsWith<GalleryRequestException> { fixture.inspect() }.failure)
            assertTrue(fixture.requests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun restrictedFavoritesKeepTheirOwnHostAndRejectAnotherSitesEditor() = runTest {
        owned { public ->
            owned(restricted = true) { restricted ->
                val state = restricted.inspect()
                assertEquals(2, restricted.repository.update(state, 2, "Restricted note").category)
                assertTrue(restricted.requests.all { it.url.host == "exhentai.org" })
                assertFailsWith<IllegalArgumentException> { public.repository.update(state, 2, "Wrong site") }
                assertTrue(public.requests.isEmpty())
            }
        }
    }

    @Test fun accountEpochChangeCancelsAnOldEditorBeforeMutation() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            fixture.epoch++
            assertFailsWith<CancellationException> { fixture.repository.update(state, 1, "Stale note") }
            assertTrue(fixture.posts.isEmpty())
        }
    }

    @Test fun borrowedAndroidStyleCookieReplacementAlsoCancelsTheOldEditor() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            fixture.signIn("different-owned-member")
            assertFailsWith<CancellationException> { fixture.repository.update(state, 1, "Wrong account") }
            assertTrue(fixture.posts.isEmpty())
        }
    }

    @Test fun sessionChangeAfterThePostCannotPublishSuccessOrSendAnotherRequest() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            fixture.intercept = { request ->
                if (request.method == HttpMethod.Post) {
                    fixture.epoch++
                    respond("owned-accepted")
                } else {
                    null
                }
            }
            assertFailsWith<CancellationException> { fixture.repository.update(state, 1, "Changed") }
            assertEquals(1, fixture.posts.size)
            assertEquals(4, fixture.requests.size)
        }
    }

    @Test fun accountReplacementWhileKtorAttachesCookiesCannotMutateAnotherAccount() = runTest {
        for (restoreBeforeSend in listOf(false, true)) {
            owned { fixture ->
                val state = fixture.inspect()
                fixture.beforeCookies = { url ->
                    if (url.encodedPath == "/gallerypopups.php") {
                        fixture.beforeCookies = {}
                        fixture.signIn("replaced-during-cookie-attachment")
                        if (restoreBeforeSend) {
                            fixture.afterCookies = {
                                fixture.afterCookies = {}
                                fixture.signIn()
                            }
                        }
                    }
                }
                assertFailsWith<CancellationException> { fixture.repository.update(state, 1, "Wrong account") }
                assertTrue(fixture.posts.isEmpty(), "Account restored before send: $restoreBeforeSend")
                assertNull(fixture.category)
            }
        }
    }

    @Test fun anUnchangedServerFavoriteCannotBeReportedAsASuccessfulWrite() = runTest {
        owned { fixture ->
            fixture.category = 2
            fixture.note = "Preserve me"
            val state = fixture.inspect()
            fixture.intercept = { request -> if (request.method == HttpMethod.Post) respond("owned-but-not-applied") else null }
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { fixture.repository.update(state, 3, "New note") }.failure)
            assertEquals(1, fixture.posts.size)
            assertEquals("Preserve me", fixture.inspect().note.note)
            assertEquals(1, fixture.posts.size)
        }
    }

    @Test fun aLostPostResponseIsNotRetriedAndReloadCanDiscoverTheAppliedWrite() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            fixture.intercept = { request ->
                if (request.method == HttpMethod.Post) {
                    fixture.category = 4
                    fixture.note = "Applied before disconnect"
                    throw IOException("private-owned-request")
                }
                null
            }
            val error = assertFailsWith<GalleryRequestException> { fixture.repository.update(state, 4, "Applied before disconnect") }
            assertEquals(GalleryFailure.OFFLINE, error.failure)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private-owned"))
            assertEquals("Applied before disconnect", fixture.inspect().note.note)
            assertEquals(1, fixture.posts.size)
        }
    }

    @Test fun unsafeRedirectsNeverReceiveTheFormOrIdentity() = runTest {
        for (destination in listOf("https://untrusted.invalid/", "http://e-hentai.org/", "https://user:secret@e-hentai.org/", "https://forums.e-hentai.org/index.php?act=Login&CODE=00")) {
            owned { fixture ->
                val state = fixture.inspect()
                fixture.intercept = { request -> if (request.method == HttpMethod.Post) respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, destination)) else null }
                assertFailsWith<GalleryRequestException> { fixture.repository.update(state, 2, "Keep private") }
                assertEquals(1, fixture.posts.size)
                assertTrue(fixture.requests.all { it.url.host == fixture.host })
            }
        }
    }

    @Test fun aSameSiteRedirectIsConfirmedWithKnownGetsAndNeverReplaysThePost() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            fixture.intercept = { request ->
                if (request.method == HttpMethod.Post) {
                    fixture.category = 5
                    fixture.note = "Confirmed"
                    respond("", HttpStatusCode.SeeOther, headersOf(HttpHeaders.Location, "/unexpected-redirect?opaque=owned"))
                } else {
                    null
                }
            }
            assertEquals(5, fixture.repository.update(state, 5, "Confirmed").category)
            assertEquals(1, fixture.posts.size)
            assertTrue(fixture.requests.none { it.url.encodedPath == "/unexpected-redirect" })
        }
    }

    @Test fun mutationStatusesAreSanitizedAndNeverAutomaticallyRetried() = runTest {
        for ((status, expected) in listOf(401 to GalleryFailure.AUTHENTICATION, 403 to GalleryFailure.CHALLENGE, 429 to GalleryFailure.RATE_LIMIT, 500 to GalleryFailure.SERVER, 409 to GalleryFailure.INVALID_RESPONSE)) {
            owned { fixture ->
                val state = fixture.inspect()
                fixture.intercept = { request -> if (request.method == HttpMethod.Post) respond("private-owned-body", HttpStatusCode.fromValue(status)) else null }
                val error = assertFailsWith<GalleryRequestException> { fixture.repository.update(state, 2, "Owned") }
                assertEquals(expected, error.failure)
                assertFalse(error.toString().contains("private-owned"))
                assertEquals(1, fixture.posts.size)
            }
        }
    }

    @Test fun limitsAndInvalidArgumentsCannotReachTheMutationEndpoint() = runTest {
        owned { fixture ->
            fixture.maximum = 4
            val state = fixture.inspect()
            assertFailsWith<IllegalArgumentException> { fixture.repository.update(state, 10, "") }
            assertFailsWith<IllegalArgumentException> { fixture.repository.update(state, 1, "12345") }
            assertFailsWith<IllegalArgumentException> { fixture.repository.update(state, 1, "\u0000") }
            assertFailsWith<IllegalArgumentException> { fixture.repository.inspect(-1, "0123456789") }
            assertTrue(fixture.posts.isEmpty())
        }
    }

    @Test fun oversizedMutationBodiesAreBoundedAndCannotBecomeSuccessfulWrites() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            fixture.intercept = { request -> if (request.method == HttpMethod.Post) respond("x", headers = headersOf(HttpHeaders.ContentLength, "8388609")) else null }
            assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { fixture.repository.update(state, 1, "Owned") }.failure)
            assertEquals(1, fixture.posts.size)
        }
    }

    @Test fun cancellationClosesTheAdmittedMutationBodyWithoutASecondPost() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            val body = ByteChannel()
            val started = CompletableDeferred<Unit>()
            val admitted = object : ByteReadChannel by body {
                override suspend fun awaitContent(min: Int): Boolean {
                    started.complete(Unit)
                    return body.awaitContent(min)
                }
            }
            fixture.intercept = { request ->
                if (request.method == HttpMethod.Post) {
                    respond(admitted)
                } else {
                    null
                }
            }
            val updating = async { fixture.repository.update(state, 2, "Owned") }
            try {
                started.await()
                updating.cancelAndJoin()
                assertEquals(1, fixture.posts.size)
                assertTrue(body.isClosedForRead)
            } finally {
                updating.cancelAndJoin()
                body.cancel(null)
            }
        }
    }

    @Test fun malformedConfigurationAndNotesCannotProduceAnEditableState() = runTest {
        owned { fixture ->
            fixture.categories = listOf("Only one")
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { fixture.inspect() }.failure)
            fixture.categories = List(10) { "Shelf $it" }
            fixture.maximum = 0
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { fixture.inspect() }.failure)
            assertTrue(fixture.posts.isEmpty())
        }
    }

    @Test fun textareaNewlinesUseTheSameCanonicalValueForWriteAndReadback() = runTest {
        owned { fixture ->
            val state = fixture.inspect()
            val result = fixture.repository.update(state, 8, "first\r\nsecond\rthird")
            assertEquals("first\nsecond\nthird", result.note.note)
            assertEquals("first\nsecond\nthird", fixture.posts.single()["favnote"])
        }
    }
}
