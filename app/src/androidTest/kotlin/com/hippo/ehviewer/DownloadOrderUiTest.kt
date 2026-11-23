package com.hippo.ehviewer

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.preferences.PrefDelegate
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadMove
import com.hippo.ehviewer.download.DownloadsFilterMode
import com.hippo.ehviewer.download.SortMode
import com.hippo.ehviewer.ui.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadOrderUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun galleryDragPersistsAndCardSelectionAndLabelDragStillWork() = runBlocking {
        val first = gallery(8_001_111_010L, "Order fixture first").apply { pages = 113 }
        val second = gallery(8_001_111_011L, "Order fixture second").apply { pages = 127 }
        val gids = listOf(first.gid, second.gid)
        val labelA = "Order fixture label A"
        val labelB = "Order fixture label B"
        val original = Settings.snapshot()
        val originalLinkTip = Settings.appLinkVerifyTip

        try {
            DownloadManager.deleteRangeDownload(gids.toLongArray())
            DownloadManager.deleteLabel(labelA)
            DownloadManager.deleteLabel(labelB)
            DownloadManager.addLabel(labelA)
            DownloadManager.addLabel(labelB)
            DownloadManager.restoreDownload(first, "order-fixture-first")
            DownloadManager.restoreDownload(second, "order-fixture-second")
            DownloadManager.changeLabel(gids.map { checkNotNull(DownloadManager.getDownloadInfo(it)) }, labelA)
            Settings.appLinkVerifyTip = true
            Settings.withMutableSnapshot {
                it[Settings.needSignIn] = false
                it[Settings.updateIntervalDays] = 0
                it[Settings.launchPage] = 6
                it[Settings.downloadSortMode] = SortMode.Custom.flag
                it[Settings.downloadFilterMode] = DownloadsFilterMode.CUSTOM.flag
                it[Settings.recentDownloadLabel] = ""
                it[Settings.gridView] = false
            }
            DownloadManager.sortDownloads(SortMode.Custom)
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                val handle = By.desc(context.getString(R.string.drag_to_reorder_download))
                assertTrue(device.wait(Until.hasObject(handle), 15_000))
                assertTrue(device.wait(Until.hasObject(By.text(checkNotNull(first.title))), 10_000))
                assertTrue(device.wait(Until.hasObject(By.text(checkNotNull(second.title))), 10_000))
                val handles = device.findObjects(handle).sortedBy { it.visibleCenter.y }
                assertEquals(2, handles.size)
                drag(handles.first(), handles.last())
                awaitCondition("Gallery order was not saved") {
                    EhDB.getAllDownloadInfo().filter { it.gid in gids }.sortedWith(SortMode.Custom.comparator()).map { it.gid } == gids
                }

                device.findObject(By.text(checkNotNull(first.title))).longClick()
                assertTrue("Card long press should enter selection mode", device.wait(Until.gone(handle), 5_000))
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(handle), 5_000))

                Settings.withMutableSnapshot { it[Settings.gridView] = true }
                assertTrue(device.wait(Until.gone(handle), 5_000))
                assertTrue("The grid should render the first gallery's page badge", device.wait(Until.hasObject(By.text("113")), 5_000))
                Settings.withMutableSnapshot { it[Settings.gridView] = false }
                assertTrue(device.wait(Until.hasObject(handle), 5_000))

                device.findObject(By.desc(context.getString(R.string.download_options))).click()
                val labels = device.wait(Until.findObject(By.text(context.getString(R.string.download_labels))), 5_000)
                assertNotNull(labels)
                labels.click()
                val labelHandle = By.desc(context.getString(R.string.reorder_download_label))
                assertTrue(device.wait(Until.hasObject(labelHandle), 5_000))
                val labelHandles = device.findObjects(labelHandle).sortedBy { it.visibleCenter.y }
                assertEquals(2, labelHandles.size)
                drag(labelHandles.first(), labelHandles.last())
                awaitCondition("Label order was not saved") {
                    EhDB.getAllDownloadLabelList().filter { it.label in listOf(labelA, labelB) }.map { it.label } == listOf(labelB, labelA)
                }
            }
        } finally {
            DownloadManager.deleteRangeDownload(gids.toLongArray())
            DownloadManager.deleteLabel(labelA)
            DownloadManager.deleteLabel(labelB)
            Settings.appLinkVerifyTip = originalLinkTip
            Settings.withMutableSnapshot { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in original) current[pref] = original[pref] else current.remove(pref)
                }
                restore(Settings.needSignIn)
                restore(Settings.updateIntervalDays)
                restore(Settings.launchPage)
                restore(Settings.downloadSortMode)
                restore(Settings.downloadFilterMode)
                restore(Settings.recentDownloadLabel)
                restore(Settings.gridView)
            }
            DownloadManager.sortDownloads(SortMode.from(original[Settings.downloadSortMode]))
        }
    }

    @Test
    fun concurrentRecoveryAndMoveKeepAStableOrderAfterImportAndDelete() = runBlocking {
        val rows = List(5) { gallery(8_001_111_020L + it, "Order storage fixture $it") }
        val gids = rows.map { it.gid }
        val originalSort = SortMode.from(Settings.downloadSortMode.value)
        try {
            rows.take(3).forEach { DownloadManager.restoreDownload(it, "storage-fixture-${it.gid}") }
            DownloadManager.sortDownloads(SortMode.Custom)
            coroutineScope {
                val move = async { DownloadManager.moveDownload(DownloadMove(gids[2], gids[0], before = false), label = "") }
                val restore = async { DownloadManager.restoreDownload(rows[3], "storage-fixture-recovery") }
                move.await()
                restore.await()
            }
            val imported = DownloadInfo(rows[4], null)
            DownloadManager.addDownload(listOf(imported, imported, DownloadInfo(rows[0], null)))
            DownloadManager.deleteDownload(gids[1])
            val saved = EhDB.getAllDownloadInfo().filter { it.gid in gids }.sortedWith(SortMode.Custom.comparator())
            assertEquals(listOf(gids[4], gids[3], gids[0], gids[2]), saved.map { it.gid })
            assertEquals(saved.size, saved.map { it.downloadInfo.customPosition }.toSet().size)
        } finally {
            DownloadManager.deleteRangeDownload(gids.toLongArray())
            DownloadManager.sortDownloads(originalSort)
        }
    }

    private suspend fun awaitCondition(message: String, condition: suspend () -> Boolean) {
        val completed = withTimeoutOrNull(10_000) {
            while (!condition()) delay(50)
            true
        }
        assertTrue(message, completed == true)
    }

    private fun drag(from: UiObject2, to: UiObject2) {
        val start = from.visibleCenter
        val end = to.visibleCenter
        assertTrue(device.drag(start.x, start.y, end.x, end.y + 6, 25))
    }

    private fun gallery(gid: Long, title: String) = GalleryEntity(gid, "fixture", title, null, "https://fixture.invalid/$gid.webp", 0, null, null, 0f, null, 0, null, -2)
}
