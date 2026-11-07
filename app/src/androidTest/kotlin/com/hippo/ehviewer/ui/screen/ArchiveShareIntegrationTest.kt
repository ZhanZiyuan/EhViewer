package com.hippo.ehviewer.ui.screen

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ehviewer.core.data.FileArchiveLibrary
import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.network.archive.NativeArchiveBridge
import com.ehviewer.core.network.session.SessionVault
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.ehviewer.shared.ArchiveLibraryScreen
import com.ehviewer.shared.archiveProductFixture
import com.ehviewer.shared.reader.ReaderStateRepository
import com.ehviewer.shared.verifyArchiveProduct
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.MainActivity
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Common production UI and real private snapshots; only the external activity result is controlled. */
@RunWith(AndroidJUnit4::class)
class ArchiveShareIntegrationTest {
    @Test fun commonLibraryHandlesShareDismissalFailureCancellationCompletionAndAbsentCapability() {
        System.loadLibrary("ehviewer")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val root = File(context.cacheDir, "archive-share-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val source = File(root, "Owned comic.cbz").apply { writeBytes(archiveProductFixture()) }
        val archives = FileArchiveLibrary(File(root, "books").toOkioPath(), NativeArchiveBridge::open)
        val online = AndroidGalleryLibrary(false)
        val book = runBlocking { archives.importFile(source.absolutePath, "Owned sharing fixture") }
        val snapshots = CopyOnWriteArrayList<File>()
        val calls = AtomicInteger()
        val sharedCopy = File(root, "Shared result.cbz")
        val exportedCopy = File(root, "Exported result.cbz")
        val stateFile = File(root, "reader-state")
        val reading = ReaderStateRepository(object : SessionVault {
            override suspend fun read() = stateFile.takeIf { it.exists() }?.readBytes()
            override suspend fun write(bytes: ByteArray) = stateFile.writeBytes(bytes)
            override suspend fun clear() {
                stateFile.delete()
            }
        })
        val before = runBlocking { Settings.snapshot() }
        val tip = Settings.appLinkVerifyTip
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            it[launchPage] = 8
        }
        fun click(text: String) = runBlocking {
            withTimeout(15_000) {
                while (true) {
                    val target = device.findObject(By.text(text))
                    if (target?.parent?.isEnabled == true) {
                        target.click()
                        break
                    }
                    delay(25)
                }
            }
        }
        fun visible(text: String) = assertTrue(text, device.wait(Until.hasObject(By.text(text)), 15_000))
        fun privateCopiesClosed() = snapshots.forEach { assertFalse("Snapshot still exists: $it", it.exists()) }
        val share: suspend (ArchiveBook) -> Boolean = { selected ->
            assertEquals(book, selected)
            val snapshot = archives.prepareExport(selected)
            snapshots += File(snapshot.path)
            try {
                assertArrayEquals(archiveProductFixture(), File(snapshot.path).readBytes())
                when (calls.getAndIncrement()) {
                    0 -> false
                    1 -> throw IOException("Owned activity failure")
                    2 -> awaitCancellation()
                    3 -> {
                        snapshot.saveTo(sharedCopy.absolutePath)
                        true
                    }
                    else -> error("Unexpected extra share")
                }
            } finally {
                snapshot.close()
            }
        }
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                fun render(capability: (suspend (ArchiveBook) -> Boolean)?) {
                    scenario.onActivity { activity ->
                        activity.setContent {
                            MaterialTheme {
                                ArchiveLibraryScreen(archives, online, pickAndImport = { null }, pickAndImportDirectory = { null }, pickAndExport = { selected ->
                                    val snapshot = archives.prepareExport(selected)
                                    try {
                                        snapshot.saveTo(exportedCopy.absolutePath)
                                        true
                                    } finally {
                                        snapshot.close()
                                    }
                                }, readingState = { reading }, onBack = {}, shareComic = capability)
                            }
                        }
                    }
                }
                render(share)
                click("Share comic")
                visible("Sharing cancelled.")
                privateCopiesClosed()
                click("Share comic")
                visible("The system could not finish sharing. Existing comics have been kept; check the chosen app before trying again.")
                privateCopiesClosed()
                click("Share comic")
                runBlocking { withTimeout(15_000) { while (calls.get() != 3) delay(25) } }
                click("Cancel sharing")
                visible("Sharing cancelled. Check the chosen app before trying again.")
                privateCopiesClosed()
                click("Share comic")
                visible("Sharing completed.")
                privateCopiesClosed()
                assertArrayEquals(archiveProductFixture(), sharedCopy.readBytes())
                assertEquals(4, calls.get())
                click("Export comic")
                visible("Export saved.")
                assertArrayEquals(archiveProductFixture(), exportedCopy.readBytes())
                render(null)
                assertTrue(device.wait(Until.gone(By.text("Share comic")), 15_000))
                visible("Export comic")
                assertFalse(device.hasObject(By.text("Share comic")))
                assertEquals(listOf(book), runBlocking { archives.recent() })
                assertEquals(3, runBlocking { verifyArchiveProduct(archives.open(book)) })
                assertArrayEquals(archiveProductFixture(), source.readBytes())
            }
        } finally {
            runBlocking { archives.close() }
            assertEquals(0 to 0, NativeArchiveBridge.activeResources())
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in before) current[pref] = before[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
            }
            Settings.appLinkVerifyTip = tip
            check(root.deleteRecursively())
        }
    }
}
