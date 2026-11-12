package com.hippo.ehviewer.ktor

import androidx.webkit.WebViewCompat
import com.ehviewer.core.network.EhCookieStore
import com.hippo.ehviewer.Settings
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.userAgent
import io.ktor.util.AttributeKey
import io.ktor.util.appendIfNameAbsent
import java.net.ConnectException
import java.net.UnknownHostException
import splitties.init.appCtx

// It's safe to assume the WebView package will always be present as we require CookieManager anyway
private val WebViewVersion = WebViewCompat.getCurrentWebViewPackage(appCtx)!!.versionName!!.substringBefore('.')
val CHROME_MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$WebViewVersion.0.0.0 Mobile Safari/537.36"
private val CHROME_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$WebViewVersion.0.0.0 Safari/537.36"
private const val CHROME_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
private const val CHROME_ACCEPT_LANGUAGE = "en-US,en;q=0.9"
private val safeReadRetry = AttributeKey<Unit>("SafeReadTransportRetry")

internal fun HttpRequestBuilder.allowReadTransportRetry() {
    attributes.put(safeReadRetry, Unit)
}

fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureCommon(redirect: Boolean = true) = apply {
    install(HttpCookies) {
        storage = EhCookieStore
    }
    install(HttpTimeout) {
        reset()
        requestTimeoutMillis = 10_000
    }
    install(HttpRequestRetry) {
        noRetry()
        retryOnExceptionIf(maxRetries = 1) { request, failure ->
            request.method == HttpMethod.Get && request.attributes.contains(safeReadRetry) &&
                (failure is ConnectException || failure is UnknownHostException)
        }
        constantDelay(millis = 200, randomizationMs = 100, respectRetryAfterHeader = false)
    }
    install(UserAgent)
    defaultRequest {
        headers.appendIfNameAbsent(HttpHeaders.Accept, CHROME_ACCEPT)
        header(HttpHeaders.AcceptLanguage, CHROME_ACCEPT_LANGUAGE)
    }
    followRedirects = redirect
}

private val UserAgent = createClientPlugin("UserAgent") {
    onRequest { request, _ ->
        val userAgent = if (Settings.desktopSite.value) {
            CHROME_USER_AGENT
        } else {
            CHROME_MOBILE_USER_AGENT
        }
        request.userAgent(userAgent)
    }
}

fun HttpTimeoutConfig.reset() = apply {
    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    connectTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
}
