package com.hippo.ehviewer.updater

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubSessionCredentialsTest {
    @After
    fun clear() = GitHubSessionCredentials.clear()

    @Test
    fun downloadRequiresUserSuppliedSessionCredential() {
        assertThrows(IllegalStateException::class.java) { GitHubSessionCredentials.forDownload("https://api.github.com/repos/test/repo/actions/artifacts/1/zip") }
        GitHubSessionCredentials.set("test-session-token")
        assertEquals("test-session-token", GitHubSessionCredentials.forDownload("https://api.github.com/repos/test/repo/actions/artifacts/1/zip"))
        GitHubSessionCredentials.set("")
        assertThrows(IllegalStateException::class.java) { GitHubSessionCredentials.forDownload("https://api.github.com/") }
    }

    @Test
    fun credentialCannotBeSentToOtherOriginsOrPlainHttp() {
        GitHubSessionCredentials.set("test-session-token")
        for (url in listOf("http://api.github.com/", "https://api.github.com.evil.test/", "https://api.github.com@evil.test/", "https://user@api.github.com/", "https://api.github.com:444/", "https://github.com/")) {
            assertThrows(IllegalArgumentException::class.java) { GitHubSessionCredentials.forDownload(url) }
        }
    }

    @Test
    fun rejectsHeaderInjectionAndOversizedCredentials() {
        for (token in listOf("test\r\nHeader: value", "\ttest", "x".repeat(256), "密码")) {
            assertThrows(IllegalArgumentException::class.java) { GitHubSessionCredentials.set(token) }
        }
    }
}
