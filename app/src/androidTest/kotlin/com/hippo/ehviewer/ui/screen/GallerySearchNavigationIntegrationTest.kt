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
    @Test fun orderingAndCursorRecoveryRetainCommittedInputsAndCancelledSearchCannotReplaceNewResults() {
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
        val rejectPrevious = AtomicBoolean(true)
        val blockedStarted = CompletableDeferred<Unit>()
        val releaseBlocked = CompletableDeferred<Unit>()
        val blockedFinished = CompletableDeferred<Unit>()
        val png = checkNotNull("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=".decodeBase64()).toByteArray()
        fun listing(titles: List<String>, previous: String? = null, next: String? = null): String {
            val cards = titles.mapIndexed { index, title ->
                """<div><div class="glname"><a href="https://e-hentai.org/g/${gid + index}/0123456789/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/owned-$gid-$index.png" style="height:100px;width:80px"><div class="cn">Non-H</div><div class="ir" style="background-position:0px -1px"></div></div>"""
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
                            2 -> respond(listing(listOf("Second page gallery"), previous = "100-150"))
                            else -> respond(listing(listOf("Reloaded second page"), previous = "100-150"))
                        }
                    }
                    request.url.parameters["prev"] != null -> {
                        assertEquals("100-150", request.url.parameters["prev"])
                        if (rejectPrevious.getAndSet(false)) {
                            respond("Owned unavailable previous page", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond(listing(listOf("Previous page gallery"), next = "200-300"))
                        }
                    }
                    else -> respond(listing(listOf("Z owned gallery", "A owned gallery", "B owned gallery") + ('C'..'R').map { "$it owned filler" }, next = "200-300"))
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
            checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = text
            visible(text)
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
                        GalleryLibraryScreen(library, library.readerState, AndroidGalleryAppearanceStore(), EhDB.sharedHistory, downloads, remember { androidGalleryLanguageSettings() })
                    }
                }
                try {
                    visible("Search galleries")
                    query("committed query")
                    click("Filters")
                    visible("Minimum rating (2–5)")
                    checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = "3"
                    click("Apply and search")
                    visible("Z owned gallery")
                    visible("A owned gallery")
                    click("Sort this page")
                    click("Title")
                    assertTrue(device.wait(Until.gone(By.text("Title")), 5_000))
                    fun cardOrder() = try {
                        device.findObjects(By.text(Pattern.compile("[ABZ] owned gallery|[C-R] owned filler"))).filter { !it.visibleBounds.isEmpty }.sortedWith(compareBy({ it.visibleBounds.top }, { it.visibleBounds.left })).map { it.text }.take(2)
                    } catch (_: StaleObjectException) {
                        emptyList()
                    }
                    runBlocking { withTimeout(5_000) { while (cardOrder() != listOf("A owned gallery", "B owned gallery")) delay(25) } }
                    assertEquals(listOf("A owned gallery", "B owned gallery"), cardOrder())
                    click("Sort this page")
                    click("Service order")
                    runBlocking { withTimeout(5_000) { while (cardOrder() != listOf("Z owned gallery", "A owned gallery")) delay(25) } }
                    click("Sort this page")
                    click("Title")
                    runBlocking { withTimeout(5_000) { while (cardOrder() != listOf("A owned gallery", "B owned gallery")) delay(25) } }
                    assertEquals(1, requests.size) // Sorting the page never repeats its HTTP search.
                    click("Next")
                    visible("Retry")
                    visible("A owned gallery") // The preceding page remains usable on failure.
                    query("unsubmitted draft")
                    click("Retry")
                    visible("Second page gallery")
                    click("Reload page")
                    visible("Reloaded second page")
                    click("Previous")
                    visible("Retry")
                    query("another unsubmitted draft")
                    click("Retry")
                    visible("Previous page gallery")
                    query("cancelled query")
                    click("Search")
                    runBlocking { withTimeout(5_000) { blockedStarted.await() } }
                    query("replacement query")
                    click("Search")
                    visible("Replacement gallery")
                    releaseBlocked.complete(Unit)
                    runBlocking { withTimeout(5_000) { blockedFinished.await() } }
                    instrumentation.waitForIdleSync()
                    visible("Replacement gallery")
                    assertFalse(device.hasObject(By.text("Stale cancelled gallery")))
                    val actual = requests.toList()
                    assertEquals(8, actual.size)
                    assertEquals(List(6) { "committed query" } + listOf("cancelled query", "replacement query"), actual.map { it.parameters["f_search"] })
                    assertEquals(listOf(null, "200-300", "200-300", "200-300", null, null, null, null), actual.map { it.parameters["next"] })
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
