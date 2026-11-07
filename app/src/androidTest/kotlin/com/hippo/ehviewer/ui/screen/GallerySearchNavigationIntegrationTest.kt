package com.hippo.ehviewer.ui.screen

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.ehviewer.shared.GalleryLibraryScreen
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.setAppLanguage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.decodeBase64
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual CMP actions, Ktor requests, Rust list/CBOR parsing and image decoding on each form factor. */
@RunWith(AndroidJUnit4::class)
class GallerySearchNavigationIntegrationTest {
    @Test fun scrollingAndCursorRecoveryRetainCommittedInputsAndCancelledSearchCannotReplaceNewResults() {
        System.loadLibrary("ehviewer")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val saved = runBlocking { Settings.snapshot() }
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        val originalDefault = Locale.getDefault()
        val location = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        val tip = Settings.appLinkVerifyTip
        val owned = Files.createTempDirectory(context.cacheDir.toPath(), "owned-search-navigation-").toFile()
        val gid = 7_000_000_000_000L + (UUID.randomUUID().mostSignificantBits and 0xff_ffff_ffffL)
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val nextRequests = AtomicInteger()
        val firstPageRequests = AtomicInteger()
        val rejectPrevious = AtomicBoolean(true)
        val blockedStarted = CompletableDeferred<Unit>()
        val releaseBlocked = CompletableDeferred<Unit>()
        val blockedFinished = CompletableDeferred<Unit>()
        val png = checkNotNull("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=".decodeBase64()).toByteArray()
        fun listing(titles: List<String>, previous: String? = null, next: String? = null, firstGid: Long = gid): String {
            val cards = titles.mapIndexed { index, title ->
                """<div><div class="glname"><a href="https://e-hentai.org/g/${firstGid + index}/0123456789/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/owned-$firstGid-$index.png" style="height:100px;width:80px"><div class="cn">Non-H</div><div class="ir" style="background-position:0px -1px"></div></div>"""
            }.joinToString("")
            val prevLink = previous?.let { """<a id="uprev" href="/?prev=$it">Previous</a>""" }.orEmpty()
            val nextLink = next?.let { """<a id="unext" href="/?next=$it">Next</a>""" }.orEmpty()
            return """<!DOCTYPE html><html><body><div class="itg">$cards</div>$prevLink$nextLink</body></html>"""
        }
        val engine = MockEngine { request ->
            if (request.url.host == "ehgt.org") {
                respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            } else {
                assertEquals("e-hentai.org", request.url.host)
                assertEquals("/", request.url.encodedPath)
                requests += request.url
                when {
                    request.url.parameters["f_search"] == null -> respond(listing(listOf("Owned initial homepage")))
                    request.url.parameters["f_search"] == "cancelled query" -> {
                        blockedStarted.complete(Unit)
                        try {
                            // Deliberately finish a stale transport callback after the replacement request.
                            withContext(NonCancellable) { releaseBlocked.await() }
                            respond(listing(listOf("Stale cancelled gallery")))
                        } finally {
                            blockedFinished.complete(Unit)
                        }
                    }
                    request.url.parameters["f_search"] == "replacement query" -> respond(listing(listOf("Replacement gallery")))
                    request.url.parameters["next"] != null -> {
                        assertEquals("200-300", request.url.parameters["next"])
                        when (nextRequests.incrementAndGet()) {
                            1 -> respond("Owned unavailable page", HttpStatusCode.ServiceUnavailable)
                            2 -> respond(listing(listOf("Second page gallery"), firstGid = gid + 40))
                            else -> error("Unexpected repeated append")
                        }
                    }
                    request.url.parameters["prev"] != null -> {
                        assertEquals("100-150", request.url.parameters["prev"])
                        if (rejectPrevious.getAndSet(false)) {
                            respond("Owned unavailable previous page", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond(listing(listOf("Previous page gallery"), firstGid = gid - 1))
                        }
                    }
                    firstPageRequests.incrementAndGet() == 1 -> respond(listing(listOf("Z owned gallery", "A owned gallery", "B owned gallery") + ('C'..'R').map { "$it owned filler" } + (20..40).map { "Owned filler $it" }, next = "200-300"))
                    else -> respond(listing(listOf("Reloaded committed page"), previous = "100-150", firstGid = gid + 100))
                }
            }
        }
        val client = HttpClient(engine) { followRedirects = false }
        val library = AndroidGalleryLibrary(false, client)
        fun click(text: String) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                try {
                    var control = device.findObject(By.text(text))
                    while (control != null && !control.isClickable) control = control.parent
                    if (control != null && control.isEnabled) {
                        control.click()
                        return
                    }
                } catch (_: StaleObjectException) {
                    // The action was not dispatched; reacquire the current enabled node.
                }
                SystemClock.sleep(25)
            }
            assertTrue("Missing enabled production action: $text", false)
        }
        fun visible(text: String) = assertTrue("Missing actual search result/control: $text", device.wait(Until.hasObject(By.text(text)), 15_000))
        fun query(text: String) {
            device.setSharedSearchText(text)
            visible(text)
        }
        fun scrollTo(text: String, direction: Direction) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                // A fitting refreshed page legitimately has no scrollable accessibility node.
                val scroll = device.findObject(By.scrollable(true))
                val grid = scroll?.visibleBounds ?: device.findObject(By.res("android:id/content"))?.visibleBounds
                val target = device.findObject(By.text(text))?.visibleBounds
                if (target != null && !target.isEmpty && grid != null && target.top >= grid.top + 8 && target.bottom <= grid.bottom - 8) return
                if (scroll != null && grid != null) {
                    val upper = grid.top + grid.height() / 4
                    val lower = grid.bottom - grid.height() / 8
                    device.swipe(grid.centerX(), if (direction == Direction.DOWN) lower else upper, grid.centerX(), if (direction == Direction.DOWN) upper else lower, 18)
                }
                SystemClock.sleep(25)
            }
            error("The scrolling list did not reach $text")
        }
        fun revealSearch() {
            if (device.findObject(By.clazz("android.widget.EditText"))?.visibleBounds?.isEmpty == false) return
            val grid = (device.findObject(By.scrollable(true)) ?: checkNotNull(device.findObject(By.res("android:id/content")))).visibleBounds
            device.swipe(grid.centerX(), grid.top + grid.height() / 3, grid.centerX(), grid.top + grid.height() / 2, 20)
        }
        fun returnToTopAndRefresh() {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (firstPageRequests.get() == 1 && SystemClock.uptimeMillis() < deadline) {
                device.findObject(By.scrollable(true))?.visibleBounds?.let { grid ->
                    // The final downward swipe may reach the top and perform the real pull
                    // in one gesture. Observe that request before sending another gesture.
                    device.swipe(grid.centerX(), grid.top + grid.height() / 4, grid.centerX(), grid.bottom - grid.height() / 8, 18)
                }
                SystemClock.sleep(25)
            }
            assertEquals("One real pull must reload the committed first page exactly once", 2, firstPageRequests.get())
        }
        fun retryCursor() {
            device.waitForIdle(5_000)
            val before = requests.size
            val deadline = SystemClock.uptimeMillis() + 15_000
            repeat(2) {
                if (requests.size == before && SystemClock.uptimeMillis() < deadline) {
                    val bounds = checkNotNull(device.findObject(By.text("Retry"))).visibleBounds
                    check(!bounds.isEmpty)
                    // A clipped parent's center can be outside the viewport. Use its
                    // actually visible label, and allow a second touch only if the
                    // first stopped a fling without dispatching any HTTP request.
                    device.click(bounds.centerX(), bounds.centerY())
                    val activation = minOf(deadline, SystemClock.uptimeMillis() + 500)
                    while (requests.size == before && SystemClock.uptimeMillis() < activation) SystemClock.sleep(25)
                }
            }
            runBlocking { withTimeout((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) { while (requests.size == before) delay(25) } }
            assertEquals("Retry must make exactly one real request", before + 1, requests.size)
        }
        downloadLocation = owned.toOkioPath()
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            it[launchPage] = 8
        }
        try {
            instrumentation.runOnMainSync { setAppLanguage("en") }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        val scope = rememberCoroutineScope()
                        val downloads = remember { AndroidGalleryDownloads(scope) }
                        GalleryLibraryScreen(library, library.readerState, AndroidGalleryAppearanceStore(), EhDB.sharedHistory, downloads, remember { androidGalleryLanguageSettings() }, browseOnEntry = true)
                    }
                }
                try {
                    assertTrue(device.wait(Until.hasObject(By.desc("Search galleries")), 15_000))
                    visible("Owned initial homepage")
                    assertEquals(1, requests.size)
                    assertTrue(requests.single().parameters.isEmpty())
                    device.openSharedDestination("Downloads")
                    device.openSharedDestination("Galleries")
                    visible("Owned initial homepage")
                    assertEquals("Returning to Galleries must keep the loaded homepage", 1, requests.size)
                    query("committed query")
                    requireNotNull(device.wait(Until.findObject(By.desc("Filters")), 15_000)).click()
                    visible("Minimum rating (2–5)")
                    checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = "3"
                    click("Apply and search")
                    visible("Z owned gallery")
                    visible("A owned gallery")
                    listOf("Previous", "Reload page", "Sort this page", "Next").forEach {
                        assertFalse("The original homepage has no fixed footer: $it", device.hasObject(By.text(it)))
                    }
                    fun cardOrder() = try {
                        device.findObjects(By.text(Pattern.compile("[ABZ] owned gallery|[C-R] owned filler"))).filter { !it.visibleBounds.isEmpty }.sortedWith(compareBy({ it.visibleBounds.top }, { it.visibleBounds.left })).map { it.text }.take(2)
                    } catch (_: StaleObjectException) {
                        emptyList()
                    }
                    runBlocking { withTimeout(5_000) { while (cardOrder() != listOf("Z owned gallery", "A owned gallery")) delay(25) } }
                    assertEquals(listOf("Z owned gallery", "A owned gallery"), cardOrder())
                    assertEquals(2, requests.size) // Preserve the actual service order without extra HTTP.
                    scrollTo("Retry", Direction.DOWN)
                    visible("Retry")
                    visible("Owned filler 40") // The preceding page remains usable on failure.
                    revealSearch()
                    query("unsubmitted draft")
                    scrollTo("Retry", Direction.DOWN)
                    retryCursor()
                    scrollTo("Second page gallery", Direction.DOWN)
                    visible("Second page gallery")
                    visible("Owned filler 40")
                    returnToTopAndRefresh()
                    visible("Reloaded committed page")
                    visible("Retry")
                    revealSearch()
                    query("another unsubmitted draft")
                    scrollTo("Retry", Direction.UP)
                    retryCursor()
                    visible("Previous page gallery")
                    query("cancelled query")
                    // A real search submission must leave editing mode on phone and tablet.
                    checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).click()
                    assertTrue("Search field did not actually receive focus", device.wait(Until.hasObject(By.clazz("android.widget.EditText").focused(true)), 5_000))
                    device.submitSharedSearch()
                    assertTrue("Submitted search retained editable focus", device.wait(Until.gone(By.clazz("android.widget.EditText").focused(true)), 5_000))
                    runBlocking { withTimeout(5_000) { blockedStarted.await() } }
                    query("replacement query")
                    device.submitSharedSearch()
                    visible("Replacement gallery")
                    releaseBlocked.complete(Unit)
                    runBlocking { withTimeout(5_000) { blockedFinished.await() } }
                    instrumentation.waitForIdleSync()
                    visible("Replacement gallery")
                    assertFalse(device.hasObject(By.text("Stale cancelled gallery")))
                    assertEquals(9, requests.size) // The initial homepage plus the original eight requests.
                    val actual = requests.toList().drop(1)
                    assertEquals(8, actual.size)
                    assertEquals(List(6) { "committed query" } + listOf("cancelled query", "replacement query"), actual.map { it.parameters["f_search"] })
                    assertEquals(listOf(null, "200-300", "200-300", null, null, null, null, null), actual.map { it.parameters["next"] })
                    assertEquals(listOf(null, null, null, null, "100-150", "100-150", null, null), actual.map { it.parameters["prev"] })
                    actual.forEach {
                        assertEquals("1", it.parameters["advsearch"])
                        assertEquals("3", it.parameters["f_srdd"])
                    }
                } catch (failure: Throwable) {
                    device.dumpWindowHierarchy(java.io.File(context.cacheDir, "search-navigation-failure.xml"))
                    device.takeScreenshot(java.io.File(context.cacheDir, "search-navigation-failure.png"))
                    throw failure
                }
            }
        } finally {
            releaseBlocked.complete(Unit)
            client.close()
            engine.close()
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
}
