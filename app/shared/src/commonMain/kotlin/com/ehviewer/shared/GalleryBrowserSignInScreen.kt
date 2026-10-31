package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.session.BrowserSessionCookie
import com.ehviewer.core.network.session.BrowserSignInTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Native web content only. Navigation, controls and cookie commit remain shared CMP/KMP code. */
interface GalleryLoginBrowser : AutoCloseable {
    @Composable fun Content(modifier: Modifier)
    suspend fun cookies(): List<BrowserSessionCookie>
}

@Composable
internal fun GalleryBrowserSignInScreen(session: GallerySession, factory: (String) -> GalleryLoginBrowser, onBack: () -> Unit, onSignedIn: () -> Unit) {
    var transaction by remember { mutableStateOf<BrowserSignInTransaction?>(null) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) {
        try {
            transaction = session.authentication.beginBrowserSignIn()
        } catch (error: GalleryRequestException) {
            failure = error.failure
        }
    }
    fun back() {
        operation?.cancel()
        onBack()
    }
    BackHandler(onBack = ::back)
    Column(Modifier.fillMaxSize()) {
        val browser = if (transaction != null) remember(transaction) { factory(session.userAgent) } else null
        if (browser != null) DisposableEffect(browser) { onDispose { browser.close() } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = ::back) { Text("Back") }
            Button(enabled = browser != null && !busy, onClick = {
                operation = scope.launch {
                    busy = true
                    failure = null
                    try {
                        transaction!!.complete(browser!!.cookies())
                        onSignedIn()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: GalleryRequestException) {
                        failure = error.failure
                    } catch (_: Exception) {
                        failure = GalleryFailure.OFFLINE
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Use this session") }
        }
        Text("Complete sign-in on the official website, then use this session. Closing this screen discards the browser session.")
        if (busy || transaction == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { Text(if (it == GalleryFailure.AUTHENTICATION) "Complete sign-in before using this session." else it.userMessage(), color = MaterialTheme.colorScheme.error) }
        browser?.Content(Modifier.fillMaxWidth().weight(1f))
    }
}
