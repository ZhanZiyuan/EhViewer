package com.hippo.ehviewer.ui.login

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.network.IdentityCookies
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.exception.NotLoggedInException
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.util.setDefaultSettings
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.flow.first
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import splitties.init.appCtx

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.WebViewSignInScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val state = rememberWebViewState(url = EhUrl.URL_SIGN_IN)
    var showWebView by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        runSuspendCatching {
            changeSession {
                when (EhCookieStore.identityCookies()) {
                    IdentityCookies.Present -> Unit // Verify the existing account without opening a login form.
                    IdentityCookies.Incomplete -> throw NotLoggedInException()
                    IdentityCookies.Missing -> {
                        showWebView = true
                        try {
                            snapshotFlow { state.loadingState }.first {
                                !state.isLoading && EhCookieStore.identityCookies() == IdentityCookies.Present
                            }
                        } finally {
                            state.webView?.stopLoading()
                            showWebView = false
                        }
                    }
                }
                EhEngine.verifyForumSession(useMobileUserAgent = true)
                postLogin(cloudflareBypassed = EhCookieStore.isCloudflareBypassed())
            }
        }.onFailure {
            // A saved WebView session can be verified again from the sign-in screen.
            navigator.popBackStack()
            snackbar(appCtx.getString(R.string.sign_in_failed))
        }
    }
    if (showWebView) {
        WebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onCreated = { it.setDefaultSettings() },
        )
    }
}
