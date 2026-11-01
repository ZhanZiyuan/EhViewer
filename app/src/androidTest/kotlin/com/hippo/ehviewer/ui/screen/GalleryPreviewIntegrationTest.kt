package com.hippo.ehviewer.ui.screen

import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ehviewer.core.data.RoomGalleryDownloadStorage
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.download.DownloadDatabase
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.download.GalleryDownloadQueue
import com.ehviewer.core.network.download.networkGalleryDownloadSource
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.ehviewer.shared.GalleryLibraryScreen
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.MainActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit offline UI acceptance: real Ktor/Rust/Bitmap/Room paths, with test-only HTTP responses. */
@RunWith(AndroidJUnit4::class)
class GalleryPreviewIntegrationTest {
    // Each JUnit instance owns its fixture; repeated runs retain unrelated data.
    private val gid = 8_000_000_000_000L + (UUID.randomUUID().mostSignificantBits and 0xff_ffff_ffffL)
    private val token = "0123456789"
    private val title = "Preview integration fixture"
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
    private val originalPng = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAYAAACZgbYnAAAADklEQVR4XmNg+A+CQAAAF/IF+w/LXpgAAAAASUVORK5CYII=")
    private val animatedGif = Base64.decode("R0lGODlhAgABAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQADAAAACwAAAAAAgABAAACAgQKACH5BAASAAAALAAAAAACAAEAgAAA/wAAAAICBAoAOw==")
    private val animatedWebp = Base64.decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAAAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAAEAAAAAAHgAAAJWUDhMDwAAAC8BAAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAAAALQAAABWUDhMDwAAAC8BAAAABxDR//4HIqL/AQA=")
    private val brokenWebp = animatedWebp.copyOf().apply { fill(0, 130, 139) }

    private fun detail(page: Int): String {
        val previews = (if (page == 0) 0..1 else 2..2).joinToString("") { index ->
            val pageToken = (index + 1).toString().repeat(10)
            """<a href="https://e-hentai.org/s/$pageToken/$gid-${index + 1}"><div title="Page ${index + 1}" style="width:80px;height:100px;background:url(https://ehgt.org/$index.png)"></div></a>"""
        }
        return """<!DOCTYPE html><html><body>
            <script>var gid = $gid; var token = "$token"; var apiuid = -1; var apikey = "0000000000"; var average_rating = 4.5;</script>
            <div id="gd5"><a onclick="one()">Archive</a><a onclick="two()">Torrent (0)</a></div>
            <div id="gd1"><div style="background:url(https://ehgt.org/0.png)"></div></div>
            <h1 id="gn">$title</h1><h1 id="gj">Fixture</h1><div id="gdc">Manga</div>
            <div id="gdd"><table><tr><td>Length:</td><td class="gdt2">3 pages</td></tr><tr><td>File Size:</td><td>1 MB</td></tr></table></div>
            <span id="rating_count">3</span><div id="fav"></div><div id="taglist"><table></table></div>
            <div id="cdiv"><div id="chd"><a id="postnewcomment">Comment</a></div></div>
            <div id="gdt">$previews</div></body></html>"""
    }

    @Test fun downloadsPauseResumeAndReopenOfflineUsingRealRustFilesRoomAndSharedReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val before = runBlocking { Settings.snapshot() }
        val location = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        val tip = Settings.appLinkVerifyTip
        val owned = File(context.cacheDir, "download-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val offline = AtomicBoolean(false)
        val blockSecond = AtomicBoolean(true)
        val secondStarted = CompletableDeferred<Unit>()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            check(!offline.get()) { "Offline reading must not request HTTP" }
            requests += request.url.encodedPath
            when {
                request.url.host == "ehgt.org" -> {
                    if (request.url.encodedPath == "/1.png" && blockSecond.get()) {
                        secondStarted.complete(Unit)
                        awaitCancellation()
                    }
                    respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }
                request.url.encodedPath == "/" -> respond("""<!DOCTYPE html><html><body><div class="itg"><div><div class="glname"><a href="https://e-hentai.org/g/$gid/$token/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/0.png" style="height:100px;width:80px"><div class="cn">Manga</div><div class="ir" style="background-position:0px -1px"></div></div></div></body></html>""")
                request.url.encodedPath.startsWith("/g/") -> respond(detail(request.url.parameters["p"]?.toInt() ?: 0))
                request.url.encodedPath.startsWith("/s/") -> {
                    val index = request.url.encodedPath.substringAfterLast('-').toInt() - 1
                    val previous = if (index > 0) """<a id="prev" href="https://e-hentai.org/s/${index.toString().repeat(10)}/$gid-$index">Previous</a>""" else ""
                    val next = if (index < 2) """<a id="next" href="https://e-hentai.org/s/${(index + 2).toString().repeat(10)}/$gid-${index + 2}">Next</a>""" else ""
                    respond("""<!DOCTYPE html><html><body><img id="img" src="https://ehgt.org/$index.png">$previous$next</body></html>""")
                }
                else -> error("Unexpected fixture request")
            }
        }
        val client = HttpClient(engine) { followRedirects = false }
        val library = AndroidGalleryLibrary(false, client)
        val appearance = AndroidGalleryAppearanceStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun openDatabase() = Room.databaseBuilder<DownloadDatabase>(context, File(owned, "queue.db").absolutePath).setDriver(AndroidSQLiteDriver()).build()
        var database = openDatabase()
        fun queue(database: DownloadDatabase) = GalleryDownloadQueue(RoomGalleryDownloadStorage(database, File(owned, "images").toOkioPath()), scope) { task -> networkGalleryDownloadSource(task, library.content) {} }
        var downloads = queue(database)
        fun click(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 15_000)).click()
        fun waitFor(state: GalleryDownloadState) = runBlocking { withTimeout(15_000) { while (downloads.tasks.value.singleOrNull()?.state != state) delay(25) } }
        fun attach(scenario: ActivityScenario<MainActivity>) = scenario.onActivity { activity ->
            activity.setContent { GalleryLibraryScreen(library, library.readerState, appearance, EhDB.sharedHistory, downloads) }
        }
        runBlocking { assertNull(EhDB.getGalleryInfo(gid)) }
        downloadLocation = File(owned, "legacy").apply { check(mkdirs()) }.toOkioPath()
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            it[launchPage] = 8
            it[readingMode] = ReadingModeType.LEFT_TO_RIGHT.prefValue
        }
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                attach(scenario)
                click("Search")
                click(title)
                click("Download gallery")
                runBlocking { withTimeout(15_000) { secondStarted.await() } }
                click("Pause")
                waitFor(GalleryDownloadState.PAUSED)
                assertEquals(1, downloads.tasks.value.single().finished)
                blockSecond.set(false)
                click("Resume")
                waitFor(GalleryDownloadState.COMPLETE)
                assertEquals(3, downloads.tasks.value.single().finished)
                assertEquals(1, requests.count { it == "/0.png" })
                offline.set(true)
                val beforeOffline = requests.size
                click("Read offline")
                assertTrue(device.wait(Until.hasObject(By.desc("Gallery page 1")), 15_000))
                click("Next page")
                assertTrue(device.wait(Until.hasObject(By.desc("Gallery page 2")), 15_000))
                runBlocking { withTimeout(10_000) { while (EhDB.getReadingPosition(gid)?.page != 1) delay(25) } }
                assertEquals(beforeOffline, requests.size)
            }
            runBlocking { downloads.closeAndJoin() }
            database.close()
            database = openDatabase()
            downloads = queue(database)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                attach(scenario)
                click("Downloads")
                click("Read offline")
                assertTrue(device.wait(Until.hasObject(By.text("2 / 3")), 15_000))
                assertTrue(device.wait(Until.hasObject(By.desc("Gallery page 2")), 15_000))
                assertEquals(3, downloads.tasks.value.single().finished)
            }
        } finally {
            runBlocking { downloads.closeAndJoin() }
            scope.cancel()
            database.close()
            client.close()
            engine.close()
            runBlocking {
                EhDB.deleteHistoryInfo(BaseGalleryInfo(gid = gid, token = token).asEntity())
                assertNull("Owned gallery fixture was not removed", EhDB.getGalleryInfo(gid))
                assertNull("Owned reading progress was not removed", EhDB.getReadingPosition(gid))
            }
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in before) current[pref] = before[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
                restore(readingMode)
                restore(continuousReadingRtl)
            }
            Settings.appLinkVerifyTip = tip
            with(Settings) {
                downloadScheme = location[0]
                downloadAuthority = location[1]
                downloadPath = location[2]
                downloadQuery = location[3]
                downloadFragment = location[4]
            }
            check(owned.deleteRecursively())
        }
    }

    @Test fun searchToRealThumbnailsToLastPreviewPageAndReaderPersistsOriginalRoomProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val before = runBlocking { Settings.snapshot() }
        val location = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        val tip = Settings.appLinkVerifyTip
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val originalBytes = AtomicReference(originalPng)
        val displayBytes = AtomicReference(png)
        val engine = MockEngine { request ->
            requests += request.url.encodedPath + "?" + request.url.parameters["p"]?.let { "p=$it" }.orEmpty()
            when {
                request.url.host == "ehgt.org" -> {
                    val bytes = when (request.url.encodedPath) {
                        "/original.png" -> originalBytes.get()
                        "/2.png" -> displayBytes.get()
                        else -> png
                    }
                    val type = if (bytes === animatedGif) {
                        "image/gif"
                    } else if (bytes === animatedWebp || bytes === brokenWebp) {
                        "image/webp"
                    } else {
                        "image/png"
                    }
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, type))
                }
                request.url.encodedPath == "/" -> respond("""<!DOCTYPE html><html><body><div class="itg"><div><div class="glname"><a href="https://e-hentai.org/g/$gid/$token/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/0.png" style="height:100px;width:80px"><div class="cn">Manga</div><div class="ir" style="background-position:0px -1px"></div></div></div></body></html>""")
                request.url.encodedPath.startsWith("/g/") -> respond(detail(request.url.parameters["p"]?.toInt() ?: 0))
                request.url.encodedPath == "/s/3333333333/$gid-3" -> respond("""<!DOCTYPE html><html><body><img id="img" src="https://ehgt.org/2.png"><a id="prev" href="https://e-hentai.org/s/2222222222/$gid-2">Previous</a><a href="https://e-hentai.org/fullimg/$gid/3333333333">Original</a></body></html>""")
                request.url.encodedPath == "/fullimg/$gid/3333333333" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://ehgt.org/original.png"))
                else -> error("Unexpected fixture request")
            }
        }
        val client = HttpClient(engine) { followRedirects = false }
        val library = AndroidGalleryLibrary(restricted = false, client = client)
        val appearance = AndroidGalleryAppearanceStore()
        runBlocking { assertNull(EhDB.getGalleryInfo(gid)) }
        downloadLocation = File(context.cacheDir, "preview-integration-downloads").apply { mkdirs() }.toOkioPath()
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            it[launchPage] = 8
            it[readingMode] = ReadingModeType.LEFT_TO_RIGHT.prefValue
        }
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        val scope = rememberCoroutineScope()
                        val downloads = remember { AndroidGalleryDownloads(scope) }
                        GalleryLibraryScreen(library, library.readerState, appearance, EhDB.sharedHistory, downloads)
                    }
                }
                fun click(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 15_000)).click()
                fun waitForImagePixels(original: Boolean) = runBlocking {
                    withTimeout(10_000) {
                        while (true) {
                            val screenshot = checkNotNull(device.takeScreenshot()) { "Actual reader screenshot is unavailable" }
                            val color = try {
                                try {
                                    device.findObject(By.desc("Gallery page 3"))?.visibleCenter?.let { screenshot.getPixel(it.x, it.y) }
                                } catch (_: StaleObjectException) {
                                    // Image replacement disposes the previous semantics node.
                                    // Retry within the same deadline and keep the pixel assertion.
                                    null
                                }
                            } finally {
                                screenshot.recycle()
                            }
                            if (color != null) {
                                val red = Color.red(color)
                                val green = Color.green(color)
                                val blue = Color.blue(color)
                                if (if (original) green > red + 30 && green > blue + 30 else green < 30 && red > 50 && blue > 50) break
                            }
                            delay(100)
                        }
                    }
                }
                fun waitForAnimatedPixels(label: String) = runBlocking {
                    try {
                        withTimeout(10_000) {
                            var previous = -1
                            var transitions = 0
                            while (transitions < 4) {
                                val screenshot = checkNotNull(device.takeScreenshot())
                                val color = try {
                                    try {
                                        device.findObject(By.desc("Gallery page 3"))?.visibleCenter?.let { screenshot.getPixel(it.x, it.y) }
                                    } catch (_: StaleObjectException) {
                                        null
                                    }
                                } finally {
                                    screenshot.recycle()
                                }
                                if (color != null) {
                                    val red = Color.red(color)
                                    val blue = Color.blue(color)
                                    val frame = when {
                                        red > 200 && blue < 30 -> 0
                                        blue > 200 && red < 30 -> 1
                                        else -> -1
                                    }
                                    if (frame >= 0 && frame != previous) {
                                        if (previous >= 0) transitions++
                                        previous = frame
                                    }
                                }
                                delay(40)
                            }
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        val image = checkNotNull(device.takeScreenshot())
                        try {
                            File(context.cacheDir, "animation-failure-$gid.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        } finally {
                            image.recycle()
                        }
                        device.dumpWindowHierarchy(File(context.cacheDir, "animation-failure-$gid.xml"))
                        throw AssertionError("$label did not render four actual frame transitions", timeout)
                    }
                }
                fun waitForPausedPoster(center: Point) = runBlocking {
                    try {
                        withTimeout(10_000) {
                            var stableSince = 0L
                            while (true) {
                                val screenshot = checkNotNull(device.takeScreenshot())
                                val color = try {
                                    screenshot.getPixel(center.x, center.y)
                                } finally {
                                    screenshot.recycle()
                                }
                                val now = SystemClock.uptimeMillis()
                                if (Color.red(color) > Color.blue(color) + 30 && Color.red(color) > Color.green(color) + 30) {
                                    if (stableSince == 0L) stableSince = now
                                    if (now - stableSince >= 600) break
                                } else {
                                    stableSince = 0L
                                }
                                delay(37)
                            }
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        val image = checkNotNull(device.takeScreenshot())
                        try {
                            File(context.cacheDir, "animation-failure-$gid.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        } finally {
                            image.recycle()
                        }
                        device.dumpWindowHierarchy(File(context.cacheDir, "animation-failure-$gid.xml"))
                        throw AssertionError("Paused activity did not expose the expected poster", timeout)
                    }
                }
                click("Search")
                click(title)
                click("Browse page previews")
                assertTrue("First real preview bitmap did not render", device.wait(Until.hasObject(By.desc("Preview of page 1")), 15_000))
                click("Next previews")
                val third = requireNotNull(device.wait(Until.findObject(By.desc("Preview of page 3")), 15_000))
                third.click()
                assertTrue("Selected reader page did not render", device.wait(Until.hasObject(By.text("3 / 3")), 15_000))
                click("Load original image")
                runBlocking {
                    withTimeout(10_000) { while (requests.none { it == "/original.png?" }) delay(25) }
                }
                assertTrue("Original image did not render", device.wait(Until.hasObject(By.desc("Gallery page 3")), 15_000))
                waitForImagePixels(original = true)
                assertTrue(requests.any { it == "/fullimg/$gid/3333333333?" })
                click("Use display image")
                assertTrue("Display image did not render again", device.wait(Until.hasObject(By.desc("Gallery page 3")), 15_000))
                waitForImagePixels(original = false)
                for (animation in listOf(animatedGif, animatedWebp)) {
                    originalBytes.set(animation)
                    click("Load original image")
                    waitForAnimatedPixels(if (animation === animatedGif) "GIF original" else "WebP original")
                    if (animation === animatedWebp) {
                        val center = checkNotNull(device.findObject(By.desc("Gallery page 3"))).visibleCenter
                        scenario.moveToState(Lifecycle.State.STARTED)
                        waitForPausedPoster(center)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        waitForAnimatedPixels("WebP after resume")
                    }
                    click("Use display image")
                    waitForImagePixels(original = false)
                }
                originalBytes.set(brokenWebp)
                click("Load original image")
                assertTrue("Broken later frame did not report a recoverable error", device.wait(Until.hasObject(By.text("Animation could not be played. Retry animation")), 10_000))
                click("Use display image")
                waitForImagePixels(original = false)
                displayBytes.set(animatedWebp)
                click("Continuous reading")
                assertTrue("Continuous reader moved away from the selected last page", device.wait(Until.hasObject(By.text("3 / 3")), 10_000))
                waitForAnimatedPixels("WebP continuous")
                displayBytes.set(png)
                click("Paged reading")
                waitForImagePixels(original = false)
                runBlocking {
                    withTimeout(10_000) { while (EhDB.getReadingPosition(gid)?.page != 2) delay(25) }
                    assertEquals(title, EhDB.getGalleryInfo(gid)?.title)
                }
                click("Back")
                assertTrue("Preview route was not retained", device.wait(Until.hasObject(By.desc("Preview of page 3")), 15_000))
                click("Back to details")
                assertTrue(device.wait(Until.hasObject(By.text(title)), 10_000))
                click("Back to search")
                click("History")
                assertTrue(device.wait(Until.hasObject(By.text("Reading history")), 10_000))
                click(title)
                click("Back to history")
                click("Remove from history")
                runBlocking {
                    withTimeout(10_000) { while (EhDB.sharedHistory.recent().any { it.gid == gid }) delay(25) }
                    assertEquals(2, EhDB.getReadingPosition(gid)?.page)
                }
                click("Back to search")
                assertTrue(device.wait(Until.hasObject(By.text("Search galleries")), 10_000))
                assertTrue(requests.any { it == "/g/$gid/$token/?p=1" })
                assertTrue(requests.any { it.startsWith("/s/3333333333/$gid-3") })
            }
        } finally {
            client.close()
            engine.close()
            runBlocking {
                EhDB.deleteHistoryInfo(BaseGalleryInfo(gid = gid, token = token).asEntity())
                assertNull("Owned gallery fixture was not removed", EhDB.getGalleryInfo(gid))
                assertNull("Owned reading progress was not removed", EhDB.getReadingPosition(gid))
            }
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in before) current[pref] = before[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
                restore(readingMode)
                restore(continuousReadingRtl)
            }
            Settings.appLinkVerifyTip = tip
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
