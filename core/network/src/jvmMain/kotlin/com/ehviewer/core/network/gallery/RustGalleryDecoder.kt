package com.ehviewer.core.network.gallery

import com.ehviewer.core.model.GalleryDetail
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray

/** The application loads its bundled original Rust JNI library before creating this decoder. */
object RustGalleryDecoder : GalleryDocumentDecoder {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    override fun decode(html: ByteArray): GallerySearchResult {
        require(html.isNotEmpty() && html.size <= 8 * 1024 * 1024)
        return cbor.decodeFromByteArray(decodeNative(html))
    }

    private external fun decodeNative(html: ByteArray): ByteArray
    private external fun decodeDetailsNative(html: ByteArray): ByteArray
    private external fun decodePageNative(html: ByteArray): ByteArray

    override fun decodeDetails(html: ByteArray): GalleryDetail {
        require(html.isNotEmpty() && html.size <= 8 * 1024 * 1024)
        return cbor.decodeFromByteArray(decodeDetailsNative(html))
    }

    override fun decodePage(html: ByteArray): GalleryImagePage {
        require(html.isNotEmpty() && html.size <= 8 * 1024 * 1024)
        return cbor.decodeFromByteArray(decodePageNative(html))
    }
}
