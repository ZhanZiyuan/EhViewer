package com.hippo.ehviewer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.getCookieManager
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.login.confirmsForumSession
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.getLanguages
import com.hippo.ehviewer.util.setDefaultSettings
import io.ktor.http.Cookie
import io.ktor.http.Url
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun cronetTitleIsTranslatedForEveryPackagedLanguage() {
        val context = instrumentation.targetContext
        val expected = mapOf(
            "en" to "Enable Cronet",
            "de" to "Cronet aktivieren",
            "es" to "Activar Cronet",
            "fr" to "Activer Cronet",
            "ja" to "Cronet を有効にする",
            "ko" to "Cronet 사용",
            "nb-NO" to "Aktiver Cronet",
            "th" to "เปิดใช้ Cronet",
            "tr" to "Cronet’i etkinleştir",
            "zh-CN" to "启用 Cronet",
            "zh-HK" to "啟用 Cronet",
            "zh-TW" to "啟用 Cronet",
        )
        expected.forEach { (tag, title) ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            assertEquals(tag, title, context.createConfigurationContext(config).getString(R.string.settings_advanced_enable_cronet))
        }
    }

    @Test
    fun appLanguagePickerOffersOnlyCompleteTranslations() {
        val languages = with(instrumentation.targetContext) { getLanguages() }
        assertEquals(listOf("system", "en", "ja", "zh-CN", "zh-HK", "zh-TW"), languages.keys.toList())
    }

    @Test
    fun mainActivityLaunchesAcrossRotation() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun webViewUsesMobileUserAgentAndStoresCookies() = runBlocking {
        val url = Url("https://native-regression.invalid")
        val manager = getCookieManager()
        val completed = CountDownLatch(1)
        var result = ""
        var view: WebView? = null
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                setDefaultSettings()
                assertTrue(settings.javaScriptEnabled)
                assertTrue(settings.domStorageEnabled)
                assertTrue(settings.userAgentString.contains("Mobile"))
            }
        }
        try {
            manager.setCookie(url, Cookie("platform_fixture", "value", path = "/", secure = true))
            assertTrue(CookieManager.getInstance().getCookie(url.toString()).contains("platform_fixture=value"))
            instrumentation.runOnMainSync {
                view!!.evaluateJavascript("navigator.userAgent.includes('Mobile')") {
                    result = it
                    completed.countDown()
                }
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals("true", result)
        } finally {
            manager.setCookie(url, Cookie("platform_fixture", "", maxAge = 0, path = "/", secure = true))
            instrumentation.runOnMainSync {
                view?.destroy()
            }
        }
    }

    @Test
    fun forumWebViewRecognizesLoginAndRejectsUnrelatedPages() = runBlocking {
        val context = instrumentation.targetContext
        withContext(Dispatchers.Main) {
            val view = WebView(context).apply { setDefaultSettings() }
            try {
                suspend fun confirms(page: String, html: String): Boolean {
                    val finished = CompletableDeferred<Unit>()
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = WebResourceResponse("text/html", "UTF-8", html.byteInputStream())

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url == page) finished.complete(Unit)
                        }
                    }
                    view.loadUrl(page)
                    return withTimeout(10_000) {
                        finished.await()
                        view.confirmsForumSession(page)
                    }
                }

                val acknowledgement = "<p>You are now logged in as: Reader</p>"
                val header = """<div id="userlinks"><a href="/index.php?act=Login&amp;CODE=03">Log out</a></div>"""
                assertTrue("The form's success response should confirm the session", confirms(EhUrl.API_SIGN_IN, acknowledgement))
                assertTrue("The signed-in forum header should confirm the session", confirms(EhUrl.URL_FORUMS, header))
                assertFalse(confirms(EhUrl.URL_SIGN_IN, acknowledgement))
                assertFalse(confirms(EhUrl.URL_FORUMS, "<main>$acknowledgement</main>"))
                assertFalse(confirms("https://forum-fixture.invalid/", header))
                assertFalse(confirms(EhUrl.URL_FORUMS, header.replace("href=\"/", "href=\"https://forum-fixture.invalid/")))
            } finally {
                view.destroy()
            }
        }
    }

    @Test
    fun updaterAcceptsDefaultAndStandardNamesButRejectsMarshmallow() {
        val abi = android.os.Build.SUPPORTED_ABIS.first()
        assertTrue(AppConfig.matchVariant("EhViewer-1.15.0-$abi.apk"))
        assertTrue(AppConfig.matchVariant("EhViewer-1.15.0-default-$abi.apk"))
        assertTrue(AppConfig.matchVariant("$abi-commit"))
        assertFalse(AppConfig.matchVariant("EhViewer-1.15.0-marshmallow-$abi.apk"))
        assertFalse(AppConfig.matchVariant("marshmallow-$abi-commit"))
    }
}
