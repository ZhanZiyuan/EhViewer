package com.ehviewer.core.network

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object EhCookieStore : WebViewCookieStorage(getCookieManager())

enum class IdentityCookies {
    Missing,
    Incomplete,
    Present,
}

private const val KEY_IPB_MEMBER_ID = "ipb_member_id"
private const val KEY_IPB_PASS_HASH = "ipb_pass_hash"
private const val KEY_IGNEOUS = "igneous"
private const val KEY_UTMP_NAME = "__utmp"
private val authenticationCookies = setOf(KEY_IPB_MEMBER_ID, KEY_IPB_PASS_HASH, KEY_IGNEOUS)

open class WebViewCookieStorage internal constructor(private val manager: CookieManager) : CookiesStorage {
    private val mutations = Mutex()
    private val urlE = URLBuilder(URLProtocol.HTTPS, "e-hentai.org").build()
    private val urlEx = URLBuilder(URLProtocol.HTTPS, "exhentai.org").build()
    private val forums = URLBuilder(URLProtocol.HTTPS, "forums.e-hentai.org").build()
    private val tipsCookie = Cookie(name = "nw", value = "1")

    suspend fun removeAllCookies() = mutate {
        manager.removeAllCookies()
        check(identityCookies() == IdentityCookies.Missing) { "WebView did not clear the saved session" }
        manager.flush()
    }

    fun identityCookies(): IdentityCookies {
        val eCookies = manager.getCookies(urlE).orEmpty()
        if (!eCookies[KEY_IPB_MEMBER_ID].isNullOrBlank() && !eCookies[KEY_IPB_PASS_HASH].isNullOrBlank()) {
            return IdentityCookies.Present
        }
        val hasPartialIdentity = sequenceOf(eCookies, manager.getCookies(urlEx).orEmpty(), manager.getCookies(forums).orEmpty())
            .any { cookies -> authenticationCookies.any { !cookies[it].isNullOrBlank() } }
        return if (hasPartialIdentity) IdentityCookies.Incomplete else IdentityCookies.Missing
    }

    suspend fun clearIgneous() = mutate {
        manager.setCookie(urlEx, Cookie(KEY_IGNEOUS, "", maxAge = 0, domain = urlEx.host, path = "/"))
        manager.flush()
    }

    suspend fun installLoginCookies(staged: LoginCookies) = mutate {
        check(identityCookies() == IdentityCookies.Missing) { "An existing session must be kept or explicitly signed out" }
        val writes = staged.writes.filter { (url, cookie) ->
            url.protocol == URLProtocol.HTTPS &&
                (url.host == urlE.host || url.host == forums.host) && cookie.name != KEY_UTMP_NAME
        }
        val installedIdentity = mutableListOf<Pair<Url, Cookie>>()
        try {
            for ((url, cookie) in writes) {
                if (cookie.name in authenticationCookies) installedIdentity.add(url to cookie)
                manager.setCookie(url, cookie)
            }
            check(identityCookies() == IdentityCookies.Present) { "The login did not establish a session" }
            manager.flush()
        } catch (failure: Exception) {
            for ((url, cookie) in installedIdentity.asReversed()) {
                runCatching { manager.setCookie(url, cookie.copy(value = "", maxAge = 0, expires = null)) }
                    .onFailure { failure.addSuppressed(it) }
            }
            runCatching { manager.flush() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = mutations.withLock {
        // Once submitted, WebView cannot cancel a mutation. Keep the lock until its callback.
        withContext(NonCancellable) { block() }
    }

    fun getUserId() = manager.getCookies(urlE)?.get(KEY_IPB_MEMBER_ID)

    fun getHathPerks() = manager.getCookies(urlE)?.get("hath_perks")?.substringBefore('-')

    fun getIdentityCookies(): List<Pair<String, String?>> {
        val eCookies = manager.getCookies(urlE)
        val exCookies = manager.getCookies(urlEx)
        return listOf(
            KEY_IPB_MEMBER_ID to eCookies?.get(KEY_IPB_MEMBER_ID),
            KEY_IPB_PASS_HASH to eCookies?.get(KEY_IPB_PASS_HASH),
            KEY_IGNEOUS to exCookies?.get(KEY_IGNEOUS),
        )
    }

    fun isCloudflareBypassed() = manager.getCookies(urlE)?.containsKey("cf_clearance") == true

    fun flush() = manager.flush()

    // See https://github.com/Ehviewer-Overhauled/Ehviewer/issues/873
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if (cookie.name != KEY_UTMP_NAME) {
            mutate { manager.setCookie(requestUrl, cookie) }
        }
    }

    override fun close() = Unit

    override suspend fun get(requestUrl: Url): List<Cookie> {
        val cookies = manager.getCookies(requestUrl).orEmpty().map { Cookie(it.key, it.value) }
        return if (requestUrl.host == urlE.host) cookies + tipsCookie else cookies
    }
}

class LoginCookies : CookiesStorage {
    private val storage = AcceptAllCookiesStorage()
    internal val writes = mutableListOf<Pair<Url, Cookie>>()

    override suspend fun get(requestUrl: Url) = storage.get(requestUrl)

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        storage.addCookie(requestUrl, cookie)
        writes.add(requestUrl to cookie)
    }

    override fun close() = storage.close()
}
