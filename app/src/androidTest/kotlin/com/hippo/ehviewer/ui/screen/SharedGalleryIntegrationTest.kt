package com.hippo.ehviewer.ui.screen

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReadingDirection
import com.ehviewer.core.domain.reader.ReadingLayout
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.ehviewer.shared.verifyAccountProduct
import com.ehviewer.shared.verifyGalleryProduct
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.MainActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
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
        }
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                fun searchIsVisible() = assertTrue("Common search did not render", device.wait(Until.hasObject(By.text("Search galleries")), 15_000))
                searchIsVisible()
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
                requireNotNull(device.wait(Until.findObject(By.text("Account")), 5_000)).click()
                assertTrue("Original account settings did not open", device.wait(Until.hasObject(By.text(context.getString(com.ehviewer.core.i18n.R.string.settings_eh))), 10_000))
                device.pressBack()
                searchIsVisible()
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
            }
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
        assertEquals(3, verifyAccountProduct(RustGalleryDecoder))
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
            assertEquals(continuous, AndroidReadingState { null }.preferences())
            assertEquals(ReadingModeType.WEBTOON.prefValue, Settings.snapshot()[Settings.readingMode])
            Settings.withMutableSnapshot { it[Settings.readingMode] = ReadingModeType.CONTINUOUS_VERTICAL.prefValue }
            reader.setPreferences(continuous)
            assertEquals(ReadingModeType.CONTINUOUS_VERTICAL.prefValue, Settings.snapshot()[Settings.readingMode])
            val paged = ReaderPreferences(ReadingLayout.PAGED, ReadingDirection.RIGHT_TO_LEFT)
            reader.setPreferences(paged)
            assertEquals(paged, AndroidReadingState { null }.preferences())
            assertEquals(ReadingModeType.RIGHT_TO_LEFT.prefValue, Settings.snapshot()[Settings.readingMode])
        } finally {
            Settings.withMutableSnapshot { snapshot ->
                if (Settings.readingMode in before) snapshot[Settings.readingMode] = before[Settings.readingMode] else snapshot.remove(Settings.readingMode)
                if (Settings.continuousReadingRtl in before) snapshot[Settings.continuousReadingRtl] = before[Settings.continuousReadingRtl] else snapshot.remove(Settings.continuousReadingRtl)
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
