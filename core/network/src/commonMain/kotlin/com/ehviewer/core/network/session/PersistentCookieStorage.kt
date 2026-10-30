package com.ehviewer.core.network.session

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.fillDefaults
import io.ktor.client.plugins.cookies.matches
import io.ktor.http.Cookie
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Implementations must use an OS credential store or an explicitly selected encrypted backend. */
interface SessionVault {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    suspend fun clear()
}

/** No cookie, token, URL or vault exception text may become a public error message. */
class SessionStorageException : Exception("The secure session store is unavailable or invalid")

private val sessionJson = Json { ignoreUnknownKeys = true }

@Serializable
internal class StoredCookie(val cookie: Cookie, val originHost: String, val hostOnly: Boolean, val expiresAt: Long?)

@Serializable
private class CookieSnapshot(val schema: Int = 1, val cookies: List<StoredCookie>)

private val serviceHosts = setOf("e-hentai.org", "exhentai.org", "forums.e-hentai.org")
private val identityNames = setOf("ipb_member_id", "ipb_pass_hash", "igneous")
private const val MAX_COOKIES = 128
private const val MAX_SNAPSHOT_BYTES = 256 * 1024

private fun validDomain(domain: String?, hosts: Set<String>) = domain != null && domain.trimStart('.').lowercase() in hosts

private fun scopedCookie(url: Url, cookie: Cookie, time: Long, hosts: Set<String>): StoredCookie? {
    if (url.host !in hosts || url.protocol.name != "https" || cookie.name == "__utmp") return null
    val normalized = cookie.fillDefaults(url)
    val domain = normalized.domain?.trimStart('.')?.lowercase() ?: return null
    if (!validDomain(normalized.domain, hosts) || url.host != domain && !url.host.endsWith(".$domain")) return null
    require(cookie.name.length <= 256 && cookie.value.length <= 16_384) { "Cookie exceeds the supported size" }
    val expiry = cookie.maxAge?.let { time + it.toLong().coerceAtLeast(0) * 1000 } ?: cookie.expires?.timestamp
    return StoredCookie(normalized, url.host, cookie.domain == null, expiry)
}

private fun updatedCookies(old: List<StoredCookie>, incoming: StoredCookie, time: Long): List<StoredCookie> = old.filterNot {
    it.expiresAt?.let { expiry -> expiry <= time } == true ||
        it.cookie.name == incoming.cookie.name && it.cookie.domain == incoming.cookie.domain && it.cookie.path == incoming.cookie.path
}.let { retained ->
    if (incoming.expiresAt == null || incoming.expiresAt > time) retained + incoming else retained
}.also { require(it.size <= MAX_COOKIES) { "Session cookie limit exceeded" } }

private fun cookiesFor(records: List<StoredCookie>, url: Url, time: Long, hosts: Set<String>): List<Cookie> {
    if (url.host !in hosts || url.protocol.name != "https") return emptyList()
    val request = if (url.encodedPath.isEmpty()) URLBuilder(url).apply { encodedPath = "/" }.build() else url
    return records.filter {
        val path = it.cookie.path ?: "/"
        val requested = request.encodedPath.ifEmpty { "/" }
        val matchesPath = requested == path || requested.startsWith(path) && (path.endsWith('/') || requested.getOrNull(path.length) == '/')
        (it.expiresAt == null || it.expiresAt > time) && (!it.hostOnly || it.originHost == request.host) && it.cookie.matches(request) && matchesPath
    }.sortedByDescending { it.cookie.path?.length ?: 0 }.map { it.cookie }
}

/** A bounded HTTP transaction jar, never presented as persistent storage. Commit only after login succeeds. */
internal class AuthenticationCookieJar : CookiesStorage {
    private val mutex = Mutex()
    private var records = emptyList<StoredCookie>()

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = mutex.withLock {
        val time = getTimeMillis()
        val incoming = scopedCookie(requestUrl, cookie, time, serviceHosts) ?: return@withLock
        val updated = updatedCookies(records, incoming, time)
        require(sessionJson.encodeToString(CookieSnapshot(cookies = updated)).encodeToByteArray().size <= MAX_SNAPSHOT_BYTES)
        records = updated
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock { cookiesFor(records, requestUrl, getTimeMillis(), serviceHosts) }
    suspend fun snapshot(): List<StoredCookie> = mutex.withLock { records.toList() }
    override fun close() = Unit
}

/** Real persistent Ktor storage. Failed reads never erase data; memory changes only after a successful vault write. */
class PersistentCookieStorage(
    private val vault: SessionVault,
    private val allowedHosts: Set<String> = serviceHosts,
    private val now: () -> Long = ::getTimeMillis,
) : CookiesStorage {
    private val mutex = Mutex()
    private var loaded: List<StoredCookie>? = null
    private var generation = 0L
    private var authenticationBlocked = false

    private suspend fun <T> storage(action: suspend () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        loaded = null
        throw cancelled
    } catch (_: Exception) {
        loaded = null
        throw SessionStorageException()
    }

    private suspend fun load(): List<StoredCookie> {
        loaded?.let { return it }
        val bytes = storage { vault.read() }
        val records = if (bytes == null) {
            emptyList()
        } else {
            storage {
                require(bytes.size <= MAX_SNAPSHOT_BYTES)
                val snapshot = sessionJson.decodeFromString<CookieSnapshot>(bytes.decodeToString(throwOnInvalidSequence = true))
                require(snapshot.schema == 1 && snapshot.cookies.size <= MAX_COOKIES)
                require(snapshot.cookies.all { it.originHost in allowedHosts && validDomain(it.cookie.domain, allowedHosts) })
                snapshot.cookies
            }
        }
        loaded = records
        return records
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = mutex.withLock {
        if (authenticationBlocked && cookie.name in identityNames) return@withLock
        val time = now()
        val incoming = scopedCookie(requestUrl, cookie, time, allowedHosts) ?: return@withLock
        persist(updatedCookies(load(), incoming, time))
    }

    private suspend fun persist(records: List<StoredCookie>) {
        val bytes = sessionJson.encodeToString(CookieSnapshot(cookies = records)).encodeToByteArray()
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "Session snapshot limit exceeded" }
        storage { vault.write(bytes) }
        loaded = records
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        if (requestUrl.host !in allowedHosts || requestUrl.protocol.name != "https") return@withLock emptyList()
        cookiesFor(load(), requestUrl, now(), allowedHosts)
    }

    suspend fun logout() = mutex.withLock {
        generation++
        authenticationBlocked = true
        storage { vault.clear() }
        loaded = emptyList()
    }

    suspend fun isSignedIn(): Boolean = get(Url("https://e-hentai.org/")).let { cookies ->
        listOf("ipb_member_id", "ipb_pass_hash").all { name -> cookies.any { it.name == name && it.value.isNotEmpty() } }
    }

    internal suspend fun authenticationGeneration(): Long = mutex.withLock {
        load()
        generation
    }

    internal suspend fun commitAuthentication(expectedGeneration: Long, records: List<StoredCookie>) = mutex.withLock {
        if (generation != expectedGeneration) throw CancellationException("Session changed during sign-in")
        require(records.size <= MAX_COOKIES && records.all { it.originHost in allowedHosts && validDomain(it.cookie.domain, allowedHosts) })
        val active = records.filter { it.expiresAt == null || it.expiresAt > now() }
        val identity = cookiesFor(active, Url("https://e-hentai.org/"), now(), allowedHosts)
        require(listOf("ipb_member_id", "ipb_pass_hash").all { name -> identity.any { it.name == name && it.value.isNotEmpty() } }) { "Incomplete authenticated session" }
        persist(active)
        authenticationBlocked = false
        generation++
    }

    // The vault has no owned coroutine/thread; the caller owns its lifetime independently of HttpClient.
    override fun close() = Unit
}
