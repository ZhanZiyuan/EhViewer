package com.hippo.ehviewer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.setDefaultSettings
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
