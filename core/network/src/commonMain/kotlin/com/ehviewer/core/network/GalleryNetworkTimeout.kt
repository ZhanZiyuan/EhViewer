package com.ehviewer.core.network

import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.GalleryNetworkPreferencesException
import com.ehviewer.core.domain.validateGalleryRequestTimeout
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.timeout

class GalleryNetworkTimeoutConfig {
    var preferences: GalleryNetworkPreferences? = null
}

/** Read DataStore's current snapshot before sending; existing explicit request limits win. */
val GalleryNetworkTimeout = createClientPlugin("EhViewerNetworkTimeout", ::GalleryNetworkTimeoutConfig) {
    val preferences = checkNotNull(pluginConfig.preferences)
    onRequest { request, _ ->
        val seconds = try {
            preferences.requestTimeoutSeconds().also(::validateGalleryRequestTimeout)
        } catch (_: GalleryNetworkPreferencesException) {
            throw GalleryRequestException(GalleryFailure.LOCAL_STORAGE)
        }
        if (seconds != null && request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis == null) {
            request.timeout { requestTimeoutMillis = seconds * 1000L }
        }
    }
}
