package com.ehviewer.core.network

import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryRepository
import com.ehviewer.core.network.session.GalleryAuthenticationRepository
import com.ehviewer.core.network.session.PersistentCookieStorage
import com.ehviewer.core.network.session.SessionVault
import io.ktor.client.engine.HttpClientEngine

expect fun platformNetworkEngine(): HttpClientEngine

/** One application-owned engine, secure cookie store and repository; closing releases the engine. */
class GallerySession(vault: SessionVault, decoder: GalleryDocumentDecoder, val userAgent: String, restricted: Boolean = false) : AutoCloseable {
    val cookies = PersistentCookieStorage(vault)
    private val engine = platformNetworkEngine()
    private val client = try {
        galleryHttpClient(engine, cookies, userAgent)
    } catch (failure: Exception) {
        engine.close()
        throw failure
    }
    val galleries = GalleryRepository(client, decoder, restricted)
    val content = GalleryContentRepository(client, decoder, restricted)
    val authentication = GalleryAuthenticationRepository(cookies, userAgent)

    override fun close() {
        client.close()
        engine.close()
    }
}
