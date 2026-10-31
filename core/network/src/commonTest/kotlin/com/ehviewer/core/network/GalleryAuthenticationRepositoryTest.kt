package com.ehviewer.core.network

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.session.AuthenticationCookieJar
import com.ehviewer.core.network.session.GalleryAuthenticationRepository
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.decodeURLQueryComponent
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
import kotlinx.coroutines.test.runTest

class GalleryAuthenticationRepositoryTest {
    private val root = Url("https://e-hentai.org/")
    private val success = "<html><p>You are now logged in as: Test member</p></html>"
    private val identity = listOf("ipb_member_id=test-member; Domain=.e-hentai.org; Path=/; Secure; HttpOnly", "ipb_pass_hash=test-only-secret; Domain=.e-hentai.org; Path=/; Secure; HttpOnly")

    @Test fun actualForumFormEncodingAndSuccessfulCookiesCommitExactlyOnce() = runTest {
        val durable = TestSessionVault()
        var writes = 0
        val vault = object : SessionVault by durable {
            override suspend fun write(bytes: ByteArray) {
                writes++
                durable.write(bytes)
            }
        }
        val store = PersistentCookieStorage(vault)
        val repository = GalleryAuthenticationRepository(store, "test") {
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("https://forums.e-hentai.org/index.php?act=Login&CODE=01", request.url.toString())
                val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().split('&').associate { pair ->
                    val parts = pair.split('=', limit = 2)
                    parts[0].decodeURLQueryComponent(plusIsSpace = true) to parts[1].decodeURLQueryComponent(plusIsSpace = true)
                }
                assertEquals("member + 日本語", form["UserName"])
                assertEquals("private&test=+", form["PassWord"])
                assertEquals("1", form["CookieDate"])
                assertEquals("https://forums.e-hentai.org", request.headers[HttpHeaders.Origin])
                assertEquals(null, durable.bytes)
                respond(success, headers = headersOf(HttpHeaders.SetCookie, identity))
            }
        }
        repository.signIn("member + 日本語", "private&test=+")
        assertEquals(1, writes)
        assertTrue(store.isSignedIn())
        assertTrue(PersistentCookieStorage(durable).isSignedIn())
        assertTrue(store.get(root).all { it.httpOnly && it.secure })
    }

    @Test fun rejectedOrIncompleteLoginNeverReplacesAnExistingSession() = runTest {
        for ((body, headers) in listOf("<h4>The error returned was:</h4><p>test-only-private-error</p>" to identity, success to identity.take(1))) {
            val vault = TestSessionVault()
            val store = PersistentCookieStorage(vault)
            store.addCookie(root, Cookie("ipb_member_id", "old-member", path = "/"))
            store.addCookie(root, Cookie("ipb_pass_hash", "old-secret", path = "/"))
            val original = vault.bytes!!.copyOf()
            val repository = GalleryAuthenticationRepository(store, "test") { MockEngine { respond(body, headers = headersOf(HttpHeaders.SetCookie, headers)) } }
            val error = assertFailsWith<GalleryRequestException> { repository.signIn("member", "test-only-private-password") }
            assertEquals(GalleryFailure.AUTHENTICATION, error.failure)
            assertFalse(error.toString().contains("test-only-private"))
            assertEquals(null, error.cause)
            assertContentEquals(original, vault.bytes)
            assertTrue(store.isSignedIn())
        }
    }

    @Test fun redirectsNeverReplayThePasswordOrContactAForeignOrInsecureHost() = runTest {
        for (destination in listOf("https://evil.example/login", "http://forums.e-hentai.org/index.php", "https://member:password@forums.e-hentai.org/", "https://forums.e-hentai.org:444/")) {
            var calls = 0
            val repository = GalleryAuthenticationRepository(PersistentCookieStorage(TestSessionVault()), "test") {
                MockEngine {
                    calls++
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, destination))
                }
            }
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { repository.signIn("member", "password") }.failure)
            assertEquals(1, calls)
        }
    }

    @Test fun aSuccessfulRedirectUsesGetAndOnlyTheScopedTransactionCookies() = runTest {
        var calls = 0
        val store = PersistentCookieStorage(TestSessionVault())
        val repository = GalleryAuthenticationRepository(store, "test") {
            MockEngine { request ->
                calls++
                if (calls == 1) {
                    respond("", HttpStatusCode.SeeOther, headersOf(HttpHeaders.Location to listOf("/index.php?act=idx"), HttpHeaders.SetCookie to identity))
                } else {
                    assertEquals(HttpMethod.Get, request.method)
                    assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("ipb_member_id=test-member"))
                    assertFalse(request.headers.toString().contains("test-only-password"))
                    respond(success)
                }
            }
        }
        repository.signIn("member", "test-only-password")
        assertEquals(2, calls)
        assertTrue(store.isSignedIn())
    }

    @Test fun logoutInvalidatesAnAlreadyRunningLoginAndBlocksLateIdentityCookies() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = PersistentCookieStorage(TestSessionVault())
        val repository = GalleryAuthenticationRepository(store, "test") {
            MockEngine {
                entered.complete(Unit)
                release.await()
                respond(success, headers = headersOf(HttpHeaders.SetCookie, identity))
            }
        }
        val pending = async { repository.signIn("member", "password") }
        entered.await()
        store.logout()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        for (name in listOf("ipb_member_id", "ipb_pass_hash")) store.addCookie(root, Cookie(name, "late", path = "/"))
        assertFalse(store.isSignedIn())
        GalleryAuthenticationRepository(store, "test") { MockEngine { respond(success, headers = headersOf(HttpHeaders.SetCookie, identity)) } }.signIn("member", "password")
        assertTrue(store.isSignedIn())
    }

    @Test fun failedSecureCommitPreservesThePriorSessionAndDoesNotReportSuccess() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        store.addCookie(root, Cookie("ipb_member_id", "old-member", path = "/"))
        store.addCookie(root, Cookie("ipb_pass_hash", "old-secret", path = "/"))
        val original = vault.bytes!!.copyOf()
        vault.failure = IllegalStateException("test-only-private-vault-path")
        val repository = GalleryAuthenticationRepository(store, "test") { MockEngine { respond(success, headers = headersOf(HttpHeaders.SetCookie, identity)) } }
        val error = assertFailsWith<GalleryRequestException> { repository.signIn("member", "password") }
        assertEquals(GalleryFailure.SESSION_STORAGE, error.failure)
        assertFalse(error.toString().contains("private"))
        assertContentEquals(original, vault.bytes)
        vault.failure = null
        assertEquals("old-member", store.get(root).single { it.name == "ipb_member_id" }.value)
    }

    @Test fun challengeRateServerAndOversizedResponsesRemainExplicit() = runTest {
        for ((status, reason) in listOf(403 to GalleryFailure.CHALLENGE, 429 to GalleryFailure.RATE_LIMIT, 503 to GalleryFailure.SERVER)) {
            val repository = GalleryAuthenticationRepository(PersistentCookieStorage(TestSessionVault()), "test") { MockEngine { respond("test-only-private-body", HttpStatusCode.fromValue(status)) } }
            assertEquals(reason, assertFailsWith<GalleryRequestException> { repository.signIn("member", "password") }.failure)
        }
        val repository = GalleryAuthenticationRepository(PersistentCookieStorage(TestSessionVault()), "test") { MockEngine { respond("x", headers = headersOf(HttpHeaders.ContentLength, (8 * 1024 * 1024 + 1).toString())) } }
        assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { repository.signIn("member", "password") }.failure)
    }

    @Test fun transactionCookiesKeepHostOnlyPathAndOriginBoundaries() = runTest {
        val jar = AuthenticationCookieJar()
        jar.addCookie(root, Cookie("host-only", "one", path = "/"))
        jar.addCookie(root, Cookie("scoped", "two", domain = ".e-hentai.org", path = "/g"))
        jar.addCookie(root, Cookie("foreign", "no", domain = ".org"))
        jar.addCookie(Url("http://e-hentai.org/"), Cookie("insecure", "no"))
        assertTrue(jar.get(Url("https://forums.e-hentai.org/")).isEmpty())
        assertEquals(listOf("host-only"), jar.get(Url("https://e-hentai.org/gallery")).map { it.name })
        assertEquals(setOf("host-only", "scoped"), jar.get(Url("https://e-hentai.org/g/1")).map { it.name }.toSet())
        assertTrue(jar.get(Url("https://exhentai.org/")).isEmpty())
    }
}
