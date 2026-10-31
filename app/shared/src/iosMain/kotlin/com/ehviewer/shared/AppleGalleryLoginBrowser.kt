@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.session.BrowserSessionCookie
import com.ehviewer.core.network.session.GalleryAuthenticationRepository
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import com.ehviewer.core.network.session.isGalleryLoginNavigation
import io.ktor.http.Url
import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/** Kotlin-only WKWebView, with an isolated nonpersistent cookie store and normal system TLS. */
class AppleGalleryLoginBrowser internal constructor(userAgent: String, private val dataStore: WKWebsiteDataStore) : GalleryLoginBrowser {
    constructor(userAgent: String) : this(userAgent, WKWebsiteDataStore.nonPersistentDataStore())
    private var loading by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var closed = false
    private val delegate = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val target = decidePolicyForNavigationAction.targetFrame
            val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
            val allowed = target != null && (isGalleryLoginNavigation(url) || !target.mainFrame && url.startsWith("https://challenges.cloudflare.com/"))
            decisionHandler(if (allowed) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }

        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            loading = false
            failed = false
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            if (withError.code != -999L) {
                loading = false
                failed = true
            }
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            if (withError.code != -999L) {
                loading = false
                failed = true
            }
        }
    }
    private val view = WKWebView(
        frame = CGRectZero.readValue(),
        configuration = WKWebViewConfiguration().apply {
            websiteDataStore = dataStore
            preferences.javaScriptCanOpenWindowsAutomatically = false
        },
    ).apply {
        customUserAgent = userAgent
        navigationDelegate = delegate
        allowsBackForwardNavigationGestures = true
    }

    @Composable
    override fun Content(modifier: Modifier) {
        Column(modifier) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) {
                Text("The official sign-in page could not be loaded.")
                TextButton(onClick = { load() }) { Text("Retry") }
            }
            UIKitView(factory = { view.also { load() } }, modifier = Modifier.fillMaxWidth().weight(1f), properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true))
        }
    }

    private fun load() {
        check(!closed)
        loading = true
        failed = false
        view.loadRequest(NSURLRequest(uRL = NSURL(string = "https://forums.e-hentai.org/index.php?act=Login&CODE=00")))
    }

    override suspend fun cookies(): List<BrowserSessionCookie> = withContext(Dispatchers.Main) {
        if (closed) throw GalleryRequestException(GalleryFailure.AUTHENTICATION)
        try {
            withTimeout(15_000) {
                suspendCancellableCoroutine { continuation ->
                    dataStore.httpCookieStore.getAllCookies { cookies ->
                        if (continuation.isActive) {
                            val scoped = cookies.orEmpty().filterIsInstance<NSHTTPCookie>().filter { it.domain.trimStart('.').lowercase() in setOf("e-hentai.org", "exhentai.org", "forums.e-hentai.org") }
                            if (scoped.size > 128) {
                                continuation.resumeWith(Result.failure(GalleryRequestException(GalleryFailure.INVALID_RESPONSE)))
                            } else {
                                continuation.resume(
                                    scoped.map {
                                        BrowserSessionCookie(it.name, it.value, it.domain, it.path, it.secure, it.HTTPOnly, it.expiresDate?.timeIntervalSince1970?.times(1000)?.toLong())
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw GalleryRequestException(GalleryFailure.OFFLINE)
        }
    }

    override fun close() {
        closed = true
        view.stopLoading()
        view.navigationDelegate = null
        view.removeFromSuperview()
    }
}

/** Explicit startup regression only: actual WebKit HttpOnly cookies → real injected OS vault. */
suspend fun verifyAppleBrowserCookieTransfer(vault: SessionVault): Int = withContext(Dispatchers.Main) {
    val dataStore = WKWebsiteDataStore.nonPersistentDataStore()
    val browser = AppleGalleryLoginBrowser("EhViewer/browser-regression", dataStore)
    val isolated = AppleGalleryLoginBrowser("EhViewer/browser-regression")
    try {
        check(!dataStore.persistent)
        val source = listOf("ipb_member_id", "ipb_pass_hash").map { name ->
            NSHTTPCookie.cookiesWithResponseHeaderFields(
                mapOf("Set-Cookie" to "$name=synthetic+%/=; Domain=.e-hentai.org; Path=/; Secure; HttpOnly; Max-Age=3600"),
                forURL = NSURL(string = "https://e-hentai.org/"),
            ).filterIsInstance<NSHTTPCookie>().single().also { check(it.HTTPOnly && it.secure) }
        }
        for (cookie in source) {
            suspendCancellableCoroutine { continuation ->
                dataStore.httpCookieStore.setCookie(cookie) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
        val transferred = browser.cookies()
        check(transferred.size == 2 && transferred.all { it.httpOnly && it.secure && it.value == "synthetic+%/=" && it.domain == ".e-hentai.org" && it.path == "/" && it.expiresAt != null })
        val store = PersistentCookieStorage(vault)
        GalleryAuthenticationRepository(store, "EhViewer/browser-regression").beginBrowserSignIn().complete(transferred)
        val restored = PersistentCookieStorage(vault)
        check(restored.isSignedIn() && restored.get(Url("https://e-hentai.org/")).all { it.httpOnly && it.secure && it.value == "synthetic+%/=" })
        check(isolated.cookies().isEmpty())
        4
    } finally {
        browser.close()
        isolated.close()
        vault.clear()
    }
}
