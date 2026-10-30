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
private class StoredCookie(val cookie: Cookie, val originHost: String, val hostOnly: Boolean, val expiresAt: Long?)

@Serializable
private class CookieSnapshot(val schema: Int = 1, val cookies: List<StoredCookie>)

/** Real persistent Ktor storage. Failed reads never erase data; memory changes only after a successful vault write. */
class PersistentCookieStorage(
    private val vault: SessionVault,
    private val allowedHosts: Set<String> = setOf("e-hentai.org", "exhentai.org", "forums.e-hentai.org"),
    private val now: () -> Long = ::getTimeMillis,
) : CookiesStorage {
    private val mutex = Mutex()
    private var loaded: List<StoredCookie>? = null

    private suspend fun <T> storage(action: suspend () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
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
                require(snapshot.cookies.all { it.originHost in allowedHosts && validDomain(it.cookie.domain) })
                snapshot.cookies
            }
        }
        loaded = records
        return records
    }

    private fun validDomain(domain: String?) = domain != null && domain.trimStart('.').lowercase() in allowedHosts

    private fun matchesPath(cookie: Cookie, request: Url): Boolean {
        val path = cookie.path ?: "/"
        val requested = request.encodedPath.ifEmpty { "/" }
        return requested == path || requested.startsWith(path) && (path.endsWith('/') || requested.getOrNull(path.length) == '/')
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = mutex.withLock {
        if (requestUrl.host !in allowedHosts || requestUrl.protocol.name != "https" || cookie.name == "__utmp") return@withLock
        val normalized = cookie.fillDefaults(requestUrl)
        val domain = normalized.domain?.trimStart('.')?.lowercase() ?: return@withLock
        if (!validDomain(normalized.domain) || requestUrl.host != domain && !requestUrl.host.endsWith(".$domain")) return@withLock
        require(cookie.name.length <= 256 && cookie.value.length <= 16_384) { "Cookie exceeds the supported size" }
        val time = now()
        val expiry = cookie.maxAge?.let { time + it.toLong().coerceAtLeast(0) * 1000 } ?: cookie.expires?.timestamp
        val old = load()
        val updated = old.filterNot {
            it.expiresAt?.let { expiry -> expiry <= time } == true ||
                it.cookie.name == normalized.name && it.cookie.domain == normalized.domain && it.cookie.path == normalized.path
        }.toMutableList()
        if (expiry == null || expiry > time) updated += StoredCookie(normalized, requestUrl.host, cookie.domain == null, expiry)
        require(updated.size <= MAX_COOKIES) { "Session cookie limit exceeded" }
        val bytes = sessionJson.encodeToString(CookieSnapshot(cookies = updated)).encodeToByteArray()
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "Session snapshot limit exceeded" }
        storage { vault.write(bytes) }
        loaded = updated
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        if (requestUrl.host !in allowedHosts || requestUrl.protocol.name != "https") return@withLock emptyList()
        val request = if (requestUrl.encodedPath.isEmpty()) URLBuilder(requestUrl).apply { encodedPath = "/" }.build() else requestUrl
        val time = now()
        load().filter {
            (it.expiresAt == null || it.expiresAt > time) && (!it.hostOnly || it.originHost == request.host) && it.cookie.matches(request) && matchesPath(it.cookie, request)
        }.sortedByDescending { it.cookie.path?.length ?: 0 }.map { it.cookie }
    }

    suspend fun logout() = mutex.withLock {
        storage { vault.clear() }
        loaded = emptyList()
    }

    suspend fun isSignedIn(): Boolean = get(Url("https://e-hentai.org/")).let { cookies ->
        listOf("ipb_member_id", "ipb_pass_hash").all { name -> cookies.any { it.name == name && it.value.isNotEmpty() } }
    }

    // The vault has no owned coroutine/thread; the caller owns its lifetime independently of HttpClient.
    override fun close() = Unit

    private companion object {
        const val MAX_COOKIES = 128
        const val MAX_SNAPSHOT_BYTES = 256 * 1024
    }
}
