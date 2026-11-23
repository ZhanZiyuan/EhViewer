package com.hippo.ehviewer.ui.login

import android.webkit.WebView
import androidx.annotation.MainThread
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun isForumPage(url: String): Boolean = runCatching { URI(url) }.getOrNull()?.run {
    "https".equals(scheme, ignoreCase = true) &&
        "forums.e-hentai.org".equals(host, ignoreCase = true) &&
        (port == -1 || port == 443) && rawUserInfo == null
} == true

@MainThread
internal suspend fun WebView.confirmsForumSession(page: String): Boolean {
    if (!isForumPage(page) || url != page) return false
    return suspendCancellableCoroutine { continuation ->
        evaluateJavascript(FORUM_SESSION_CHECK) { result ->
            if (continuation.isActive) continuation.resume(result == "true" && url == page)
        }
    }
}

// Return only a boolean. Login pages can contain account details and session tokens.
private val FORUM_SESSION_CHECK = """
    (() => {
        const here = new URL(window.location.href);
        if (here.origin !== "https://forums.e-hentai.org") return false;

        const isLoginAction = (path, params, code) =>
            path === "/index.php" && (params.get("act") || "").toLowerCase() === "login" &&
            params.get("CODE") === code;

        if (isLoginAction(here.pathname, here.searchParams, "01")) {
            const acknowledged = Array.from(document.querySelectorAll("p")).some(
                element => element.textContent.trim().startsWith("You are now logged in as:")
            );
            if (acknowledged) return true;
        }

        return Array.from(document.querySelectorAll("#userlinks a[href]")).some(
            link => link.origin === here.origin &&
                isLoginAction(link.pathname, new URLSearchParams(link.search), "03")
        );
    })()
""".trimIndent()
