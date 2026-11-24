package com.hippo.ehviewer.client

import com.ehviewer.core.network.IdentityCookies
import com.hippo.ehviewer.client.exception.ParseException
import com.hippo.ehviewer.ktor.RetryableConnectionFailure
import com.hippo.ehviewer.ktor.allowReadTransportRetry
import com.hippo.ehviewer.ktor.installSafeReadRetry
import com.hippo.ehviewer.ui.login.SessionOperation
import com.hippo.ehviewer.ui.login.isForumPage
import com.hippo.ehviewer.ui.login.reconcileSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAccessTest {
    private val exUrl = Url("https://exhentai.org/uconfig.php")

    @Test
    fun webSessionProofIsLimitedToTheHttpsForumOrigin() {
        assertTrue(isForumPage(EhUrl.API_SIGN_IN))
        assertTrue(isForumPage("https://forums.e-hentai.org:443/"))
        assertFalse(isForumPage("http://forums.e-hentai.org/"))
        assertFalse(isForumPage("https://forums.e-hentai.org.invalid/"))
        assertFalse(isForumPage("https://forums.e-hentai.org:444/"))
        assertFalse(isForumPage("https://another@example.invalid/"))
        assertFalse(isForumPage("https://another@forums.e-hentai.org/"))
    }

    @Test
    fun responseClassifiesChallengeRedirectDenialAndUnverifiedBody() {
        fun response(url: Url, status: Int, challenge: Boolean = false, type: ContentType? = null) = classifySiteHeaders(exUrl.host, url, status, challenge, type)
        assertEquals(SiteAccess.ChallengeRequired, response(exUrl, 403, challenge = true))
        assertEquals(SiteAccess.AuthenticationRequired, response(Url("https://e-hentai.org/bounce_login.php"), 200))
        assertEquals(SiteAccess.AuthenticationRequired, response(Url("https://forums.e-hentai.org/index.php?act=Login"), 200))
        assertEquals(SiteAccess.AccessDenied, response(exUrl, 403))
        assertEquals(SiteAccess.AccessDenied, response(exUrl, 200, type = ContentType.Image.GIF))
        assertEquals(SiteAccess.UnexpectedResponse, response(Url("https://e-hentai.org/uconfig.php"), 200))
        assertEquals(SiteAccess.Unverified, response(exUrl, 200))
        assertEquals(SiteAccess.TransientFailure, classifySiteFailure(UnknownHostException()))
        assertEquals(SiteAccess.UnexpectedResponse, classifySiteFailure(ParseException("fixture")))
        assertEquals(SiteAccess.UnexpectedResponse, classifySiteFailure(SSLHandshakeException("fixture")))
        assertEquals(SiteAccess.MissingIdentity, identityAccess(IdentityCookies.Missing))
        assertEquals(SiteAccess.MissingIdentity, identityAccess(IdentityCookies.Incomplete))
        assertEquals(SiteAccess.Unverified, identityAccess(IdentityCookies.Present))
        assertFalse(reconcileSession(true, IdentityCookies.Missing))
        assertFalse(reconcileSession(true, IdentityCookies.Incomplete))
        assertTrue(reconcileSession(true, IdentityCookies.Present))
    }

    @Test
    fun onlyOptedInGetsReplayBeforeReceivingHeaders() = runBlocking {
        suspend fun attempts(method: HttpMethod, optedIn: Boolean, failFirst: Int, cause: IOException): Pair<Int, String?> {
            var calls = 0
            val engine = MockEngine {
                if (++calls <= failFirst) throw cause
                respondOk("ok")
            }
            val client = HttpClient(engine) { installSafeReadRetry() }
            return try {
                val result = runCatching {
                    client.request(exUrl.toString()) {
                        this.method = method
                        if (optedIn) allowReadTransportRetry()
                    }.bodyAsText()
                }
                if (result.isFailure) assertEquals(cause::class, result.exceptionOrNull()!!::class)
                calls to result.getOrNull()
            } finally {
                client.close()
                engine.close()
            }
        }
        val closed = RetryableConnectionFailure(IOException())
        assertEquals(2 to "ok", attempts(HttpMethod.Get, true, 1, closed))
        assertEquals(2 to null, attempts(HttpMethod.Get, true, 3, closed))
        assertEquals(1 to null, attempts(HttpMethod.Post, true, 3, closed))
        assertEquals(1 to null, attempts(HttpMethod.Get, false, 3, closed))
        assertEquals(1 to null, attempts(HttpMethod.Get, true, 3, SSLHandshakeException("fixture")))
    }

    @Test
    fun secondLoginCannotEnterAndCancellationReleasesTheGuard() = runBlocking {
        val operation = SessionOperation()
        val busy = { error("busy") }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { operation.run(busy) { awaitCancellation() } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { operation.run(busy) { error("second login entered") } }
        }.also { assertEquals("busy", it.message) }
        first.cancelAndJoin()
        assertEquals("next", operation.run(busy) { "next" })
    }
}
