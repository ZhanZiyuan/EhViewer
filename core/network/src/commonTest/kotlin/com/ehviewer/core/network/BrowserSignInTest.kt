package com.ehviewer.core.network

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.session.BrowserSessionCookie
import com.ehviewer.core.network.session.GalleryAuthenticationRepository
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import com.ehviewer.core.network.session.isGalleryLoginNavigation
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.getTimeMillis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class BrowserSignInTest {
    private val root = Url("https://e-hentai.org/")
    private fun cookie(name: String, domain: String = ".e-hentai.org", path: String = "/", expires: Long? = null, value: String = "synthetic+%/=") = BrowserSessionCookie(name, value, domain, path, true, true, expires)
    private fun identity() = listOf(cookie("ipb_member_id"), cookie("ipb_pass_hash"))
    private fun repository(store: PersistentCookieStorage) = GalleryAuthenticationRepository(store, "test") { error("Browser import must not manufacture HTTP form requests") }

    @Test fun explicitHandoffPreservesHttpOnlyMetadataAndCommitsExactlyOnce() = runTest {
        val durable = TestSessionVault()
        var writes = 0
        val store = PersistentCookieStorage(object : SessionVault by durable {
            override suspend fun write(bytes: ByteArray) {
                writes++
                durable.write(bytes)
            }
        })
        val transaction = repository(store).beginBrowserSignIn()
        assertEquals(null, durable.bytes)
        val expiry = getTimeMillis() + 3_600_000
        transaction.complete(identity() + cookie("host-only", "e-hentai.org", "/private", expiry) + cookie("unrelated", ".example.org"))
        assertEquals(1, writes)
        assertTrue(PersistentCookieStorage(durable).isSignedIn())
        val loaded = store.get(Url("https://e-hentai.org/private/page"))
        assertEquals(3, loaded.size)
        assertTrue(loaded.all { it.secure && it.httpOnly && it.value == "synthetic+%/=" })
        assertEquals(expiry, loaded.first { it.name == "host-only" }.expires?.timestamp)
        assertFalse(store.get(Url("https://forums.e-hentai.org/private/page")).any { it.name == "host-only" })
        assertEquals(emptyList(), store.get(Url("https://example.org/")))
        assertFailsWith<CancellationException> { transaction.complete(identity()) }
        assertEquals(1, writes)
    }

    @Test fun incompleteExpiredOrWrongPathIdentityPreservesThePreviousSession() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        store.addCookie(root, Cookie("existing", "keep"))
        val original = vault.bytes!!.copyOf()
        for (incoming in listOf(identity().take(1), listOf(cookie("ipb_member_id"), cookie("ipb_pass_hash", expires = getTimeMillis() - 1)), identity().map { cookie(it.name, path = "/private") }, identity().map { cookie(it.name, domain = "forums.e-hentai.org") })) {
            val error = assertFailsWith<GalleryRequestException> { repository(store).beginBrowserSignIn().complete(incoming) }
            assertEquals(GalleryFailure.AUTHENTICATION, error.failure)
            assertContentEquals(original, vault.bytes)
        }
    }

    @Test fun logoutAndANewerSuccessfulLoginInvalidateAnAlreadyOpenedBrowser() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        val old = repository(store).beginBrowserSignIn()
        store.logout()
        assertFailsWith<CancellationException> { old.complete(identity()) }
        assertEquals(null, vault.bytes)
        val earlier = repository(store).beginBrowserSignIn()
        repository(store).beginBrowserSignIn().complete(identity())
        val current = vault.bytes!!.copyOf()
        assertFailsWith<CancellationException> { earlier.complete(identity()) }
        assertContentEquals(current, vault.bytes)
    }

    @Test fun invalidOrOversizedTransferIsRedactedAndNeverPartiallyPersisted() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        for (invalid in listOf(cookie("private\r\nname"), cookie("bad", value = "private\nvalue"), cookie("bad", value = "x; injected=y"), cookie("bad", path = "relative"), cookie("bad", domain = "..e-hentai.org"), cookie("bad", value = "x".repeat(16_385)))) {
            val error = assertFailsWith<GalleryRequestException> { repository(store).beginBrowserSignIn().complete(identity() + invalid) }
            assertEquals(GalleryFailure.INVALID_RESPONSE, error.failure)
            assertFalse(error.toString().contains("private"))
            assertFalse(invalid.toString().contains(invalid.value))
            assertEquals(null, error.cause)
            assertEquals(null, vault.bytes)
        }
        assertFailsWith<GalleryRequestException> { repository(store).beginBrowserSignIn().complete(List(129) { cookie("c$it") }) }
        assertEquals(null, vault.bytes)
    }

    @Test fun failedVaultCommitPreservesActualDurableStateAndCancellationPropagates() = runTest {
        val durable = TestSessionVault()
        var cancel = false
        val store = PersistentCookieStorage(object : SessionVault by durable {
            override suspend fun write(bytes: ByteArray) {
                if (cancel) throw CancellationException("test cancellation")
                error("test-only private vault detail")
            }
        })
        val transaction = repository(store).beginBrowserSignIn()
        assertEquals(GalleryFailure.SESSION_STORAGE, assertFailsWith<GalleryRequestException> { transaction.complete(identity()) }.failure)
        assertFalse(store.isSignedIn())
        assertEquals(null, durable.bytes)
        cancel = true
        assertFailsWith<CancellationException> { transaction.complete(identity()) }
        assertEquals(null, durable.bytes)
    }

    @Test fun openingOrAbandoningABrowserNeverWritesAndIncompleteHandoffCanRetry() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        repository(store).beginBrowserSignIn()
        assertEquals(null, vault.bytes)
        val transaction = repository(store).beginBrowserSignIn()
        assertFailsWith<GalleryRequestException> { transaction.complete(emptyList()) }
        assertEquals(null, vault.bytes)
        transaction.complete(identity())
        assertTrue(store.isSignedIn())
    }

    @Test fun navigationAllowsOfficialHttpsPagesAndRejectsOtherHostsPortsAndSchemes() {
        for (url in listOf("https://forums.e-hentai.org/index.php?act=Login&CODE=00", "https://e-hentai.org/", "https://exhentai.org/s/token/123-1#page")) assertTrue(isGalleryLoginNavigation(url))
        for (url in listOf("http://e-hentai.org/", "https://e-hentai.org.evil.example/", "https://member:secret@e-hentai.org/", "https://forums.e-hentai.org:444/", "file:///private/cookies", "javascript:alert(1)", "ehviewer://login", "not a URL")) assertFalse(isGalleryLoginNavigation(url))
    }
}
