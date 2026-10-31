package com.hippo.ehviewer.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.session.BrowserSessionCookie
import com.ehviewer.shared.GalleryLoginBrowser
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Created/closed on the application main thread. Only the DLL reference stays alive;
 * Windows IO operations each create their own confined arena on their caller thread. */
internal class DesktopBrowserService(private val library: Path, private val binary: Path) : AutoCloseable {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val libraryOwner = if (windows) openCapi(library) else null
    private val calls: BrowserCalls = if (windows) WindowsBrowserCalls(library) else NativeBrowserBridge()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closing = AtomicBoolean()
    private val browsers = mutableSetOf<DesktopGalleryLoginBrowser>()

    fun create(userAgent: String): GalleryLoginBrowser = synchronized(browsers) {
        check(!closing.get())
        DesktopGalleryLoginBrowser(calls, binary, userAgent, scope) { browser ->
            synchronized(browsers) { browsers.remove(browser) }
        }.also {
            browsers.add(it)
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        // Cleanup removes entries on IO threads. Capture ownership under the same
        // lock as registration/removal, before awaiting any native work.
        val pending = synchronized(browsers) { browsers.toList() }
        try {
            runBlocking {
                withTimeout(30_000) {
                    pending.forEach { browser ->
                        browser.close()
                        browser.cleanup()
                    }
                    check(withContext(Dispatchers.IO) { calls.active() } == 0)
                }
            }
        } finally {
            scope.cancel()
            libraryOwner?.close()
        }
    }
}

private enum class BrowserStatus { OPENING, READY, CLOSED, FAILED }

/** Same CMP account/confirmation screen as iOS, with the desktop's native browser window. */
private class DesktopGalleryLoginBrowser(
    private val calls: BrowserCalls,
    private val binary: Path,
    private val userAgent: String,
    private val scope: CoroutineScope,
    private val onClosed: (DesktopGalleryLoginBrowser) -> Unit,
) : GalleryLoginBrowser {
    private val handle = AtomicLong()
    private val owned = AtomicLong()
    private val ready = CompletableDeferred<Long>()
    private val openingFinished = CompletableDeferred<Unit>()
    private val cleanupMutex = Mutex()
    private val started = AtomicBoolean()
    private var status by mutableStateOf(BrowserStatus.OPENING)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (handle.get() == -1L) {
            openingFinished.complete(Unit)
            return
        }
        scope.launch {
            try {
                check(calls.protocolVersion() == 1)
                val id = calls.open(binary.toAbsolutePath().toString(), userAgent, regression = false)
                owned.set(id)
                if (handle.compareAndSet(0, id)) {
                    status = BrowserStatus.READY
                    ready.complete(id)
                }
            } catch (cancelled: CancellationException) {
                ready.cancel(cancelled)
                throw cancelled
            } catch (_: Exception) {
                status = BrowserStatus.FAILED
                ready.completeExceptionally(GalleryRequestException(GalleryFailure.OFFLINE))
            } catch (_: LinkageError) {
                status = BrowserStatus.FAILED
                ready.completeExceptionally(GalleryRequestException(GalleryFailure.OFFLINE))
            } finally {
                openingFinished.complete(Unit)
                if (handle.get() == -1L) cleanup()
            }
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        LaunchedEffect(Unit) {
            // Start after composition commits: otherwise a fast native failure can
            // be overwritten by the initial Compose snapshot's OPENING value.
            start()
            while (isActive) {
                val id = handle.get()
                if (id > 0 && !withContext(Dispatchers.IO) { runCatching { calls.running(id) }.getOrDefault(false) }) {
                    status = BrowserStatus.CLOSED
                    close()
                    break
                }
                delay(500)
            }
        }
        Column(modifier) {
            when (status) {
                BrowserStatus.OPENING -> {
                    LinearProgressIndicator()
                    Text("Opening the sign-in browser…")
                }
                BrowserStatus.READY -> Text("Complete sign-in in the browser window, then use this session here.")
                BrowserStatus.CLOSED -> Text("The browser was closed. Return and open it again to sign in.")
                BrowserStatus.FAILED -> Text("The sign-in browser is unavailable. Return and try again.")
            }
        }
    }

    override suspend fun cookies(): List<BrowserSessionCookie> = withContext(Dispatchers.IO) {
        val id = ready.await()
        if (handle.get() != id) throw CancellationException("Browser closed")
        val bytes = try {
            calls.cookies(id)
        } catch (_: Exception) {
            throw GalleryRequestException(GalleryFailure.OFFLINE)
        }
        try {
            decodeBrowserCookies(bytes)
        } catch (_: Exception) {
            throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
        }
    }

    override fun close() {
        if (handle.getAndSet(-1) == -1L) return
        // A composition may be abandoned before Content's effect ever starts.
        // Such an instance has no opening coroutine to complete this signal.
        if (!started.get()) openingFinished.complete(Unit)
        ready.cancel()
        scope.launch { cleanup() }
    }

    suspend fun cleanup() {
        openingFinished.await()
        cleanupMutex.withLock {
            val id = owned.get()
            if (id > 0) {
                withContext(Dispatchers.IO) {
                    calls.close(id)
                    owned.compareAndSet(id, 0)
                }
            }
            onClosed(this)
        }
    }
}
