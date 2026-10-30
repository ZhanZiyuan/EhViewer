package com.ehviewer.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Url

/** The platform supplies a real engine and its browser/secure cookie store. No logging plugin is installed. */
fun galleryHttpClient(engine: HttpClientEngine, cookies: CookiesStorage, userAgent: String): HttpClient {
    require(userAgent.isNotBlank() && userAgent.none { it == '\r' || it == '\n' })
    return HttpClient(engine) {
        install(HttpCookies) {
            storage = object : CookiesStorage by cookies {
                override suspend fun get(requestUrl: Url): List<Cookie> {
                    val stored = cookies.get(requestUrl)
                    // Preserve EhCookieStore's existing public-gallery content-warning preference.
                    return if (requestUrl.host == "e-hentai.org") stored.filterNot { it.name == "nw" } + Cookie("nw", "1") else stored
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
        }
        expectSuccess = false
        followRedirects = true
    }
}
