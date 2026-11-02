package com.hippo.ehviewer.ui.screen

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ehviewer.core.data.DataStoreNetworkPreferences
import com.ehviewer.core.i18n.R
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.setAppLanguage
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real AppCompat/OS preferences and production CMP navigation; never requests a gallery. */
@RunWith(AndroidJUnit4::class)
class GalleryLanguageIntegrationTest {
    @Test fun allSharedLanguagesSurviveActivityRecreationKeepTheQueryAndAllowSystemReset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val saved = runBlocking { Settings.snapshot() }
        val network = DataStoreNetworkPreferences()
        val originalTimeout = runBlocking { network.requestTimeoutSeconds() }
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        val originalDefault = Locale.getDefault()
        val location = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        val linkTip = Settings.appLinkVerifyTip
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "owned-language-").toFile()
        downloadLocation = directory.toOkioPath()
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            it[launchPage] = 8
            it[detailSize] = 0
        }
        try {
            instrumentation.runOnMainSync { setAppLanguage("en") }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                fun visible(text: String) = assertTrue("Missing localized production control: $text", device.wait(Until.hasObject(By.text(text)), 15_000))
                fun click(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 5_000)).click()
                visible("Search galleries")
                requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = "owned-language-query"
                var settingsLabel = "Settings"
                for ((tag, choice, heading, search, settings) in listOf(
                    listOf("ja", "日本語", "言語", "ギャラリーを検索", "設定"),
                    listOf("zh-CN", "简体中文", "语言", "搜索图库", "设置"),
                    listOf("zh-TW", "繁體中文", "語言", "搜尋圖庫", "設定"),
                    listOf("en", "English", "Language", "Search galleries", "Settings"),
                )) {
                    click(settingsLabel)
                    visible(choice)
                    click(choice)
                    visible(heading)
                    instrumentation.waitForIdleSync()
                    assertEquals(tag, AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag())
                    if (tag == "ja") {
                        scenario.recreate()
                        visible(heading)
                        assertEquals("ja", AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag())
                    }
                    device.pressBack()
                    visible(search)
                    visible("owned-language-query")
                    settingsLabel = settings
                }
                click("Settings")
                fun scrollTo(text: String, direction: Direction) {
                    repeat(6) {
                        if (device.hasObject(By.text(text))) return
                        requireNotNull(device.findObject(By.scrollable(true))).scroll(direction, 0.75f)
                        instrumentation.waitForIdleSync()
                    }
                    visible(text)
                }
                fun savedTimeout(expected: Int?) = runBlocking {
                    withTimeout(5_000) {
                        while (network.requestTimeoutSeconds() != expected) delay(25)
                    }
                }
                fun savedDensity(expected: Int, label: String) = runBlocking {
                    withTimeout(5_000) {
                        while (Settings.snapshot()[Settings.detailSize] != expected || device.findObject(By.text(label))?.parent?.let { it.isChecked && it.isEnabled } != true) delay(25)
                    }
                }
                scrollTo("Comfortable columns", Direction.DOWN)
                savedDensity(0, "Comfortable columns")
                click("Compact columns")
                savedDensity(1, "Compact columns")
                scenario.recreate()
                scrollTo("Compact columns", Direction.DOWN)
                savedDensity(1, "Compact columns")
                // The actual original preference rejects unknown saved values without replacing them.
                Settings.edit(blocking = true) { it[detailSize] = 7 }
                scenario.recreate()
                scrollTo("Gallery layout is unavailable.", Direction.DOWN)
                assertEquals(7, runBlocking { Settings.snapshot()[Settings.detailSize] })
                Settings.edit(blocking = true) { it[detailSize] = 0 }
                click("Retry")
                savedDensity(0, "Comfortable columns")
                scrollTo("120 seconds", Direction.DOWN)
                click("120 seconds")
                savedTimeout(120)
                scenario.recreate()
                scrollTo("120 seconds", Direction.DOWN)
                savedTimeout(120)
                scrollTo("Platform default", Direction.UP)
                click("Platform default")
                savedTimeout(null)
                scrollTo("Open Android download storage settings", Direction.DOWN)
                click("Open Android download storage settings")
                visible(context.getString(R.string.settings_download_download_location))
                assertEquals(directory.toOkioPath(), downloadLocation)
                device.pressBack()
                scrollTo("Open Android download storage settings", Direction.DOWN)
                device.pressBack()
                visible("Search galleries")
                click("Settings")
                click("System language")
                instrumentation.waitForIdleSync()
                assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
            }
        } finally {
            runBlocking { network.setRequestTimeoutSeconds(originalTimeout) }
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(originalLocales)
                Locale.setDefault(originalDefault)
            }
            instrumentation.waitForIdleSync()
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in saved) current[pref] = saved[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
                restore(detailSize)
            }
            Settings.appLinkVerifyTip = linkTip
            with(Settings) {
                downloadScheme = location[0]
                downloadAuthority = location[1]
                downloadPath = location[2]
                downloadQuery = location[3]
                downloadFragment = location[4]
            }
            check(directory.deleteRecursively())
        }
    }
}
