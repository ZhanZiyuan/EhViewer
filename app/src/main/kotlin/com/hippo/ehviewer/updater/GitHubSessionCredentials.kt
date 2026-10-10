package com.hippo.ehviewer.updater

import java.net.URI

/** User-supplied CI download credentials. Never persisted or bundled in the APK. */
object GitHubSessionCredentials {
    @Volatile
    private var token: String? = null

    fun set(value: String) {
        require(value.length <= 255 && value.all { it.code in 32..126 }) { "Invalid GitHub token" }
        token = value.trim().ifEmpty { null }
    }

    fun clear() {
        token = null
    }

    fun forDownload(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host.equals("api.github.com", true) && uri.userInfo == null && uri.port in listOf(-1, 443)) {
            "GitHub credentials may only be sent to the HTTPS GitHub API"
        }
        return checkNotNull(token) { "Enter a GitHub token in About for CI downloads. It is kept only for this session." }
    }
}
