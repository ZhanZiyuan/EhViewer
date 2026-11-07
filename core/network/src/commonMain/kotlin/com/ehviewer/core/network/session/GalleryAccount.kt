package com.ehviewer.core.network.session

import com.ehviewer.core.network.gallery.GalleryFailure
import kotlinx.serialization.Serializable

@Serializable
data class GalleryAccountProfile(val displayName: String, val avatar: String?)

/** Implemented by the same real Rust parser on every production platform. */
interface GalleryAccountDecoder {
    fun decodeProfileLink(html: ByteArray): String?
    fun decodeProfile(html: ByteArray): GalleryAccountProfile
    fun decodeUserConfig(html: ByteArray): List<String>
}

enum class RestrictedSiteAccess { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class GalleryAccountOverview(
    val profile: GalleryAccountProfile,
    val favoriteCategories: List<String>,
    val restrictedAccess: RestrictedSiteAccess,
    val restrictedFailure: GalleryFailure?,
    val newsInitialized: Boolean,
)
