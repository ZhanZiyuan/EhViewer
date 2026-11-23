package com.ehviewer.core.network

import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.parseClientCookiesHeader
import io.ktor.http.renderSetCookieHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCookieManager : CookieManager {
    private val manager = android.webkit.CookieManager.getInstance()
    override fun getCookies(url: Url) = manager.getCookie(url.toString())?.let { parseClientCookiesHeader(it) }
    override suspend fun setCookie(url: Url, cookie: Cookie) {
        val written = withContext(Dispatchers.Main.immediate) {
            awaitCookieCallback { done -> manager.setCookie(url.toString(), renderSetCookieHeader(cookie)) { done(it) } }
        }
        check(written) { "WebView rejected a cookie write" }
    }

    override suspend fun removeAllCookies() {
        withContext(Dispatchers.Main.immediate) {
            // A false result means there were no cookies to delete.
            awaitCookieCallback { done -> manager.removeAllCookies { done(it) } }
        }
    }

    override fun flush() = manager.flush()
}

actual fun getCookieManager(): CookieManager = AndroidCookieManager()
