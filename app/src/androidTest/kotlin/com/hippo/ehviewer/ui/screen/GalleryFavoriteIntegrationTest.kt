package com.hippo.ehviewer.ui.screen

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Owned offline HTTP data, real Ktor/JNI/CBOR/Room and the same production CMP editor. */
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
                    request.url.encodedPath == "/" -> respond("""<!DOCTYPE html><html><body><div class="itg"><div><div class="glname"><a href="https://e-hentai.org/g/$gid/$token/"><span class="glink">$title</span></a></div><img src="https://ehgt.org/owned.png" style="height:100px;width:80px"><div class="cn">Manga</div><div class="ir" style="background-position:0px -1px"></div></div></div></body></html>""")
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = Room.databaseBuilder<DownloadDatabase>(context, File(owned, "queue.db").absolutePath).setDriver(AndroidSQLiteDriver()).build()
        val downloads = GalleryDownloadQueue(RoomGalleryDownloadStorage(database, File(owned, "images").toOkioPath()), scope) { task ->
            networkGalleryDownloadSource(task, GalleryContentRepository(client, RustGalleryDecoder)) {}
        }
        fun click(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 15_000)).click()
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
                scenario.onActivity { activity -> activity.setContent { GalleryLibraryScreen(library, library.readerState, AndroidGalleryAppearanceStore(), EhDB.sharedHistory, downloads, remember { androidGalleryLanguageSettings() }) } }
                click("Search")
                click(title)
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
                assertTrue(device.wait(Until.hasObject(By.text("Add to favorites")), 15_000))
                assertNull(category.get())
                assertEquals("favdel", posts.last()["favcat"])
                assertEquals(3, posts.size)
            }
        } finally {
            runBlocking { downloads.closeAndJoin() }
            scope.cancel()
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
