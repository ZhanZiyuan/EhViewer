package com.hippo.ehviewer.ui.screen

import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
import io.ktor.http.headersOf
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled HTTP/absent-handler failure; actual installed Ktor, Rust JNI, shared UI and clipboard. */
@RunWith(AndroidJUnit4::class)
class GalleryTorrentIntegrationTest {
    @Test fun queryWarningsRecoverIntoActualTorrentRetryCopyAndExplicitEmpty() {
        System.loadLibrary("ehviewer")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val before = runBlocking { Settings.snapshot() }
        val locales = AppCompatDelegate.getApplicationLocales()
        val originalLocale = Locale.getDefault()
        val tip = Settings.appLinkVerifyTip
        val gid = 8_100_000_000_000L + (UUID.randomUUID().mostSignificantBits and 0xff_ffff_ffffL)
        val token = "0123456789"
        val title = "Owned torrent gallery"
        val torrentName = "Alice & friends.zip"
        val limitQuery = "one two three four five six seven"
        val limitMessage = "You provided too many keywords. Use fewer keywords, or combine keywords with quotes."
        val expected = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Alice%20%26%20friends.zip&tr=https%3A%2F%2Fehtracker.org%2F$gid%2Fannounce"
        val calls = AtomicInteger()
        val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
        val detail = """<!DOCTYPE html><html><body>
            <script>var gid = $gid; var token = "$token"; var apiuid = -1; var apikey = "0000000000"; var average_rating = 4.5;</script>
            <div id="gd5"><a onclick="one()">Archive</a><a onclick="two()">Torrent (1)</a></div>
            <div id="gd1"><div style="background:url(https://ehgt.org/owned.png)"></div></div>
            <h1 id="gn">$title</h1><h1 id="gj">Owned</h1><div id="gdc">Non-H</div>
            <div id="gdd"><table><tr><td>Length:</td><td class="gdt2">1 pages</td></tr><tr><td>File Size:</td><td>1 MB</td></tr></table></div>
            <span id="rating_count">3</span><div id="fav"></div><div id="taglist"><table></table></div>
            <div id="cdiv"><div id="chd"><a id="postnewcomment">Comment</a></div></div>
            <div id="gdt"><a href="https://e-hentai.org/s/1111111111/$gid-1"><div title="Page 1" style="width:80px;height:100px;background:url(https://ehgt.org/owned.png)"></div></a></div></body></html>"""
        val torrents = """<!DOCTYPE html><html><body><div id="torrentinfo">
            <table><tr><td><span style="color:red">2026-11-05 12:34</span></td><td><span>Size:</span> 63.14 MiB</td>
            <td><span>Seeds:</span> 12</td><td><span>Peers:</span> 3</td><td><span>Downloads:</span> 42</td><td><span>Uploader:</span> Reader</td>
            <td><a onclick="document.location='https://ehtracker.org/get/1/0123456789abcdef0123456789abcdef01234567.torrent'">Alice &amp; friends.zip</a></td></tr></table>
            <table><tr><td>Expunged</td></tr></table></div></body></html>"""
        val engine = MockEngine { request ->
            when {
                request.url.host == "ehgt.org" -> respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                request.url.encodedPath == "/gallerytorrents.php" -> {
                    assertEquals(gid.toString(), request.url.parameters["gid"])
                    assertEquals(token, request.url.parameters["t"])
                    assertEquals("https://e-hentai.org/g/$gid/$token/", request.headers[HttpHeaders.Referrer])
                    when (calls.incrementAndGet()) {
                        1 -> respond("Owned failure", HttpStatusCode.ServiceUnavailable)
                        2, 3 -> respond(torrents)
                        else -> respond("""<!DOCTYPE html><html><div id="torrentinfo">There are no torrents for this gallery.</div></html>""")
                    }
                }
                request.url.encodedPath.startsWith("/g/") -> respond(detail)
                request.url.parameters["f_search"] == limitQuery -> respond("""<!DOCTYPE html><html><body><div class="searchwarn"><p>$limitMessage</p></div></body></html>""")
                else -> respond("""<!DOCTYPE html><html><body><div class="itg"><div><div class="glname"><a href="https://e-hentai.org/g/$gid/$token/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/owned.png" style="height:100px;width:80px"><div class="cn">Non-H</div><div class="ir" style="background-position:0px -1px"></div></div></div></body></html>""")
            }
        }
        val client = HttpClient(engine) { followRedirects = false }
        val library = AndroidGalleryLibrary(false, client)
        fun visible(text: String) = assertTrue("Missing torrent control: $text", device.wait(Until.hasObject(By.text(text)), 15_000))

        // The original caption is beside the icon button, not inside its clickable ancestor.
        fun openTorrents() = requireNotNull(device.wait(Until.findObject(By.desc("Torrent (1)")), 15_000)).click()
        fun capture(name: String) {
            val folder = requireNotNull(context.getExternalFilesDir("kmp-ui-torrents")).apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(folder, name)))
        }
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
                        val missingHandler = remember {
                            object : UriHandler {
                                override fun openUri(uri: String) {
                                    error("Owned missing torrent application")
                                }
                            }
                        }
                        CompositionLocalProvider(LocalUriHandler provides missingHandler) {
                            GalleryLibraryScreen(library, library.readerState, AndroidGalleryAppearanceStore(), EhDB.sharedHistory, remember { AndroidGalleryDownloads(scope) }, remember { androidGalleryLanguageSettings() }, listPreferences = remember { AndroidGalleryListPreferences() })
                        }
                    }
                }
                requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = limitQuery
                device.submitSharedSearch()
                visible(limitMessage)
                assertEquals(limitQuery, requireNotNull(device.findObject(By.clazz("android.widget.EditText"))).text)
                assertFalse(device.hasObject(By.text("The site returned a page that could not be read.")))
                capture("query-keyword-limit.png")
                requireNotNull(device.findObject(By.clazz("android.widget.EditText"))).text = "owned torrent"
                device.submitSharedSearch()
                visible(title)
                device.clickSharedControl(title)
                assertTrue(device.wait(Until.hasObject(By.desc("Torrent (1)")), 15_000))
                openTorrents()
                visible("The site is temporarily unavailable.")
                assertEquals(1, calls.get())
                capture("torrent-server-failure.png")
                device.clickSharedControl("Retry")
                visible(torrentName)
                visible("2026-11-05")
                visible("63.14 MiB")
                visible("↑12 ↓3 ✓42")
                assertFalse(device.hasObject(By.textContains("Expunged")))
                assertEquals(2, calls.get())
                capture("torrent-loaded.png")
                assertFalse("Stable original rows must not gain an extra action", device.hasObject(By.text("Copy link")))
                device.pressBack()
                assertTrue(device.wait(Until.gone(By.text("Torrents")), 5_000))
                assertEquals(2, calls.get())
                openTorrents()
                visible(torrentName)
                device.clickSharedControl(torrentName)
                visible("Link copied")
                scenario.onActivity { activity ->
                    assertEquals(expected, activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString())
                }
                assertTrue(device.wait(Until.gone(By.text("Torrents")), 5_000))
                openTorrents()
                visible("No torrents")
                assertTrue(device.wait(Until.gone(By.text("Torrents")), 5_000))
                assertEquals(4, calls.get())
                assertTrue(device.hasObject(By.desc("Back to search")))
                scenario.onActivity { activity ->
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                    if (clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() == expected) clipboard.clearPrimaryClip()
                }
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
            }
            Settings.appLinkVerifyTip = tip
            instrumentation.runOnMainSync { setAppLanguage(locales.toLanguageTags()) }
            Locale.setDefault(originalLocale)
        }
    }
}
