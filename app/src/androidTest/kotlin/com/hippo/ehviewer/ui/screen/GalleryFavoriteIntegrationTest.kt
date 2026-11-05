package com.hippo.ehviewer.ui.screen

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboardManager
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager as ComposeClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ehviewer.core.data.DataStoreStoragePreferences
import com.ehviewer.core.data.RoomGalleryDownloadStorage
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.download.DownloadDatabase
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.network.download.GalleryDownloadQueue
import com.ehviewer.core.network.download.networkGalleryDownloadSource
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.ehviewer.core.network.galleryHttpClient
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import com.ehviewer.core.preferences.PrefDelegate
import com.ehviewer.core.preferences.edit
import com.ehviewer.shared.GalleryLibraryScreen
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.MainActivity
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Owned offline HTTP data, real Ktor/JNI/CBOR/Room and the same production CMP editor. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class GalleryFavoriteIntegrationTest {
    @Test fun sharedEditorAddsPreservesCancelsReloadsAndRemovesARealDecodedFavorite() {
        System.loadLibrary("ehviewer")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val gid = 9_000_000_000_000L + (UUID.randomUUID().mostSignificantBits and 0xff_ffff_ffffL)
        val token = "0123456789"
        val title = "Favorite integration fixture"
        val owned = File(context.cacheDir, "favorite-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val before = runBlocking { Settings.snapshot() }
        val tip = Settings.appLinkVerifyTip
        val category = AtomicReference<Int?>(null)
        val note = AtomicReference("")
        val rejectNext = AtomicBoolean(false)
        val rejectCopy = AtomicBoolean(false)
        val rejectBrowser = AtomicBoolean(false)
        val rejectSimilar = AtomicBoolean(true)
        val similarRequests = Collections.synchronizedList(mutableListOf<Url>())
        val openedLinks = Collections.synchronizedList(mutableListOf<Intent>())
        val sharedLinks = Collections.synchronizedList(mutableListOf<Intent>())
        val browserMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) {
                    val shared = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (shared?.action == Intent.ACTION_SEND && shared.type == "text/plain") {
                        sharedLinks += Intent(shared)
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                if (intent.action == Intent.ACTION_VIEW && intent.data?.host == "e-hentai.org") {
                    openedLinks += Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        val posts = Collections.synchronizedList(mutableListOf<Parameters>())
        val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
        var vaultBytes: ByteArray? = null
        val cookies = PersistentCookieStorage(object : SessionVault {
            override suspend fun read() = vaultBytes?.copyOf()
            override suspend fun write(bytes: ByteArray) {
                vaultBytes = bytes.copyOf()
            }
            override suspend fun clear() {
                vaultBytes = null
            }
        })
        fun escaped(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun detail(): String {
            val favorite = category.get()?.let { """<div title="Shelf $it" style="background-position:0px -${2 + it * 19}px"></div>""" }.orEmpty()
            return """<!DOCTYPE html><html><body>
                <script>var gid = $gid; var token = "$token"; var apiuid = -1; var apikey = "0000000000"; var average_rating = 4.5;</script>
                <div id="gd5"><a onclick="one()">Archive</a><a onclick="two()">Torrent (0)</a></div>
                <div id="gd1"><div style="background:url(https://ehgt.org/owned.png)"></div></div>
                <h1 id="gn">$title</h1><h1 id="gj">Fixture</h1><div id="gdc">Manga</div>
                <div id="gdd"><table><tr><td>Length:</td><td class="gdt2">1 pages</td></tr><tr><td>File Size:</td><td>1 MB</td></tr></table></div>
                <span id="rating_count">3</span><div id="fav">$favorite</div><div id="taglist"><table></table></div>
                <div id="cdiv"><div id="chd"><a id="postnewcomment">Comment</a></div></div>
                <div id="gdt"><a href="https://e-hentai.org/s/1111111111/$gid-1"><div title="Page 1" style="width:80px;height:100px;background:url(https://ehgt.org/owned.png)"></div></a></div>
                </body></html>"""
        }
        val engine = MockEngine { request ->
            if (request.url.host == "ehgt.org") {
                check(!request.headers[HttpHeaders.Cookie].orEmpty().contains("ipb_"))
                return@MockEngine respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            }
            check(request.url.host == "e-hentai.org")
            if (request.method == HttpMethod.Post) {
                check(request.url.encodedPath == "/gallerypopups.php")
                val fields = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                posts += fields
                if (rejectNext.compareAndSet(true, false)) {
                    respond("Owned temporary rejection", HttpStatusCode.InternalServerError)
                } else {
                    category.set(fields["favcat"]!!.toIntOrNull())
                    note.set(if (category.get() == null) "" else fields["favnote"]!!)
                    respond("Owned accepted")
                }
            } else {
                when {
                    request.url.encodedPath == "/" && request.url.parameters["f_search"] == "\"$title\"" && rejectSimilar.getAndSet(false) -> {
                        similarRequests += request.url
                        respond("Owned unavailable Similar search", HttpStatusCode.ServiceUnavailable)
                    }
                    request.url.encodedPath == "/" -> {
                        if (request.url.parameters["f_search"] == "\"$title\"") similarRequests += request.url
                        respond("""<!DOCTYPE html><html><body><div class="itg"><div><div class="glname"><a href="https://e-hentai.org/g/$gid/$token/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/owned.png" style="height:100px;width:80px"><div class="cn">Manga</div><div class="ir" style="background-position:0px -1px"></div></div></div></body></html>""")
                    }
                    request.url.encodedPath == "/uconfig.php" -> respond("<!DOCTYPE html><html><body>" + (0..9).joinToString("") { """<input type="text" name="favorite_$it" value="Shelf $it">""" } + "</body></html>")
                    request.url.encodedPath == "/gallerypopups.php" -> respond("<!DOCTYPE html><html><body><textarea name=\"favnote\" maxlength=\"200\">${escaped(note.get())}</textarea></body></html>")
                    request.url.encodedPath == "/g/$gid/$token/" -> respond(detail())
                    else -> error("Unexpected owned favorite request")
                }
            }
        }
        runBlocking {
            cookies.addCookie(Url("https://e-hentai.org/"), Cookie("ipb_member_id", "owned-fixture", path = "/"))
            cookies.addCookie(Url("https://e-hentai.org/"), Cookie("ipb_pass_hash", "owned-fixture-secret", path = "/"))
            assertNull(EhDB.getGalleryInfo(gid))
        }
        val client = galleryHttpClient(engine, cookies, "favorite-ui-test")
        val library = AndroidGalleryLibrary(false, client, cookies)
        val scopeOwner = SupervisorJob()
        val scope = CoroutineScope(scopeOwner + Dispatchers.Default)
        val database = Room.databaseBuilder<DownloadDatabase>(context, File(owned, "queue.db").absolutePath).setDriver(AndroidSQLiteDriver()).build()
        val storageStore = PreferenceDataStoreFactory.createWithPath(scope = scope) { File(owned, "storage.preferences_pb").toOkioPath() }
        val storagePreferences = DataStoreStoragePreferences(storageStore)
        val downloadStorage = RoomGalleryDownloadStorage(database, File(owned, "images").toOkioPath(), preferences = storagePreferences)
        val downloads = GalleryDownloadQueue(downloadStorage, scope) { task ->
            networkGalleryDownloadSource(task, GalleryContentRepository(client, RustGalleryDecoder)) {}
        }
        fun click(text: String, icon: Boolean = false) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                try {
                    var control = if (icon) device.findObject(By.desc(text)) else device.findObject(By.text(text)) ?: device.findObject(By.desc(text))
                    while (control != null && !control.isClickable) control = control.parent
                    if (control != null && control.isEnabled) {
                        control.click()
                        return
                    }
                } catch (_: StaleObjectException) {
                    // No click was dispatched: reacquire the enabled action after recomposition.
                }
                SystemClock.sleep(25)
            }
            assertTrue("Enabled production action did not become available: $text", false)
        }
        fun reveal(text: String) {
            check(device.wait(Until.hasObject(By.text("Category")), 15_000))
            repeat(10) {
                if (device.findObject(By.text(text))?.visibleBounds?.isEmpty == false) return
                requireNotNull(device.wait(Until.findObject(By.desc("Favorite editor")), 15_000)).scroll(Direction.DOWN, 0.65f)
            }
            check(device.hasObject(By.text(text)))
        }
        fun noteField() = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").hasDescendant(By.desc("Favorite note"))), 15_000))
        Settings.appLinkVerifyTip = true
        Settings.edit(blocking = true) {
            it[needSignIn] = false
            it[updateIntervalDays] = 0
            it[launchPage] = 8
        }
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val galleryUrl = "https://e-hentai.org/g/$gid/$token/"
                try {
                    instrumentation.addMonitor(browserMonitor)
                    scenario.onActivity { activity ->
                        activity.getSystemService(AndroidClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Owned clipboard fixture", "owned-link-sentinel"))
                        activity.setContent {
                            val nativeClipboard = LocalClipboardManager.current
                            val nativeBrowser = rememberGalleryBrowserHandler()
                            val clipboard = remember(nativeClipboard) {
                                object : ComposeClipboardManager by nativeClipboard {
                                    override fun setText(annotatedString: AnnotatedString) {
                                        check(!rejectCopy.getAndSet(false)) { "Owned clipboard failure" }
                                        nativeClipboard.setText(annotatedString)
                                    }
                                }
                            }
                            val browser = remember(nativeBrowser) {
                                object : UriHandler {
                                    override fun openUri(uri: String) {
                                        check(!rejectBrowser.getAndSet(false)) { "Owned browser failure" }
                                        nativeBrowser.openUri(uri)
                                    }
                                }
                            }
                            CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalUriHandler provides browser) {
                                GalleryLibraryScreen(library, library.readerState, AndroidGalleryAppearanceStore(), EhDB.sharedHistory, downloads, remember { androidGalleryLanguageSettings() }, storageSettings = downloadStorage)
                            }
                        }
                    }
                    device.submitSharedSearch()
                    click(title)
                    val readBounds = requireNotNull(device.wait(Until.findObject(By.text("Read").enabled(true)), 15_000)).visibleBounds
                    fun assertFeedbackDoesNotMoveDetails() {
                        val current = requireNotNull(device.wait(Until.findObject(By.text("Read").enabled(true)), 15_000)).visibleBounds
                        if (readBounds != current) {
                            val image = File(context.cacheDir, "detail-feedback-layout-${SystemClock.uptimeMillis()}.png")
                            check(device.takeScreenshot(image))
                            println("Detail feedback layout failure screenshot=$image before=$readBounds after=$current")
                        }
                        assertEquals("Link feedback must overlay the detail viewport without moving its content", readBounds, current)
                    }
                    fun clipboardText(): String? {
                        var value: String? = null
                        scenario.onActivity { activity -> value = activity.getSystemService(AndroidClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() }
                        return value
                    }
                    rejectCopy.set(true)
                    requireNotNull(device.wait(Until.findObject(By.desc("More options")), 15_000)).click()
                    click("Copy link")
                    assertTrue(device.wait(Until.hasObject(By.text("The link could not be copied. Try again.")), 15_000))
                    assertFeedbackDoesNotMoveDetails()
                    assertEquals("owned-link-sentinel", clipboardText())
                    requireNotNull(device.wait(Until.findObject(By.desc("More options")), 15_000)).click()
                    click("Copy link")
                    assertTrue(device.wait(Until.hasObject(By.text("Link copied")), 15_000))
                    assertFeedbackDoesNotMoveDetails()
                    assertEquals(galleryUrl, clipboardText())
                    rejectBrowser.set(true)
                    requireNotNull(device.wait(Until.findObject(By.desc("More options")), 15_000)).click()
                    click("Open in browser")
                    assertTrue(device.wait(Until.hasObject(By.text("The browser could not be opened. Try again.")), 15_000))
                    assertFeedbackDoesNotMoveDetails()
                    assertTrue(openedLinks.isEmpty())
                    requireNotNull(device.wait(Until.findObject(By.desc("More options")), 15_000)).click()
                    click("Open in browser")
                    assertTrue(device.wait(Until.hasObject(By.text("Sent to the browser")), 15_000))
                    assertFeedbackDoesNotMoveDetails()
                    assertEquals(1, openedLinks.size)
                    assertEquals(galleryUrl, openedLinks.single().dataString)
                    assertTrue(openedLinks.single().hasExtra("android.support.customtabs.extra.SESSION"))
                    requireNotNull(device.wait(Until.findObject(By.desc("Share")), 15_000)).click()
                    val shareDeadline = SystemClock.uptimeMillis() + 15_000
                    while (sharedLinks.isEmpty() && SystemClock.uptimeMillis() < shareDeadline) SystemClock.sleep(25)
                    assertEquals(1, sharedLinks.size)
                    assertEquals(galleryUrl, sharedLinks.single().getStringExtra(Intent.EXTRA_TEXT))
                    assertEquals("text/plain", sharedLinks.single().type)
                    // The real Android intent was observed and explicitly cancelled by this owned
                    // monitor. This does not establish external browser rendering or completed sharing.
                    click("Back to search")
                    click(title)
                    assertFalse(device.hasObject(By.text("Link copied")))
                    assertFalse(device.hasObject(By.text("Sent to the browser")))
                    click("Add to favorites")
                    reveal("8. Shelf 7")
                    click("8. Shelf 7")
                    reveal("Favorite note")
                    val original = "Owned 日本語 + & <note>"
                    noteField().text = original
                    click("Save favorite")
                    click("Edit favorite")
                    reveal("Favorite note")
                    assertEquals(original, note.get())
                    assertEquals(original, noteField().text)
                    assertEquals(7, category.get())
                    assertEquals(1, posts.size)
                    noteField().text = "Do not send this cancelled draft"
                    click("Cancel")
                    assertEquals(original, note.get())
                    assertEquals(1, posts.size)
                    click("Edit favorite")
                    reveal("Favorite note")
                    noteField().text = "Rejected owned draft"
                    rejectNext.set(true)
                    click("Save favorite")
                    assertTrue(device.wait(Until.hasObject(By.text("The favorite state could not be confirmed. Reload before continuing.")), 15_000))
                    val saveButton = requireNotNull(requireNotNull(device.findObject(By.text("Save favorite"))).parent)
                    assertFalse(saveButton.isEnabled)
                    assertEquals(2, posts.size)
                    assertEquals(original, note.get())
                    click("Reload favorite")
                    reveal("Favorite note")
                    assertTrue(device.wait(Until.hasObject(By.text(original)), 15_000))
                    assertEquals(original, noteField().text)
                    assertEquals(2, posts.size)
                    reveal("Remove favorite")
                    click("Remove favorite")
                    assertTrue(device.wait(Until.hasObject(By.desc("Add to favorites")), 15_000))
                    assertNull(category.get())
                    assertEquals("favdel", posts.last()["favcat"])
                    assertEquals(3, posts.size)
                    click("Similar", icon = true)
                    assertTrue(device.wait(Until.hasObject(By.text("The site is temporarily unavailable.")), 15_000))
                    assertEquals("\"$title\"", requireNotNull(device.findObject(By.clazz("android.widget.EditText"))).text)
                    assertEquals(1, similarRequests.size)
                    // The original list retries by tapping its actual error card, not a separate button.
                    click("The site is temporarily unavailable.")
                    assertTrue(device.wait(Until.hasObject(By.text(title)), 15_000))
                    assertEquals(2, similarRequests.size)
                    assertTrue(similarRequests.all { it.parameters["f_search"] == "\"$title\"" && it.parameters["f_cats"] == null && it.parameters["next"] == null })
                    assertEquals(3, posts.size)
                    click(title)
                    click("Back to search")
                    device.openSharedDestination("Settings")
                    click("Download")
                    fun storageChoice(text: String) {
                        repeat(12) {
                            try {
                                if (device.findObject(By.text(text))?.visibleBounds?.isEmpty == false) return
                                // This category can fit entirely on screen and expose no scroll node.
                                // Wait for its actual target as well as a viewport, within the same5s.
                                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                                var viewport = device.findObject(By.scrollable(true))
                                while (viewport == null && android.os.SystemClock.uptimeMillis() < deadline) {
                                    if (device.findObject(By.text(text))?.visibleBounds?.isEmpty == false) return
                                    android.os.SystemClock.sleep(25)
                                    viewport = device.findObject(By.scrollable(true))
                                }
                                viewport?.scroll(Direction.DOWN, 0.7f)
                            } catch (_: StaleObjectException) {
                                // Reacquire after recomposition within the original12 attempts/5s wait.
                            }
                        }
                        assertTrue("Missing visible real storage setting: $text", device.findObject(By.text(text))?.visibleBounds?.isEmpty == false)
                    }
                    storageChoice("256 MiB limit")
                    var storageCheck = 0
                    fun savedLimit(expected: Int) = runBlocking {
                        storageCheck++
                        try {
                            withTimeout(5_000) {
                                while (true) {
                                    val selected = try {
                                        device.findObject(By.text("$expected MiB limit"))?.parent?.let { it.isChecked && it.isEnabled } == true
                                    } catch (_: StaleObjectException) {
                                        false // A new composition replaces the node; reacquire within the original deadline.
                                    }
                                    if (storagePreferences.downloadLimitMiB() == expected && selected) break
                                    delay(25)
                                }
                            }
                        } catch (failure: TimeoutCancellationException) {
                            device.dumpWindowHierarchy(File(context.cacheDir, "favorite-storage-failure.xml"))
                            device.takeScreenshot(File(context.cacheDir, "favorite-storage-failure.png"))
                            throw AssertionError("Storage selection check $storageCheck did not render $expected within the original deadline", failure)
                        }
                    }
                    click("256 MiB limit")
                    assertTrue(device.wait(Until.hasObject(By.text("Saved download images: 0 bytes")), 5_000))
                    savedLimit(256)
                    runBlocking { storageStore.edit { it[intPreferencesKey("schema")] = 2 } }
                    click("1024 MiB limit")
                    assertTrue(device.wait(Until.hasObject(By.text("The storage limit could not be saved. Reload before trying again.")), 5_000))
                    val clippedChoice = device.findObject(By.text("1024 MiB limit"))?.visibleBounds?.isEmpty != false
                    if (clippedChoice) {
                        device.dumpWindowHierarchy(File(context.cacheDir, "favorite-storage-disabled-before-scroll.xml"))
                        device.takeScreenshot(File(context.cacheDir, "favorite-storage-disabled-before-scroll.png"))
                    }
                    storageChoice("1024 MiB limit")
                    if (clippedChoice) {
                        device.dumpWindowHierarchy(File(context.cacheDir, "favorite-storage-disabled-after-scroll.xml"))
                        device.takeScreenshot(File(context.cacheDir, "favorite-storage-disabled-after-scroll.png"))
                    }
                    val failedChoice = requireNotNull(requireNotNull(device.findObject(By.text("1024 MiB limit"))).parent)
                    assertFalse(failedChoice.isEnabled)
                    runBlocking { storageStore.edit { it[intPreferencesKey("schema")] = 1 } }
                    click("Refresh storage usage")
                    savedLimit(256)
                    click("1024 MiB limit")
                    savedLimit(1024)
                    assertEquals(3, posts.size) // Storage controls never trigger another account request.
                } finally {
                    scenario.onActivity { activity ->
                        val clipboard = activity.getSystemService(AndroidClipboardManager::class.java)
                        if (clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() in setOf("owned-link-sentinel", galleryUrl)) clipboard.clearPrimaryClip()
                    }
                }
            }
        } finally {
            instrumentation.removeMonitor(browserMonitor)
            runBlocking { downloads.closeAndJoin() }
            runBlocking { scopeOwner.cancelAndJoin() }
            database.close()
            client.close()
            engine.close()
            runBlocking {
                cookies.logout()
                EhDB.deleteHistoryInfo(BaseGalleryInfo(gid = gid, token = token).asEntity())
                assertNull(EhDB.getGalleryInfo(gid))
            }
            Settings.edit(blocking = true) { current ->
                fun <T> restore(pref: PrefDelegate<T>) {
                    if (pref in before) current[pref] = before[pref] else current.remove(pref)
                }
                restore(needSignIn)
                restore(updateIntervalDays)
                restore(launchPage)
            }
            Settings.appLinkVerifyTip = tip
            check(owned.deleteRecursively())
        }
    }
}
