package com.hippo.ehviewer.ui.screen

import android.content.Intent
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.domain.reader.ReaderBackground
import com.ehviewer.core.domain.reader.ReaderMode
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReadingDirection
import com.ehviewer.core.domain.reader.ReadingLayout
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.ehviewer.shared.settings.GalleryThemeMode
import com.ehviewer.shared.verifyAccountProduct
import com.ehviewer.shared.verifyGalleryProduct
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.updateWhenThemeChanges
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real packaged Rust/Bitmap, Room and DataStore execution; no production fixture backend. */
@RunWith(AndroidJUnit4::class)
class SharedGalleryIntegrationTest {
    @Test fun productionSharedGalleryDestinationRendersRotatesAndReturnsFromOriginalAccountSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val saved = runBlocking { Settings.snapshot() }
        val location = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        val linkTip = Settings.appLinkVerifyTip
        downloadLocation = File(context.cacheDir, "shared-gallery-smoke-downloads").apply { mkdirs() }.toOkioPath()
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false // Exercise the existing supported guest flow.
            it[updateIntervalDays] = 0
            it[launchPage] = 8 // The appended destination preserves original persisted indexes 0..7.
            // Exercise a real theme transition even when the retained device already stores Dark.
            // An idempotent Dark write has no change event to update AppCompat's process default.
            it[theme] = AppCompatDelegate.MODE_NIGHT_NO
            it[blackDarkTheme] = false
            it[harmonizeCategoryColor] = true
        }
        try {
            // A preference-only setup does not reset Android's retained application night mode.
            runBlocking { withContext(Dispatchers.Main) { updateWhenThemeChanges(AppCompatDelegate.MODE_NIGHT_NO) } }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                fun searchIsVisible(stage: String = "search") {
                    val visible = device.wait(Until.hasObject(By.desc("Search galleries")), 15_000)
                    if (!visible) {
                        val name = "shared-destination-$stage-${android.os.SystemClock.uptimeMillis()}"
                        device.dumpWindowHierarchy(File(context.cacheDir, "$name.xml"))
                        device.takeScreenshot(File(context.cacheDir, "$name.png"))
                    }
                    assertTrue("Common search did not render: $stage", visible)
                }
                searchIsVisible()
                fun themeApplied(mode: Int): Boolean {
                    var applied = false
                    scenario.onActivity { activity ->
                        val night = if (mode == AppCompatDelegate.MODE_NIGHT_YES) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        applied = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == night &&
                            AppCompatDelegate.getDefaultNightMode() == mode &&
                            activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                            activity.window.decorView.hasWindowFocus() && !activity.isChangingConfigurations
                    }
                    return applied
                }
                // The OS retains per-app night mode between launches. Settle the explicit Light
                // precondition before rotating, rather than racing the previous run's recreation.
                runBlocking { withTimeout(10_000) { while (!themeApplied(AppCompatDelegate.MODE_NIGHT_NO)) delay(25) } }
                fun rotate(landscape: Boolean) {
                    // Android 16 large screens ignore Activity orientation requests.
                    // Rotate the actual display instead of opting out of adaptive behavior.
                    if (landscape) device.setOrientationLandscape() else device.setOrientationPortrait()
                    runBlocking {
                        withTimeout(10_000) {
                            while (true) {
                                var reached = false
                                scenario.onActivity { reached = (it.window.decorView.width > it.window.decorView.height) == landscape }
                                if (reached) break
                                delay(50)
                            }
                        }
                    }
                    searchIsVisible()
                }
                rotate(true)
                rotate(false)
                requireNotNull(device.wait(Until.findObject(By.desc("Filters")), 5_000)).click()
                assertTrue("Common filters did not open", device.wait(Until.hasObject(By.text("Search filters")), 5_000))
                requireNotNull(device.wait(Until.findObject(By.text("Use site category preferences")), 5_000)).click()
                assertTrue("Category draft did not change", device.wait(Until.hasObject(By.text("Miscellaneous")), 5_000))
                requireNotNull(device.wait(Until.findObject(By.text("Cancel")), 5_000)).click()
                searchIsVisible()
                assertFalse("Cancelling filters changed the applied search", device.hasObject(By.desc("Filters applied")))
                device.openSharedDestination("Settings")
                fun capturePage(name: String) {
                    val output = requireNotNull(context.getExternalFilesDir("kmp-ui-settings")).apply { mkdirs() }
                    instrumentation.waitForIdleSync()
                    instrumentation.uiAutomation.executeShellCommand("screencap -p").use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { input -> File(output, "$name.png").outputStream().use(input::copyTo) }
                    }
                    device.dumpWindowHierarchy(File(output, "$name.xml"))
                }
                fun captureSettingsRoot(theme: String) {
                    listOf("EH", "Download", "Privacy", "Advanced", "About").forEach {
                        assertTrue("Missing original settings category: $it", device.wait(Until.hasObject(By.text(it)), 5_000))
                    }
                    val file = File(context.cacheDir, "settings-root-$theme-${android.os.SystemClock.uptimeMillis()}.png")
                    // Match the authoritative SDK screencap path rather than Bitmap re-encoding.
                    instrumentation.uiAutomation.executeShellCommand("screencap -p").use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { input -> file.outputStream().use(input::copyTo) }
                    }
                    check(file.length() > 0)
                    println("Actual shared settings root screenshot=$file")
                }
                captureSettingsRoot("light")
                requireNotNull(device.wait(Until.findObject(By.text("EH")), 5_000)).click()
                assertTrue("The original EH appearance preference did not open", device.wait(Until.hasObject(By.text("Dark theme")), 5_000))
                assertEquals(GalleryThemeMode.LIGHT, runBlocking { AndroidGalleryAppearanceStore().mode() })
                capturePage("eh-light")
                requireNotNull(device.wait(Until.findObject(By.text("Dark theme")), 5_000)).click()
                assertTrue(device.wait(Until.hasObject(By.text("Always on")), 5_000))
                capturePage("theme-menu-light")
                device.clickSharedControl("Always on")
                runBlocking {
                    withTimeout(10_000) {
                        // The existing observer applies the system theme after the preference write.
                        // Back must target the activity whose actual UI configuration has settled.
                        while (true) {
                            if (themeApplied(AppCompatDelegate.MODE_NIGHT_YES) && AndroidGalleryAppearanceStore().mode() == GalleryThemeMode.DARK) break
                            delay(25)
                        }
                    }
                    assertEquals(AppCompatDelegate.MODE_NIGHT_YES, Settings.snapshot()[Settings.theme])
                }
                instrumentation.waitForIdleSync()
                capturePage("eh-dark")
                device.clickSharedControl("Black dark theme")
                runBlocking { withTimeout(5_000) { while (!Settings.snapshot()[Settings.blackDarkTheme]) delay(25) } }
                device.clickSharedControl("Harmonize category color for Dynamic Color")
                runBlocking { withTimeout(5_000) { while (Settings.snapshot()[Settings.harmonizeCategoryColor]) delay(25) } }
                capturePage("eh-black-dark")
                assertTrue("Restored settings did not render before Back", device.wait(Until.hasObject(By.desc("Back").enabled(true)), 5_000))
                device.pressBack()
                captureSettingsRoot("dark")
                device.clickSharedControl("EH")
                runBlocking {
                    val reopened = AndroidGalleryAppearanceStore().preferences()
                    assertTrue(reopened.blackDarkTheme)
                    assertFalse(reopened.harmonizeCategoryColor)
                }
                device.clickSharedControl("Black dark theme")
                runBlocking { withTimeout(5_000) { while (Settings.snapshot()[Settings.blackDarkTheme]) delay(25) } }
                device.clickSharedControl("Harmonize category color for Dynamic Color")
                runBlocking { withTimeout(5_000) { while (!Settings.snapshot()[Settings.harmonizeCategoryColor]) delay(25) } }
                device.pressBack()
                device.pressBack()
                searchIsVisible("settings-return")
                device.openSharedDestination("Account")
                assertTrue("Original account settings did not open", device.wait(Until.hasObject(By.text(context.getString(com.ehviewer.core.i18n.R.string.settings_eh))), 10_000))
                device.pressBack()
                searchIsVisible("account-return")
            }
        } finally {
            device.unfreezeRotation()
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in saved) current[pref] = saved[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
                restore(theme)
                restore(blackDarkTheme)
                restore(harmonizeCategoryColor)
            }
            runBlocking { withContext(Dispatchers.Main) { updateWhenThemeChanges(saved[Settings.theme]) } }
            Settings.appLinkVerifyTip = linkTip
            with(Settings) {
                downloadScheme = location[0]
                downloadAuthority = location[1]
                downloadPath = location[2]
                downloadQuery = location[3]
                downloadFragment = location[4]
            }
        }
    }

    @Test fun sharedModelsAndImagesUseTheActualPackagedRustAndAndroidDecoder() {
        assertEquals(3, verifyGalleryProduct(RustGalleryDecoder))
        assertEquals(4, verifyAccountProduct(RustGalleryDecoder))
    }

    @Test fun sharedAndLegacyReadersUseTheSameActualRoomRowsAndAtomicPreferences() = runBlocking {
        // An explicitly owned test row, not a placeholder synthesized by production.
        val fixture = BaseGalleryInfo(gid = 8_000_000_000_001L, token = "0123456789", title = "KMP reader regression fixture", pages = 7)
        assertNull(EhDB.getGalleryInfo(fixture.gid))
        val before = Settings.snapshot()
        try {
            EhDB.putGalleryInfo(fixture.asEntity())
            EhDB.putReadProgress(fixture.gid, 4)
            val reader = AndroidReadingState { null }
            assertEquals(4, reader.position(fixture.gid)!!.index)
            assertNull(reader.position(fixture.gid)!!.pageToken)
            reader.savePosition(fixture.gid, 5, "1111111111")
            assertEquals(5, EhDB.getReadProgress(fixture.gid))
            assertEquals(5, AndroidReadingState { null }.position(fixture.gid)!!.index)

            reader.setPreferences(ReaderPreferences(ReadingLayout.PAGED, ReadingDirection.LEFT_TO_RIGHT))
            val continuous = ReaderPreferences(ReadingLayout.CONTINUOUS, ReadingDirection.LEFT_TO_RIGHT)
            reader.setPreferences(continuous)
            assertEquals(continuous.copy(mode = ReaderMode.WEBTOON), AndroidReadingState { null }.preferences())
            assertEquals(ReadingModeType.WEBTOON.prefValue, Settings.snapshot()[Settings.readingMode])
            Settings.withMutableSnapshot { it[Settings.readingMode] = ReadingModeType.CONTINUOUS_VERTICAL.prefValue }
            reader.setPreferences(continuous)
            assertEquals(ReadingModeType.CONTINUOUS_VERTICAL.prefValue, Settings.snapshot()[Settings.readingMode])
            val paged = ReaderPreferences(ReadingLayout.PAGED, ReadingDirection.RIGHT_TO_LEFT)
            reader.setPreferences(paged)
            assertEquals(paged.copy(mode = ReaderMode.RIGHT_TO_LEFT), AndroidReadingState { null }.preferences())
            assertEquals(ReadingModeType.RIGHT_TO_LEFT.prefValue, Settings.snapshot()[Settings.readingMode])
            // Changing general options must preserve original modes outside the two common layouts.
            for (mode in ReadingModeType.entries) {
                val shared = ReaderMode.valueOf(mode.name)
                reader.setPreferences(reader.preferences().withMode(shared))
                assertEquals("Shared selection did not preserve original mode code", mode.prefValue, Settings.snapshot()[Settings.readingMode])
                assertEquals(shared, AndroidReadingState { null }.preferences().selectedMode)
                for ((background, originalValue) in listOf(ReaderBackground.BLACK to 1, ReaderBackground.GRAY to 2, ReaderBackground.WHITE to 0, ReaderBackground.AUTOMATIC to 3)) {
                    val changed = reader.preferences().copy(background = background, showPageNumber = false, showSeekBar = false, doubleTapToZoom = false, cropBorders = true)
                    reader.setPreferences(changed)
                    assertEquals("General options replaced the original $mode", mode.prefValue, Settings.snapshot()[Settings.readingMode])
                    assertEquals(originalValue, Settings.snapshot()[Settings.readerTheme])
                    assertTrue(Settings.snapshot()[Settings.cropBorder])
                    assertEquals(changed, AndroidReadingState { null }.preferences())
                    assertEquals(5, EhDB.getReadProgress(fixture.gid))
                }
            }
            // A future native value must survive unrelated common preference edits.
            Settings.withMutableSnapshot { it[Settings.readingMode] = 999 }
            reader.setPreferences(reader.preferences().copy(showPageNumber = true))
            assertEquals(999, Settings.snapshot()[Settings.readingMode])
            assertEquals(5, EhDB.getReadProgress(fixture.gid))
        } finally {
            Settings.withMutableSnapshot { snapshot ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in before) snapshot[pref] = before[pref] else snapshot.remove(pref)
                }
                restore(Settings.readingMode)
                restore(Settings.continuousReadingRtl)
                restore(Settings.readerTheme)
                restore(Settings.showPageNumber)
                restore(Settings.showReaderSeekbar)
                restore(Settings.doubleTapToZoom)
                restore(Settings.cropBorder)
            }
            EhDB.deleteHistoryInfo(fixture.asEntity())
        }
        assertNull(EhDB.getGalleryInfo(fixture.gid))
        assertNull(EhDB.getReadingPosition(fixture.gid))
    }

    @Test fun missingGalleryCannotBeReplacedByAFabricatedProgressParent() = runBlocking {
        val gid = 8_000_000_000_002L
        assertNull(EhDB.getGalleryInfo(gid))
        val reader = AndroidReadingState { null }
        assertThrows(ReaderStateException::class.java) { runBlocking { reader.savePosition(gid, 1, "1111111111") } }
        assertNull(EhDB.getGalleryInfo(gid))
        assertNull(EhDB.getReadingPosition(gid))
    }
}
