package com.hippo.ehviewer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.getLanguages
import com.hippo.ehviewer.util.setDefaultSettings
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun webViewUsesMobileUserAgentAndStoresCookies() {
        val saved = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var result = ""
        var view: WebView? = null
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                setDefaultSettings()
                assertTrue(settings.javaScriptEnabled)
                assertTrue(settings.domStorageEnabled)
                assertTrue(settings.userAgentString.contains("Mobile"))
                CookieManager.getInstance().setCookie("https://native-regression.invalid", "p3_test=value; Secure") { saved.countDown() }
            }
        }
        try {
            assertTrue(saved.await(10, TimeUnit.SECONDS))
            CookieManager.getInstance().flush()
            assertTrue(CookieManager.getInstance().getCookie("https://native-regression.invalid").contains("p3_test=value"))
            instrumentation.runOnMainSync {
                view!!.evaluateJavascript("navigator.userAgent.includes('Mobile')") {
                    result = it
                    completed.countDown()
                }
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals("true", result)
        } finally {
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie("https://native-regression.invalid", "p3_test=; Max-Age=0; Secure")
                view?.destroy()
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
