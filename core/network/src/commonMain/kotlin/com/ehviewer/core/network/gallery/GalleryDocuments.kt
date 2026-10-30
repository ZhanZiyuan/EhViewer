package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.session.GalleryAccountDecoder
import kotlinx.serialization.Serializable

/** All production platforms decode the same original Rust documents and CBOR models. */
interface GalleryDocumentDecoder : GalleryListDecoder, GalleryAccountDecoder {
    fun decodeDetails(html: ByteArray): GalleryDetail
    fun decodePage(html: ByteArray): GalleryImagePage
}

@Serializable
data class GalleryImagePage(
    val imageUrl: String,
    val skipHathKey: String?,
    val originImageUrl: String?,
    val showKey: String?,
    val nextPageUrl: String?,
    val previousPageUrl: String?,
)
