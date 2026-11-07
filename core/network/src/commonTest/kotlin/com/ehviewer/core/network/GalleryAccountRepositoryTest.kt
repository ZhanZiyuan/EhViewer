package com.ehviewer.core.network

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.session.BrowserSessionCookie
import com.ehviewer.core.network.session.GalleryAccountDecoder
import com.ehviewer.core.network.session.GalleryAccountProfile
import com.ehviewer.core.network.session.GalleryAccountRepository
import com.ehviewer.core.network.session.GalleryAuthenticationRepository
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.RestrictedSiteAccess
import com.ehviewer.core.network.session.SessionVault
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GalleryAccountRepositoryTest {
    private val root = Url("https://e-hentai.org/")
    private val categories = List(10) { "Shelf $it" }
    private fun decoder(link: String? = "https://forums.e-hentai.org/index.php?showuser=123") = object : GalleryAccountDecoder {
        override fun decodeProfileLink(html: ByteArray): String? {
            assertEquals("forum", html.decodeToString())
            return link
        }
        override fun decodeProfile(html: ByteArray): GalleryAccountProfile {
            assertEquals("profile", html.decodeToString())
            return GalleryAccountProfile("Test reader", "https://forums.e-hentai.org/avatar.png")
        }
        override fun decodeUserConfig(html: ByteArray): List<String> {
            require(html.decodeToString() == "config")
            return categories
        }
    }

    private suspend fun signedStore(vault: SessionVault): PersistentCookieStorage = PersistentCookieStorage(vault).also { store ->
        for ((name, value) in listOf("ipb_member_id" to "synthetic-member", "ipb_pass_hash" to "synthetic-secret")) {
            store.addCookie(root, Cookie(name, value, domain = ".e-hentai.org", path = "/", secure = true, httpOnly = true))
        }
    }

    private fun body(url: Url) = when {
        url.host == "forums.e-hentai.org" && url.encodedPath == "/" -> "forum"
        url.host == "forums.e-hentai.org" -> "profile"
        url.encodedPath == "/news.php" -> "news"
        else -> "config"
    }

    private fun restrictedHeaders() = headersOf(
        HttpHeaders.SetCookie,
        listOf(
            "ipb_member_id=synthetic-ex-member; Path=/; Secure; HttpOnly",
            "ipb_pass_hash=synthetic-ex-secret; Path=/; Secure; HttpOnly",
            "igneous=synthetic-ex; Path=/; Secure; HttpOnly",
        ),
    )

    @Test fun realRequestSequencePreservesIdentityAndCommitsAllInitializationCookiesOnce() = runTest {
        val durable = TestSessionVault()
        var writes = 0
        val vault = object : SessionVault by durable {
            override suspend fun write(bytes: ByteArray) {
                writes++
                durable.write(bytes)
            }
        }
        val store = signedStore(vault)
        writes = 0
        val visited = mutableListOf<String>()
        var restrictedRequests = 0
        val repository = GalleryAccountRepository(store, decoder(), "EhViewer/account-test") {
            MockEngine { request ->
                visited += "${request.url.host}${request.url.encodedPath}"
                assertEquals("EhViewer/account-test", request.headers[HttpHeaders.UserAgent])
                val sent = request.headers[HttpHeaders.Cookie].orEmpty()
                if (request.url.host == "exhentai.org") {
                    assertFalse(sent.contains("synthetic-member"))
                    when (++restrictedRequests) {
                        1 -> {
                            assertFalse(sent.contains("ipb_"))
                            return@MockEngine respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://forums.e-hentai.org/remoteapi.php?synthetic=callback"))
                        }
                        2 -> {
                            assertFalse(sent.contains("ipb_"))
                            return@MockEngine respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location to listOf("/"), HttpHeaders.SetCookie to restrictedHeaders().getAll(HttpHeaders.SetCookie)!!))
                        }
                        3 -> return@MockEngine respond("gallery")
                    }
                    assertTrue(sent.contains("ipb_member_id=synthetic-ex-member"))
                } else {
                    assertTrue(sent.contains("ipb_member_id=synthetic-member"))
                    assertFalse(sent.contains("synthetic-ex-member"))
                    if (request.url.encodedPath == "/remoteapi.php") {
                        return@MockEngine respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://exhentai.org/?synthetic=callback"))
                    }
                }
                val headers = if (request.url.encodedPath == "/news.php") headersOf(HttpHeaders.SetCookie, "star=synthetic-star; Path=/; Secure") else headersOf()
                respond(body(request.url), headers = headers)
            }
        }
        val result = repository.refresh()
        assertEquals("Test reader", result.profile.displayName)
        assertEquals(categories, result.favoriteCategories)
        assertEquals(RestrictedSiteAccess.AVAILABLE, result.restrictedAccess)
        assertTrue(result.newsInitialized)
        assertEquals(listOf("forums.e-hentai.org/", "forums.e-hentai.org/index.php", "e-hentai.org/uconfig.php", "e-hentai.org/news.php", "exhentai.org/uconfig.php", "forums.e-hentai.org/remoteapi.php", "exhentai.org/", "exhentai.org/", "exhentai.org/uconfig.php"), visited)
        assertEquals(1, writes)
        assertTrue(PersistentCookieStorage(durable).isSignedIn())
        assertEquals("synthetic-ex", store.get(Url("https://exhentai.org/")).single { it.name == "igneous" }.value)
        assertEquals("synthetic-star", store.get(root).single { it.name == "star" }.value)
    }

    @Test fun guestAndRevokedForumIdentityNeverCommitOrFollowAProfile() = runTest {
        val empty = PersistentCookieStorage(TestSessionVault())
        val noNetwork = GalleryAccountRepository(empty, decoder(), "test") { error("A guest must not create an engine") }
        assertEquals(GalleryFailure.AUTHENTICATION, assertFailsWith<GalleryRequestException> { noNetwork.refresh() }.failure)
        val vault = TestSessionVault()
        val store = signedStore(vault)
        val previous = vault.bytes!!.copyOf()
        var requests = 0
        val revoked = GalleryAccountRepository(store, decoder(null), "test") {
            MockEngine {
                requests++
                respond("forum")
            }
        }
        assertEquals(GalleryFailure.AUTHENTICATION, assertFailsWith<GalleryRequestException> { revoked.refresh() }.failure)
        assertEquals(1, requests)
        assertContentEquals(previous, vault.bytes)
    }

    @Test fun untrustedProfileLinksAreRejectedBeforeSendingCookiesToThem() = runTest {
        for (url in listOf("https://evil.example/index.php?showuser=123", "http://forums.e-hentai.org/index.php?showuser=123", "https://forums.e-hentai.org:444/index.php?showuser=123", "https://member@forums.e-hentai.org/index.php?showuser=123", "https://forums.e-hentai.org/index.php?showuser=oops", "https://forums.e-hentai.org/login?showuser=123")) {
            val vault = TestSessionVault()
            val store = signedStore(vault)
            val previous = vault.bytes!!.copyOf()
            var requests = 0
            val repository = GalleryAccountRepository(store, decoder(url), "test") {
                MockEngine {
                    requests++
                    respond("forum")
                }
            }
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.refresh() }.failure)
            assertEquals(1, requests)
            assertContentEquals(previous, vault.bytes)
        }
    }

    @Test fun failedPublicConfigDiscardsAllStagedServerCookies() = runTest {
        val vault = TestSessionVault()
        val store = signedStore(vault)
        val previous = vault.bytes!!.copyOf()
        val repository = GalleryAccountRepository(store, decoder(), "test") {
            MockEngine { request ->
                respond(if (request.url.encodedPath == "/uconfig.php") "malformed" else body(request.url), headers = headersOf(HttpHeaders.SetCookie, "ipb_pass_hash=staged-only; Domain=.e-hentai.org; Path=/; Secure"))
            }
        }
        assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.refresh() }.failure)
        assertContentEquals(previous, vault.bytes)
        assertEquals("synthetic-secret", store.get(root).single { it.name == "ipb_pass_hash" }.value)
    }

    @Test fun restrictedInitializationRetriesOnlyOneMalformedDocument() = runTest {
        val store = signedStore(TestSessionVault())
        var restrictedRequests = 0
        val repository = GalleryAccountRepository(store, decoder(), "test") {
            MockEngine { request ->
                val result = if (request.url.host == "exhentai.org" && ++restrictedRequests == 1) "binary-panda" else body(request.url)
                respond(result, headers = if (request.url.host == "exhentai.org") restrictedHeaders() else headersOf())
            }
        }
        assertEquals(RestrictedSiteAccess.AVAILABLE, repository.refresh().restrictedAccess)
        assertEquals(2, restrictedRequests)
    }

    @Test fun restrictedFailureKeepsTheValidPublicSessionAndRollsBackPartialRestrictedCookies() = runTest {
        data class Case(val status: HttpStatusCode, val failure: GalleryFailure, val access: RestrictedSiteAccess, val attempts: Int)
        for ((status, expected, access, attempts) in listOf(
            Case(HttpStatusCode.OK, GalleryFailure.INVALID_RESPONSE, RestrictedSiteAccess.UNAVAILABLE, 2),
            Case(HttpStatusCode.Forbidden, GalleryFailure.CHALLENGE, RestrictedSiteAccess.UNKNOWN, 1),
        )) {
            val vault = TestSessionVault()
            val store = signedStore(vault)
            var exRequests = 0
            val repository = GalleryAccountRepository(store, decoder(), "test") {
                MockEngine { request ->
                    if (request.url.host == "exhentai.org") {
                        exRequests++
                        respond("unavailable", status, headersOf(HttpHeaders.SetCookie, "igneous=partial; Path=/; Secure"))
                    } else {
                        respond(body(request.url))
                    }
                }
            }
            val overview = repository.refresh()
            assertEquals(access, overview.restrictedAccess)
            assertEquals(expected, overview.restrictedFailure)
            assertEquals(attempts, exRequests)
            assertTrue(store.isSignedIn())
            assertTrue(store.get(Url("https://exhentai.org/")).none { it.name == "igneous" })
        }
    }

    @Test fun logoutDuringRefreshCannotRestoreIdentityOrWriteItsStagedResult() = runTest {
        val vault = TestSessionVault()
        val store = signedStore(vault)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val repository = GalleryAccountRepository(store, decoder(), "test") {
            MockEngine { request ->
                requests++
                if (request.url.host == "forums.e-hentai.org" && request.url.encodedPath == "/index.php") {
                    entered.complete(Unit)
                    release.await()
                }
                respond(body(request.url))
            }
        }
        val result = async { repository.refresh() }
        entered.await()
        store.logout()
        release.complete(Unit)
        assertFailsWith<CancellationException> { result.await() }
        assertFalse(store.isSignedIn())
        assertEquals(null, vault.bytes)
        assertEquals(2, requests)
    }

    @Test fun newerBrowserLoginWinsOverAnOlderAccountRefresh() = runTest {
        val vault = TestSessionVault()
        val store = signedStore(vault)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val repository = GalleryAccountRepository(store, decoder(), "test") {
            MockEngine { request ->
                requests++
                if (request.url.host == "forums.e-hentai.org" && request.url.encodedPath == "/index.php") {
                    entered.complete(Unit)
                    release.await()
                }
                respond(body(request.url))
            }
        }
        val old = async { repository.refresh() }
        entered.await()
        GalleryAuthenticationRepository(store, "test").beginBrowserSignIn().complete(
            listOf(
                BrowserSessionCookie("ipb_member_id", "new-member", ".e-hentai.org", "/", true, true, null),
                BrowserSessionCookie("ipb_pass_hash", "new-secret", ".e-hentai.org", "/", true, true, null),
            ),
        )
        val previous = vault.bytes!!.copyOf()
        release.complete(Unit)
        assertFailsWith<CancellationException> { old.await() }
        assertContentEquals(previous, vault.bytes)
        assertEquals("new-member", store.get(root).single { it.name == "ipb_member_id" }.value)
        assertEquals(2, requests)
    }

    @Test fun cancelledRefreshPreservesTheOriginalDurableSnapshot() = runTest {
        val vault = TestSessionVault()
        val store = signedStore(vault)
        val previous = vault.bytes!!.copyOf()
        val entered = CompletableDeferred<Unit>()
        val repository = GalleryAccountRepository(store, decoder(), "test") {
            MockEngine {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val task = launch { repository.refresh() }
        entered.await()
        task.cancelAndJoin()
        assertContentEquals(previous, vault.bytes)
        assertTrue(store.isSignedIn())
    }

    @Test fun restrictedConfigWithoutItsOwnIdentityCannotEnableTheRestrictedSite() = runTest {
        val store = signedStore(TestSessionVault())
        val repository = GalleryAccountRepository(store, decoder(), "test") { MockEngine { respond(body(it.url)) } }
        val result = repository.refresh()
        assertEquals(RestrictedSiteAccess.UNAVAILABLE, result.restrictedAccess)
        assertEquals(GalleryFailure.AUTHENTICATION, result.restrictedFailure)
        assertTrue(store.get(Url("https://exhentai.org/")).isEmpty())
    }

    @Test fun restrictedNetworkFailureRollsBackItsCookiesAndPreservesPublicInitialization() = runTest {
        val store = signedStore(TestSessionVault())
        val repository = GalleryAccountRepository(store, decoder(), "test") {
            MockEngine { request ->
                if (request.url.host == "exhentai.org") throw IllegalStateException("synthetic transport failure")
                respond(body(request.url), headers = headersOf(HttpHeaders.SetCookie, "star=public-only; Path=/; Secure"))
            }
        }
        val result = repository.refresh()
        assertEquals(RestrictedSiteAccess.UNKNOWN, result.restrictedAccess)
        assertEquals(GalleryFailure.OFFLINE, result.restrictedFailure)
        assertTrue(store.isSignedIn())
        assertEquals("public-only", store.get(root).single { it.name == "star" }.value)
        assertTrue(store.get(Url("https://exhentai.org/")).isEmpty())
    }

    @Test fun failedAtomicVaultCommitDoesNotReplaceTheDurableOrInMemoryIdentity() = runTest {
        val durable = TestSessionVault()
        var failWrite = false
        val vault = object : SessionVault by durable {
            override suspend fun write(bytes: ByteArray) {
                if (failWrite) throw IllegalStateException("synthetic secret vault error")
                durable.write(bytes)
            }
        }
        val store = signedStore(vault)
        val previous = durable.bytes!!.copyOf()
        failWrite = true
        val repository = GalleryAccountRepository(store, decoder(), "test") {
            MockEngine { request ->
                respond(body(request.url), headers = headersOf(HttpHeaders.SetCookie, "star=staged; Path=/; Secure"))
            }
        }
        val error = assertFailsWith<GalleryRequestException> { repository.refresh() }
        assertEquals(GalleryFailure.SESSION_STORAGE, error.failure)
        assertFalse(error.toString().contains("synthetic secret"))
        assertContentEquals(previous, durable.bytes)
        assertTrue(store.isSignedIn())
        assertTrue(store.get(root).none { it.name == "star" })
    }

    @Test fun redirectedProfileRequestsRejectForeignOriginsPortsAndLoginBeforeContact() = runTest {
        for (location in listOf("https://evil.example/profile", "https://forums.e-hentai.org:444/index.php?showuser=123", "http://forums.e-hentai.org/index.php?showuser=123", "https://forums.e-hentai.org/index.php?act=Login")) {
            val vault = TestSessionVault()
            val store = signedStore(vault)
            val previous = vault.bytes!!.copyOf()
            var requests = 0
            val repository = GalleryAccountRepository(store, decoder(), "test") {
                MockEngine { request ->
                    requests++
                    if (request.url.encodedPath == "/") {
                        respond("forum")
                    } else {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                    }
                }
            }
            val error = assertFailsWith<GalleryRequestException> { repository.refresh() }
            assertEquals(if (location.endsWith("act=Login")) GalleryFailure.AUTHENTICATION else GalleryFailure.INVALID_RESPONSE, error.failure)
            assertEquals(2, requests)
            assertContentEquals(previous, vault.bytes)
        }
    }
}
