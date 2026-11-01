package com.ehviewer.core.network

import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.GalleryNetworkPreferencesException
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class GalleryNetworkTimeoutTest {
    private class Preferences : GalleryNetworkPreferences {
        var value: Int? = null
        override suspend fun requestTimeoutSeconds() = value
        override suspend fun setRequestTimeoutSeconds(seconds: Int?) {
            value = seconds
        }
    }

    @Test fun anExplicitShortLimitReallyCancelsTheEngineAndIsNotExtendedByTheSavedPreference() = runTest {
        val preferences = Preferences().apply { value = 120 }
        val cancelled = CompletableDeferred<Unit>()
        var sends = 0
        val engine = MockEngine {
            sends++
            try {
                delay(60_000)
                respond("unexpected")
            } finally {
                cancelled.complete(Unit)
            }
        }
        val client = HttpClient(engine) {
            install(GalleryNetworkTimeout) { this.preferences = preferences }
            install(HttpTimeout)
        }
        try {
            assertFailsWith<HttpRequestTimeoutException> {
                client.get("https://example.invalid/") { timeout { requestTimeoutMillis = 100 } }
            }
            cancelled.await()
            assertEquals(1, sends)
            assertEquals(120, preferences.requestTimeoutSeconds())
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun defaultSelectionAndResetKeepOriginalPlatformLimitsAndExplicitRequestsWin() = runTest {
        val preferences = Preferences()
        val seen = mutableListOf<Triple<Long?, Long?, Long?>>()
        val engine = MockEngine { request ->
            val timeout = checkNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
            seen += Triple(timeout.requestTimeoutMillis, timeout.connectTimeoutMillis, timeout.socketTimeoutMillis)
            respond("ok")
        }
        val client = HttpClient(engine) {
            install(GalleryNetworkTimeout) { this.preferences = preferences }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
        try {
            client.get("https://example.invalid/")
            preferences.setRequestTimeoutSeconds(60)
            client.get("https://example.invalid/")
            client.get("https://example.invalid/") { timeout { requestTimeoutMillis = 1234 } }
            preferences.setRequestTimeoutSeconds(null)
            client.get("https://example.invalid/")
            assertEquals(listOf(10_000L, 60_000L, 1234L, 10_000L), seen.map { it.first })
            assertEquals(List(4) { 15_000L to 30_000L }, seen.map { it.second to it.third })
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun aChangedPreferenceAffectsTheNextRequestWithoutCancellingTheAlreadySentOne() = runTest {
        val preferences = Preferences().apply { value = 30 }
        val firstSent = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val seen = mutableListOf<Long?>()
        val engine = MockEngine { request ->
            seen += request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
            if (request.url.encodedPath == "/first") {
                firstSent.complete(Unit)
                finishFirst.await()
            }
            respond("ok")
        }
        val client = HttpClient(engine) {
            install(GalleryNetworkTimeout) { this.preferences = preferences }
            install(HttpTimeout)
        }
        try {
            val first = async { client.get("https://example.invalid/first") }
            firstSent.await()
            preferences.setRequestTimeoutSeconds(120)
            client.get("https://example.invalid/second")
            finishFirst.complete(Unit)
            assertEquals(200, first.await().status.value)
            assertEquals(listOf<Long?>(30_000L, 120_000L), seen)
        } finally {
            finishFirst.complete(Unit)
            client.close()
            engine.close()
        }
    }

    @Test fun unreadablePreferencesFailBeforeSendingWithoutReplacingTheSavedChoice() = runTest {
        var sends = 0
        val engine = MockEngine {
            sends++
            respond("unexpected")
        }
        val preferences = object : GalleryNetworkPreferences {
            override suspend fun requestTimeoutSeconds(): Int? = throw GalleryNetworkPreferencesException()
            override suspend fun setRequestTimeoutSeconds(seconds: Int?) = error("Must not write")
        }
        val client = HttpClient(engine) {
            install(GalleryNetworkTimeout) { this.preferences = preferences }
            install(HttpTimeout)
        }
        try {
            val failure = assertFailsWith<GalleryRequestException> { client.get("https://example.invalid/") }
            assertEquals(GalleryFailure.LOCAL_STORAGE, failure.failure)
            assertEquals(0, sends)
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun cancellationWhileReadingPreferencesNeverBecomesANetworkErrorOrSendsARequest() = runTest {
        var sends = 0
        val engine = MockEngine {
            sends++
            respond("unexpected")
        }
        val preferences = object : GalleryNetworkPreferences {
            override suspend fun requestTimeoutSeconds(): Int? = throw CancellationException("owned cancellation")
            override suspend fun setRequestTimeoutSeconds(seconds: Int?) = error("Must not write")
        }
        val client = HttpClient(engine) {
            install(GalleryNetworkTimeout) { this.preferences = preferences }
            install(HttpTimeout)
        }
        try {
            assertFailsWith<CancellationException> { client.get("https://example.invalid/") }
            assertEquals(0, sends)
        } finally {
            client.close()
            engine.close()
        }
    }
}
