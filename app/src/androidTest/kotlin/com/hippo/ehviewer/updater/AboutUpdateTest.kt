package com.hippo.ehviewer.updater

import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
import android.os.Build
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.ehviewer.core.i18n.R
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.MainActivity
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutUpdateTest {
    @Test fun dailyChannelWorksWithoutATokenPreference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val channelTitle = context.getString(R.string.use_ci_update_channel)
        fun preference(text: String): UiObject2 {
            device.waitForIdle()
            var control = requireNotNull(device.wait(Until.findObject(By.text(text)), 10_000)) { "Missing preference: $text" }
            while (!control.isClickable) control = requireNotNull(control.parent) { "Preference is not clickable: $text" }
            assertTrue("Disabled preference: $text", control.isEnabled)
            return control
        }
        fun clickPreference(text: String) {
            assertTrue("Click rejected: $text", preference(text).accessibilityNodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun toggleChannel(expected: Boolean) {
            // Observe the initial value before dispatching the action. Keep collection off the
            // instrumentation event loop, which UiAutomator blocks while waiting for UI events.
            runBlocking {
                withTimeout(5_000) {
                    val ready = CompletableDeferred<Unit>()
                    val changed = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        Settings.useCIUpdateChannel.valueFlow().onEach {
                            if (it == !expected) ready.complete(Unit)
                        }.first { it == expected }
                    }
                    ready.await()
                    assertEquals("Unexpected initial switch state", !expected, preference(channelTitle).isChecked)
                    clickPreference(channelTitle)
                    assertEquals(expected, changed.await())
                }
            }
            assertTrue("Switch did not render $expected", preference(channelTitle).wait(Until.checked(expected), 5_000))
        }
        val saved = runBlocking { Settings.snapshot() }
        val channel = saved[Settings.useCIUpdateChannel]
        val location = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        downloadLocation = File(context.cacheDir, "about-smoke-downloads").apply { mkdirs() }.toOkioPath()
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            // The shared search has its own Settings action; this test targets the original updater.
            it[launchPage] = 0
        }
        try {
            // MainActivity is singleTask. Give this test its own fresh activity stack without
            // clearing preferences, including when the platform rotation test ran first.
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                try {
                    assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 15_000))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Settings.appLinkVerifyTip) {
                        val domains = context.getSystemService(DomainVerificationManager::class.java)
                            .getDomainVerificationUserState(context.packageName)
                        if (domains?.hostToStateMap?.values?.any { it == DOMAIN_STATE_NONE } == true) {
                            val notice = By.text(context.getString(R.string.app_link_not_verified_title))
                            assertTrue("App-link notice did not appear", device.wait(Until.hasObject(notice), 15_000))
                            clickPreference(context.getString(android.R.string.cancel))
                            assertTrue("App-link notice did not close", device.wait(Until.gone(notice), 5_000))
                        }
                    }
                    val mainTitles = listOf(R.string.app_name, R.string.homepage, R.string.downloads)
                        .joinToString("|") { Pattern.quote(context.getString(it)) }
                    assertTrue("Main content is not ready", device.wait(Until.hasObject(By.pkg(context.packageName).text(Pattern.compile(mainTitles))), 15_000))
                    var drawerStart = 0
                    scenario.onActivity { activity ->
                        val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                        drawerStart = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).left + ViewConfiguration.get(activity).scaledTouchSlop
                    }
                    // The leftmost pixel belongs to the system Back gesture on gesture-navigation
                    // devices. Start beyond that region so the app receives the drawer drag.
                    assertTrue(drawerStart < device.displayWidth * 3 / 4)
                    assertTrue("Drawer gesture rejected", device.swipe(drawerStart, device.displayHeight / 2, device.displayWidth * 3 / 4, device.displayHeight / 2, 30))
                    if (!device.wait(Until.hasObject(By.text(context.getString(R.string.settings))), 3_000)) {
                        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(context.getString(R.string.settings)))
                    }
                    clickPreference(context.getString(R.string.settings))
                    clickPreference(context.getString(R.string.settings_about))
                    assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.settings_about_declaration))), 10_000))
                    if (!device.hasObject(By.text(channelTitle))) {
                        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(channelTitle))
                    }
                    assertFalse(device.hasObject(By.textContains("GitHub token")))
                    toggleChannel(!channel)
                    toggleChannel(channel)
                    if (!device.hasObject(By.text(context.getString(R.string.settings_about_check_for_updates)))) {
                        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(context.getString(R.string.settings_about_check_for_updates)))
                    }
                    assertNotNull(device.findObject(By.text(context.getString(R.string.settings_about_check_for_updates))))
                    assertFalse(device.hasObject(By.textContains("GitHub token")))
                } finally {
                    device.waitForIdle()
                    device.takeScreenshot(File(context.cacheDir, "daily-about.png"))
                    device.dumpWindowHierarchy(File(context.cacheDir, "daily-about.xml"))
                }
            }
        } finally {
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in saved) current[pref] = saved[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
                restore(useCIUpdateChannel)
            }
            with(Settings) {
                downloadScheme = location[0]
                downloadAuthority = location[1]
                downloadPath = location[2]
                downloadQuery = location[3]
                downloadFragment = location[4]
            }
        }
    }
}
