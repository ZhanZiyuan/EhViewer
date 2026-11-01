package com.ehviewer.core.network

import com.ehviewer.core.domain.download.GalleryDownloadStorage
import com.ehviewer.core.network.download.GalleryDownloadQueue
import com.ehviewer.core.network.download.networkGalleryDownloadSource
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.session.GalleryAccountRepository
import com.ehviewer.core.network.session.GalleryAuthenticationRepository
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

expect fun platformNetworkEngine(): HttpClientEngine

/** One application-owned engine, secure cookie store and repository; closing releases the engine. */
class GallerySession(vault: SessionVault, private val decoder: GalleryDocumentDecoder, val userAgent: String, restricted: Boolean = false) : GalleryLibrary, AutoCloseable {
    val cookies = PersistentCookieStorage(vault)
    private val engine = platformNetworkEngine()
    private val client = try {
        galleryHttpClient(engine, cookies, userAgent)
    } catch (failure: Exception) {
        engine.close()
        throw failure
    }
    override var restricted = restricted
        private set
    override var galleries = GalleryRepository(client, decoder, restricted)
        private set
    override var content = GalleryContentRepository(client, decoder, restricted)
        private set
    val authentication = GalleryAuthenticationRepository(cookies, userAgent)
    val account = GalleryAccountRepository(cookies, decoder, userAgent)
    override var favorites = GalleryFavoritesRepository(client, cookies, decoder, restricted, cookies::authenticationGeneration)
        private set

    fun downloads(storage: GalleryDownloadStorage, scope: CoroutineScope) = GalleryDownloadQueue(storage, scope) { task ->
        val generation = cookies.authenticationGeneration()
        val requireSession: suspend () -> Unit = { cookies.requireGeneration(generation) }
        networkGalleryDownloadSource(task, GalleryContentRepository(client, decoder, task.restricted, requireSession), requireSession)
    }

    fun selectSite(restricted: Boolean) {
        this.restricted = restricted
        galleries = GalleryRepository(client, decoder, restricted)
        content = GalleryContentRepository(client, decoder, restricted)
        favorites = GalleryFavoritesRepository(client, cookies, decoder, restricted, cookies::authenticationGeneration)
    }

    override fun close() {
        client.close()
        engine.close()
    }
}
