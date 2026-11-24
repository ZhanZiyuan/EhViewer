package com.ehviewer.core.network

import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class WebViewCookieStorageTest {
    private val gallery = Url("https://e-hentai.org/")
    private val forum = Url("https://forums.e-hentai.org/index.php")

    @Test
    fun writesAndClearWaitForCompletionEvenWhenWriterIsCancelled() = runBlocking {
        val manager = FakeCookieManager()
        val storage = WebViewCookieStorage(manager)
        val write = launch(start = CoroutineStart.UNDISPATCHED) {
            storage.addCookie(gallery, Cookie("sample", "value", domain = gallery.host, path = "/"))
        }
        val writeCallback = manager.pending.await()
        assertFalse(write.isCompleted)
        manager.pending = CompletableDeferred()
        write.cancel()
        val clear = launch(start = CoroutineStart.UNDISPATCHED) { storage.removeAllCookies() }
        assertFalse(manager.pending.isCompleted)
        writeCallback(true)
        write.join()
        val clearCallback = manager.pending.await()
        assertFalse(clear.isCompleted)
        assertFalse(manager.flushed)
        clearCallback(false)
        clear.join()
        assertTrue(manager.flushed)
        assertTrue(write.isCancelled)
    }

    @Test
    fun stagedFailureRollsBackAndNeverClearsAnExistingIdentity() = runBlocking {
        val manager = FakeCookieManager()
        val storage = WebViewCookieStorage(manager)
        val staged = LoginCookies()
        staged.addCookie(forum, Cookie("ipb_member_id", "fixture", domain = gallery.host))
        staged.addCookie(forum, Cookie("ipb_pass_hash", "fixture", domain = gallery.host))
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<IllegalStateException> { storage.installLoginCookies(staged) }
        }
        for (success in listOf(true, false, true, true)) {
            val callback = manager.pending.await()
            manager.pending = CompletableDeferred()
            callback(success)
        }
        install.await()
        assertEquals(IdentityCookies.Missing, storage.identityCookies())
        manager.cookies["ipb_member_id"] = "existing"
        assertEquals(IdentityCookies.Incomplete, storage.identityCookies())
        assertFailsWith<IllegalStateException> { storage.installLoginCookies(staged) }
        assertFalse(manager.pending.isCompleted)
        assertTrue(manager.cookies.containsKey("ipb_member_id"))
        Unit
    }

    @Test
    fun cancelledCallbackWaitIgnoresLateCompletion() = runBlocking {
        lateinit var complete: (Boolean) -> Unit
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitCookieCallback { complete = it }
            error("Cancelled callback resumed the caller")
        }
        waiter.cancelAndJoin()
        complete(true)
    }

    private class FakeCookieManager : CookieManager {
        val cookies = mutableMapOf<String, String>()
        var pending = CompletableDeferred<(Boolean) -> Unit>()
        var flushed = false

        override fun getCookies(url: Url): Map<String, String> = cookies.toMap()

        override suspend fun setCookie(url: Url, cookie: Cookie) {
            check(awaitCookieCallback { pending.complete(it) })
            if (cookie.maxAge == 0) cookies.remove(cookie.name) else cookies[cookie.name] = cookie.value
        }

        override suspend fun removeAllCookies() {
            awaitCookieCallback { pending.complete(it) }
            cookies.clear()
        }

        override fun flush() {
            flushed = true
        }
    }
}
