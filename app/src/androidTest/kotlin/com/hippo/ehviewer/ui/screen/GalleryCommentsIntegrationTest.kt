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
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.ehviewer.shared.GalleryLibraryScreen
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.setAppLanguage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Ktor/Rust/Compose comments; only HTTP responses are controlled by this test. */
@RunWith(AndroidJUnit4::class)
class GalleryCommentsIntegrationTest {
    @Test fun originalTwoCommentPreviewLoadsAllThroughFailureRetryAndReturnsToDetails() {
        System.loadLibrary("ehviewer")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val before = runBlocking { Settings.snapshot() }
        val locales = AppCompatDelegate.getApplicationLocales()
        val originalLocale = Locale.getDefault()
        val tip = Settings.appLinkVerifyTip
        val gid = 8_000_000_000_000L + (UUID.randomUUID().mostSignificantBits and 0xff_ffff_ffffL)
        val token = "0123456789"
        val title = "Owned comment gallery"
        val allRequests = Collections.synchronizedList(mutableListOf<Url>())
        val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
        fun detail(all: Boolean): String {
            val comments = (1..if (all) 3 else 2).joinToString("") { index ->
                """<div class="c1"><div class="c3">Posted on 05 November 2026, 10:00 by: <a>Reader $index</a></div><div class="c6" id="comment_$index"><b>Owned comment $index</b><br>日本語 &amp; English</div><div class="c8">Last edited on 05 November 2026, 10:02</div></div>"""
            }
            val heading = if (all) """<a id="postnewcomment">Comment</a>""" else """<a href="?hc=1">All</a>"""
            return """<!DOCTYPE html><html><body>
            <script>var gid = $gid; var token = "$token"; var apiuid = -1; var apikey = "0000000000"; var average_rating = 4.5;</script>
            <div id="gd5"><a onclick="one()">Archive</a><a onclick="two()">Torrent (0)</a></div>
            <div id="gd1"><div style="background:url(https://ehgt.org/owned.png)"></div></div>
            <h1 id="gn">$title</h1><h1 id="gj">Owned</h1><div id="gdc">Non-H</div>
            <div id="gdd"><table><tr><td>Length:</td><td class="gdt2">1 pages</td></tr><tr><td>File Size:</td><td>1 MB</td></tr></table></div>
            <span id="rating_count">3</span><div id="fav"></div><div id="taglist"><table></table></div>
            <div id="cdiv"><div id="chd">$heading</div>$comments</div>
            <div id="gdt"><a href="https://e-hentai.org/s/1111111111/$gid-1"><div title="Page 1" style="width:80px;height:100px;background:url(https://ehgt.org/owned.png)"></div></a></div></body></html>"""
        }
        val engine = MockEngine { request ->
            when {
                request.url.host == "ehgt.org" -> respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                request.url.parameters["hc"] == "1" -> {
                    allRequests += request.url
                    if (allRequests.size == 1) respond("Owned failure", HttpStatusCode.ServiceUnavailable) else respond(detail(true))
                }
                request.url.encodedPath.startsWith("/g/") -> respond(detail(false))
                else -> respond("""<!DOCTYPE html><html><body><div class="itg"><div><div class="glname"><a href="https://e-hentai.org/g/$gid/$token/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/owned.png" style="height:100px;width:80px"><div class="cn">Non-H</div><div class="ir" style="background-position:0px -1px"></div></div></div></body></html>""")
            }
        }
        val client = HttpClient(engine) { followRedirects = false }
        val library = AndroidGalleryLibrary(false, client)
        fun visible(text: String) = assertTrue("Missing comments control: $text", device.wait(Until.hasObject(By.textContains(text)), 15_000))
        fun reveal(text: String) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                try {
                    val bounds = device.findObject(By.textContains(text))?.visibleBounds
                    if (bounds != null && !bounds.isEmpty) return
                } catch (_: StaleObjectException) { /* Reacquire within the same viewport budget. */ }
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
            }
            throw AssertionError("Comment control is outside the accessible viewport: $text")
        }
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            it[launchPage] = 8
            it[showComments] = false
        }
        try {
            instrumentation.runOnMainSync { setAppLanguage("en") }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val originalComment = com.hippo.ehviewer.ui.legacy.LinkifyTextView(activity) {}
                    android.util.Log.i("EhViewerCommentReference", "textSizePx=${originalComment.textSize}; scaledDensity=${activity.resources.displayMetrics.scaledDensity}; lineHeightPx=${originalComment.lineHeight}; includeFontPadding=${originalComment.includeFontPadding}; letterSpacing=${originalComment.letterSpacing}; fontMetrics=${originalComment.paint.fontMetrics}")
                    assertEquals("The baseline comment widget uses natural character spacing", 0f, originalComment.letterSpacing, 0.0001f)
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        for (weight in listOf(400, 500, 700)) {
                            val paint = android.text.TextPaint(originalComment.paint).apply { typeface = android.graphics.Typeface.create(originalComment.typeface, weight, false) }
                            val sample = "EhViewer Material 3 画廊詳細設定"
                            val glyphs = android.graphics.text.TextRunShaper.shapeTextRun(sample, 0, sample.length, 0, sample.length, 0f, 0f, false, paint)
                            assertTrue(glyphs.glyphCount() > 0)
                            (0 until glyphs.glyphCount()).map { glyphs.getFont(it) }.distinct().forEach { font ->
                                android.util.Log.i("EhViewerFontReference", "weight=$weight; file=${font.file?.absolutePath}; ttcIndex=${font.ttcIndex}; style=${font.style}; axes=${font.axes?.joinToString()}")
                            }
                        }
                    }
                    activity.setContent {
                        val scope = rememberCoroutineScope()
                        GalleryLibraryScreen(library, library.readerState, AndroidGalleryAppearanceStore(), EhDB.sharedHistory, remember { AndroidGalleryDownloads(scope) }, remember { androidGalleryLanguageSettings() }, listPreferences = remember { AndroidGalleryListPreferences() })
                    }
                }
                checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = "owned comments"
                device.submitSharedSearch()
                visible(title)
                device.clickSharedControl(title)
                assertTrue(device.wait(Until.hasObject(By.desc("Add to favorites")), 15_000))
                instrumentation.waitForIdleSync()
                assertFalse("The original disabled preference must hide comment previews", device.hasObject(By.textContains("Owned comment 1")))
                assertFalse(device.hasObject(By.text("More comments")))
                device.clickSharedControl("Back to search")
                device.openSharedDestination("Settings")
                device.clickSharedControl("EH")
                reveal("Show gallery comments")
                device.clickSharedControl("Show gallery comments")
                val savedDeadline = SystemClock.uptimeMillis() + 5_000
                while (SystemClock.uptimeMillis() < savedDeadline && !runBlocking { AndroidGalleryListPreferences().showComments() }) SystemClock.sleep(25)
                assertTrue(runBlocking { AndroidGalleryListPreferences().showComments() })
                device.pressBack()
                device.pressBack()
                device.clickSharedControl(title)
                reveal("Owned comment 1")
                visible("日本語 & English")
                assertFalse(device.hasObject(By.textContains("<b>")))
                reveal("More comments")
                device.clickSharedControl("More comments")
                visible("Gallery Comments")
                visible("The site is temporarily unavailable.")
                assertEquals(1, allRequests.size)
                device.clickSharedControl("Retry")
                reveal("Owned comment 3")
                visible("Last edited:")
                assertEquals(2, allRequests.size)
                assertTrue(allRequests.all { it.host == "e-hentai.org" && it.encodedPath == "/g/$gid/$token/" && it.parameters.names() == setOf("hc") })
                device.clickSharedControl("Refresh")
                visible("No more comments")
                assertEquals(3, allRequests.size)
                device.clickSharedControl("Back to details")
                visible("More comments")
                assertFalse(device.hasObject(By.textContains("Owned comment 3")))
                device.clickSharedControl("More comments")
                reveal("Owned comment 3")
                assertEquals("Reopening the owned route must retain loaded comments", 3, allRequests.size)
            }
        } finally {
            client.close()
            engine.close()
            runBlocking { EhDB.deleteHistoryInfo(BaseGalleryInfo(gid = gid, token = token).asEntity()) }
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in before) current[pref] = before[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
                restore(showComments)
            }
            Settings.appLinkVerifyTip = tip
            instrumentation.runOnMainSync { setAppLanguage(locales.toLanguageTags()) }
            Locale.setDefault(originalLocale)
        }
    }
}
