package com.ehviewer.core.domain

/** Null retains the platform's existing timeout. Changes apply to subsequent requests. */
interface GalleryNetworkPreferences {
    suspend fun requestTimeoutSeconds(): Int?
    suspend fun setRequestTimeoutSeconds(seconds: Int?)
}

class GalleryNetworkPreferencesException : Exception("Network preferences could not be loaded or saved")

fun validateGalleryRequestTimeout(seconds: Int?) {
    require(seconds == null || seconds in setOf(10, 30, 60, 120)) { "Unsupported request timeout" }
}
