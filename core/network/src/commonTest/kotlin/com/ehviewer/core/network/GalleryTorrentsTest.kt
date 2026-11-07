package com.ehviewer.core.network

import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.gallery.GalleryTorrent
import com.ehviewer.core.network.session.GalleryAccountProfile
import com.ehviewer.core.network.session.PersistentCookieStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class GalleryTorrentsTest {
    private val torrent = GalleryTorrent(false, "2026-11-05", "63.14 MiB", 12, 3, 42, "Reader", "https://ehtracker.org/get/1/0123456789abcdef0123456789abcdef01234567.torrent", "Alice & 日本語.zip")

    private fun decoder(read: (ByteArray) -> List<GalleryTorrent>) = object : GalleryDocumentDecoder {
        override fun decodeTorrents(html: ByteArray) = read(html)
        override fun decode(html: ByteArray): GallerySearchResult = error("No listing requested")
        override fun decodeDetails(html: ByteArray): com.ehviewer.core.model.GalleryDetail = error("No details requested")
        override fun decodePage(html: ByteArray): GalleryImagePage = error("No page requested")
        override fun decodeProfileLink(html: ByteArray): String? = error("No profile requested")
        override fun decodeProfile(html: ByteArray): GalleryAccountProfile = error("No profile requested")
        override fun decodeUserConfig(html: ByteArray): List<String> = error("No config requested")
        override fun decodeFavoriteNote(html: ByteArray): com.ehviewer.core.network.gallery.GalleryFavoriteNote = error("No favorite requested")
    }

    @Test fun publicAndRestrictedTorrentRequestsUseOriginalParametersRefererAndFreshBoundedTransport() = runTest {
        for (restricted in listOf(false, true)) {
            val host = if (restricted) "exhentai.org" else "e-hentai.org"
            var calls = 0
            var guards = 0
            val engine = MockEngine { request ->
                calls++
                assertEquals(host, request.url.host)
                assertEquals("/gallerytorrents.php", request.url.encodedPath)
                assertEquals(setOf("gid", "t"), request.url.parameters.names())
                assertEquals(listOf("1"), request.url.parameters.getAll("gid"))
                assertEquals(listOf("0123456789"), request.url.parameters.getAll("t"))
                assertEquals("https://$host/g/1/0123456789/", request.headers[HttpHeaders.Referrer])
                assertEquals("no-cache, no-store", request.headers[HttpHeaders.CacheControl])
                respond("owned torrent document")
            }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                val content = GalleryContentRepository(
                    client,
                    decoder {
                        assertEquals("owned torrent document", it.decodeToString())
                        listOf(torrent)
                    },
                    restricted,
                ) { guards++ }
                assertEquals(listOf(torrent), content.torrents(1, "0123456789"))
                assertEquals(1, calls)
                assertEquals(2, guards)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun magnetsEncodeNamesAndUseOnlyTheValidatedPublicHashAndTracker() {
        val magnet = torrent.magnet(1)
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Alice"))
        assertTrue("%26" in magnet && "%E6%97%A5%E6%9C%AC%E8%AA%9E" in magnet)
        assertTrue(magnet.endsWith("&tr=https%3A%2F%2Fehtracker.org%2F1%2Fannounce"))
        assertTrue(".torrent" !in magnet && "/get/" !in magnet)
    }

    @Test fun unsafeLinksInvalidMetadataAndDuplicateResultsCannotReachSystemHandlers() = runTest {
        val wrong = listOf(
            torrent.copy(url = torrent.url.replace("https:", "http:")),
            torrent.copy(url = torrent.url.replace("ehtracker.org", "evil.example")),
            torrent.copy(url = torrent.url.replace("ehtracker.org", "user:password@ehtracker.org")),
            torrent.copy(url = torrent.url + "?credential=do-not-forward"),
            torrent.copy(url = torrent.url + "#fragment"),
            torrent.copy(url = "file:///tmp/untrusted.torrent"),
            torrent.copy(url = "https://ehtracker.org/short.torrent"),
            torrent.copy(name = "Bad\nname"),
            torrent.copy(seeds = -1),
            torrent.copy(name = "x".repeat(16385)),
        )
        for (value in wrong) assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { value.magnet(1) }.failure)
        val engine = MockEngine { respond("owned") }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            for (items in wrong.map { listOf(it) } + listOf(listOf(torrent, torrent), List(1025) { torrent })) {
                assertEquals(
                    GalleryFailure.INVALID_RESPONSE,
                    assertFailsWith<GalleryRequestException> {
                        GalleryContentRepository(client, decoder { items }).torrents(1, "0123456789")
                    }.failure,
                )
            }
            assertEquals(emptyList(), GalleryContentRepository(client, decoder { emptyList() }).torrents(1, "0123456789"))
        } finally {
            client.close()
            engine.close()
        }
    }

    @Test fun authenticationRateAndServerErrorsAreNeverPresentedAsAnEmptyList() = runTest {
        for ((status, failure) in listOf(401 to GalleryFailure.AUTHENTICATION, 403 to GalleryFailure.CHALLENGE, 429 to GalleryFailure.RATE_LIMIT, 503 to GalleryFailure.SERVER)) {
            var decoded = false
            val engine = MockEngine { respond("owned failure", HttpStatusCode.fromValue(status)) }
            val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
            try {
                assertEquals(
                    failure,
                    assertFailsWith<GalleryRequestException> {
                        GalleryContentRepository(
                            client,
                            decoder {
                                decoded = true
                                emptyList()
                            },
                        ).torrents(1, "0123456789")
                    }.failure,
                )
                assertTrue(!decoded)
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test fun cancelledSessionGuardDoesNotStartTransportOrParsing() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("owned")
        }
        val client = galleryHttpClient(engine, PersistentCookieStorage(TestSessionVault()), "test")
        try {
            assertFailsWith<CancellationException> {
                GalleryContentRepository(client, decoder { error("No parsing after cancellation") }) { throw CancellationException("Owned cancelled request") }.torrents(1, "0123456789")
            }
            assertEquals(0, calls)
        } finally {
            client.close()
            engine.close()
        }
    }
}
