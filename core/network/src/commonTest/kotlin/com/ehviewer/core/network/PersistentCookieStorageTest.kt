package com.ehviewer.core.network

import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionStorageException
import com.ehviewer.core.network.session.SessionVault
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/** This in-memory vault is a test double only; production must provide a secure platform vault. */
internal class TestSessionVault : SessionVault {
    var bytes: ByteArray? = null
    var failure: Exception? = null
    override suspend fun read(): ByteArray? {
        failure?.let { throw it }
        return bytes?.copyOf()
    }
    override suspend fun write(bytes: ByteArray) {
        failure?.let { throw it }
        this.bytes = bytes.copyOf()
    }
    override suspend fun clear() {
        failure?.let { throw it }
        bytes = null
    }
}

class PersistentCookieStorageTest {
    private val root = Url("https://e-hentai.org/")

    @Test fun aRootUrlWithoutATrailingSlashStillReceivesRootCookies() = runTest {
        val store = PersistentCookieStorage(TestSessionVault())
        store.addCookie(root, Cookie("identity", "test-only", path = "/", secure = true))
        assertEquals("test-only", store.get(Url("https://e-hentai.org")).single().value)
    }

    @Test fun persistentIdentityRestoresAcrossInstancesAndLogoutClearsBothStores() = runTest {
        val vault = TestSessionVault()
        val first = PersistentCookieStorage(vault)
        first.addCookie(root, Cookie("ipb_member_id", "test-member", path = "/"))
        first.addCookie(root, Cookie("ipb_pass_hash", "test-only-secret", path = "/", httpOnly = true, secure = true))
        assertTrue(first.isSignedIn())
        val restored = PersistentCookieStorage(vault)
        assertTrue(restored.isSignedIn())
        assertTrue(restored.get(root).single { it.name == "ipb_pass_hash" }.httpOnly)
        restored.logout()
        assertFalse(restored.isSignedIn())
        assertEquals(null, vault.bytes)
        assertTrue(PersistentCookieStorage(vault).get(root).isEmpty())
    }

    @Test fun expiryAndZeroMaxAgeAreAppliedAfterRestart() = runTest {
        val vault = TestSessionVault()
        var time = 1000L
        val store = PersistentCookieStorage(vault, now = { time })
        store.addCookie(root, Cookie("short", "value", maxAge = 2, path = "/"))
        time = 2999
        assertEquals(1, store.get(root).size)
        time = 3000
        assertTrue(PersistentCookieStorage(vault, now = { time }).get(root).isEmpty())
        store.addCookie(root, Cookie("remove", "value", path = "/"))
        store.addCookie(root, Cookie("remove", "", maxAge = 0, path = "/"))
        assertTrue(store.get(root).isEmpty())
    }

    @Test fun hostOnlyCookiesNeverLeakToForumsAndDomainCookiesFollowPathBoundaries() = runTest {
        val store = PersistentCookieStorage(TestSessionVault())
        store.addCookie(root, Cookie("host-only", "one", path = "/"))
        store.addCookie(root, Cookie("domain", "two", domain = ".e-hentai.org", path = "/g"))
        assertEquals(setOf("domain"), store.get(Url("https://forums.e-hentai.org/g/1")).map { it.name }.toSet())
        assertEquals(setOf("host-only"), store.get(Url("https://e-hentai.org/gallery")).map { it.name }.toSet())
        assertTrue(store.get(Url("https://exhentai.org/g/1")).isEmpty())
    }

    @Test fun insecureForeignPublicSuffixAndDiscardedCookiesCannotEnterTheVault() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        for ((url, cookie) in listOf(
            Url("http://e-hentai.org/") to Cookie("one", "x"),
            Url("https://example.org/") to Cookie("two", "x"),
            root to Cookie("three", "x", domain = ".org"),
            root to Cookie("four", "x", domain = "exhentai.org"),
            root to Cookie("__utmp", "x"),
        )) {
            store.addCookie(url, cookie)
        }
        assertEquals(null, vault.bytes)
        assertTrue(store.get(root).isEmpty())
        assertTrue(store.get(Url("http://e-hentai.org/")).isEmpty())
    }

    @Test fun failedWriteAndLogoutPreserveThePriorSessionAndDoNotExposeVaultErrors() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        store.addCookie(root, Cookie("one", "old", path = "/"))
        val previous = vault.bytes!!.copyOf()
        vault.failure = IllegalStateException("test-only-secret")
        val error = assertFailsWith<SessionStorageException> { store.addCookie(root, Cookie("one", "new", path = "/")) }
        assertFalse(error.toString().contains("test-only-secret"))
        assertEquals(null, error.cause)
        assertFailsWith<SessionStorageException> { store.logout() }
        assertTrue(previous.contentEquals(vault.bytes))
        vault.failure = null
        assertEquals("old", store.get(root).single().value)
        assertEquals("old", PersistentCookieStorage(vault).get(root).single().value)
    }

    @Test fun invalidSnapshotIsPreservedAndCannotBecomeAnEmptySuccessfulSession() = runTest {
        val vault = TestSessionVault().apply { bytes = "invalid-test-only-secret".encodeToByteArray() }
        val store = PersistentCookieStorage(vault)
        val error = assertFailsWith<SessionStorageException> { store.get(root) }
        assertFalse(error.toString().contains("test-only-secret"))
        assertEquals("invalid-test-only-secret", vault.bytes!!.decodeToString())
        assertFailsWith<SessionStorageException> { store.addCookie(root, Cookie("one", "new")) }
    }

    @Test fun concurrentCookiesCannotOverwriteEachOthersPersistedState() = runTest {
        val vault = TestSessionVault()
        val store = PersistentCookieStorage(vault)
        (1..20).map { index -> async { store.addCookie(root, Cookie("cookie-$index", "value", path = "/")) } }.awaitAll()
        assertEquals(20, PersistentCookieStorage(vault).get(root).size)
    }

    @Test fun cancellationPropagatesWithoutChangingThePersistedSession() = runTest {
        val vault = TestSessionVault().apply { failure = CancellationException("test cancellation") }
        assertFailsWith<CancellationException> { PersistentCookieStorage(vault).get(root) }
        assertEquals(null, vault.bytes)
    }
}
