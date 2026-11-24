package com.hippo.ehviewer.ui.login

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.network.IdentityCookies
import com.google.accompanist.web.LoadingState
import com.google.accompanist.web.WebContent
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.exception.NotLoggedInException
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.util.setDefaultSettings
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import splitties.init.appCtx

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.WebViewSignInScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val identityAtEntry = remember { EhCookieStore.identityCookies() }
    val resumingSession = identityAtEntry == IdentityCookies.Present
    val state = rememberWebViewState(url = if (resumingSession) EhUrl.URL_FORUMS else EhUrl.URL_SIGN_IN)
    var showWebView by remember { mutableStateOf(false) }
    var checkingSavedSession by remember { mutableStateOf(resumingSession) }

    LaunchedEffect(state) {
        runSuspendCatching {
            changeSession {
                if (identityAtEntry == IdentityCookies.Incomplete || EhCookieStore.identityCookies() != identityAtEntry) {
                    throw NotLoggedInException()
                }
                showWebView = true
                suspend fun awaitForumSession() {
                    snapshotFlow { state.lastLoadedUrl.takeIf { state.loadingState == LoadingState.Finished } }.filterNotNull().first { url ->
                        val webView = state.webView ?: return@first false
                        if (!webView.confirmsForumSession(url)) return@first false

                        withTimeoutOrNull(2_000) {
                            while (EhCookieStore.identityCookies() != IdentityCookies.Present) delay(50)
                            webView.url == url
                        } == true
                    }
                }

                val resumed = resumingSession && withTimeoutOrNull(12_000) { awaitForumSession() } != null
                if (!resumed) {
                    if (resumingSession) {
                        state.content = WebContent.Url(EhUrl.URL_SIGN_IN)
                        checkingSavedSession = false
                    }
                    awaitForumSession()
                }

                // The page is authenticated in the same WebView that received the cookies.
                // The optional profile request can run after the app's login is committed.
                postLogin(cloudflareBypassed = EhCookieStore.isCloudflareBypassed())
            }
        }.onFailure {
            showWebView = false
            navigator.popBackStack()
            snackbar(appCtx.getString(R.string.sign_in_failed))
        }
    }

    if (showWebView) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            WebView(
                state = state,
                modifier = Modifier.fillMaxSize().alpha(if (checkingSavedSession) 0f else 1f),
                captureBackPresses = !checkingSavedSession,
                onCreated = { it.setDefaultSettings() },
                onDispose = {
                    if (state.webView === it) state.webView = null
                    it.destroy()
                },
            )
            if (checkingSavedSession) CircularWavyProgressIndicator()
        }
    }
}
