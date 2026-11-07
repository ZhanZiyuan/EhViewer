package com.hippo.ehviewer.ui.screen

import android.content.Intent
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
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
import java.io.File
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
                fun labelled(text: String) = assertTrue("Missing localized search field: $text", device.wait(Until.hasObject(By.desc(text)), 15_000))
                labelled("Search galleries")
                requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = "owned-language-query"
                var settingsLabel = "Settings"
                var advancedLabel = "Advanced"
                var navigationLabel = "Open navigation"
                var languageLabel = "App language"
                for (row in listOf(
                    listOf("ja", "日本語", "アプリの言語", "ギャラリーを検索", "設定", "ナビゲーションを開く", "その他の設定"),
                    listOf("zh-CN", "简体中文", "App 界面语言", "搜索图库", "设置", "打开导航", "高级"),
                    listOf("zh-TW", "繁體中文", "應用程式介面語言", "搜尋圖庫", "設定", "開啟導覽", "進階"),
                    listOf("en", "English", "App language", "Search galleries", "Settings", "Open navigation", "Advanced"),
                )) {
                    val (tag, choice, heading, search, settings) = row
                    device.openSharedDestination(settingsLabel, navigationLabel)
                    click(advancedLabel)
                    click(languageLabel)
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
                    visible(settings)
                    device.pressBack()
                    labelled(search)
                    visible("owned-language-query")
                    settingsLabel = settings
                    navigationLabel = row[5]
                    advancedLabel = row[6]
                    languageLabel = heading
                }
                device.openSharedDestination("Settings")
                click("EH")
                fun scrollTo(text: String, direction: Direction) {
                    fun <T> snapshot(read: () -> T): T? = try {
                        read()
                    } catch (_: StaleObjectException) {
                        null // A disposed node cannot establish visibility or a scroll boundary.
                    }
                    fun targetVisible() = snapshot { device.findObject(By.text(text))?.visibleBounds?.isEmpty == false } == true
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var current = direction
                    var reversed = false
                    var step = 0
                    while (step < 9 && SystemClock.uptimeMillis() < deadline) {
                        if (targetVisible()) return
                        val candidates = device.findObjects(By.scrollable(true)).mapNotNull { node -> snapshot { node.visibleBounds.takeUnless { it.isEmpty } } }
                        val bounds = candidates.maxByOrNull { it.width() }
                        if (bounds == null) {
                            SystemClock.sleep(25)
                            continue
                        }
                        println("Owned settings scroll target=$text step=$step bounds=$candidates")
                        fun anchors() = device.findObjects(By.clazz("android.widget.TextView"))
                            .mapNotNull { node ->
                                snapshot {
                                    val rectangle = node.visibleBounds
                                    if (!rectangle.isEmpty && bounds.contains(rectangle.centerX(), rectangle.centerY())) "${node.text}:${rectangle.top}" else null
                                }
                            }
                        val before = anchors()
                        // Address the actual viewport with a real OS swipe. UiObject2.scroll may
                        // report a boundary without moving this recreated Compose ScrollView.
                        val upper = bounds.top + bounds.height() / 4
                        val lower = bounds.bottom - bounds.height() / 4
                        val start = if (current == Direction.DOWN) lower else upper
                        val end = if (current == Direction.DOWN) upper else lower
                        check(device.swipe(bounds.centerX(), start, bounds.centerX(), end, 60))
                        step++
                        instrumentation.waitForIdleSync()
                        val after = anchors()
                        println("Owned settings scroll progress before=${before.take(2)} after=${after.take(2)}")
                        if (before.isNotEmpty() && before == after && !reversed) {
                            reversed = true
                            current = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
                        }
                    }
                    if (!targetVisible()) {
                        val name = "shared-language-scroll-${android.os.SystemClock.uptimeMillis()}"
                        device.dumpWindowHierarchy(File(context.cacheDir, "$name.xml"))
                        device.takeScreenshot(File(context.cacheDir, "$name.png"))
                        println("Owned settings scroll failure evidence=$name")
                    }
                    assertTrue("Missing localized production control in the actual viewport: $text", targetVisible())
                }
                fun savedTimeout(expected: Int?) = runBlocking {
                    withTimeout(5_000) {
                        while (network.requestTimeoutSeconds() != expected) delay(25)
                    }
                }
                fun savedDensity(expected: Int, label: String) {
                    val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                    // Inspect the actual selected popup item; its summary behind the popup has
                    // the same text. Keep the existing real preference and enabled assertions.
                    var opened = false
                    while (!opened && android.os.SystemClock.uptimeMillis() < deadline) {
                        try {
                            var row = device.findObject(By.text("Detail width"))
                            while (row != null && !row.isClickable) row = row.parent
                            if (row?.isEnabled == true) {
                                row.click()
                                opened = true
                            }
                        } catch (_: StaleObjectException) { /* Reacquire the actual preference row. */ }
                        if (!opened) android.os.SystemClock.sleep(25)
                    }
                    assertTrue("The original width menu must be enabled", opened)
                    runBlocking {
                        withTimeout((deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1)) {
                            while (Settings.snapshot()[Settings.detailSize] != expected || device.findObjects(By.text(label)).none { it.parent?.let { item -> item.isChecked && item.isEnabled } == true }) delay(25)
                        }
                    }
                    device.pressBack()
                }
                scrollTo("Detail width", Direction.DOWN)
                savedDensity(0, "Long")
                click("Detail width")
                click("Short")
                savedDensity(1, "Short")
                scenario.recreate()
                scrollTo("Detail width", Direction.DOWN)
                savedDensity(1, "Short")
                // The actual original preference rejects unknown saved values without replacing them.
                Settings.edit(blocking = true) { it[detailSize] = 7 }
                scenario.recreate()
                scrollTo("Gallery layout is unavailable.", Direction.DOWN)
                assertEquals(7, runBlocking { Settings.snapshot()[Settings.detailSize] })
                Settings.edit(blocking = true) { it[detailSize] = 0 }
                click("Retry")
                savedDensity(0, "Long")
                device.pressBack()
                click("Advanced")
                scrollTo("120 seconds", Direction.DOWN)
                click("120 seconds")
                savedTimeout(120)
                scenario.recreate()
                scrollTo("120 seconds", Direction.DOWN)
                savedTimeout(120)
                scrollTo("Platform default", Direction.UP)
                click("Platform default")
                savedTimeout(null)
                device.pressBack()
                click("Download")
                scrollTo("Open Android download storage settings", Direction.DOWN)
                click("Open Android download storage settings")
                visible(context.getString(R.string.settings_download_download_location))
                assertEquals(directory.toOkioPath(), downloadLocation)
                device.pressBack()
                scrollTo("Open Android download storage settings", Direction.DOWN)
                device.pressBack()
                visible("Settings")
                device.pressBack()
                labelled("Search galleries")
                device.openSharedDestination("Settings")
                click("Advanced")
                click("App language")
                click("System Language (Default)")
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
