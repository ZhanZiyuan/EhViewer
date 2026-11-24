package com.ehviewer.core.network

import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

interface CookieManager {
    fun getCookies(url: Url): Map<String, String>?
    suspend fun setCookie(url: Url, cookie: Cookie)
    suspend fun removeAllCookies()
    fun flush()
}

internal suspend fun awaitCookieCallback(start: ((Boolean) -> Unit) -> Unit): Boolean = suspendCancellableCoroutine { continuation -> start { continuation.resume(it) } }

expect fun getCookieManager(): CookieManager
