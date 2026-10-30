package com.ehviewer.core.network.gallery

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray

/** The application loads its bundled original Rust JNI library before creating this decoder. */
object RustGalleryDecoder : GalleryListDecoder {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    override fun decode(html: ByteArray): GallerySearchResult {
        require(html.isNotEmpty() && html.size <= 8 * 1024 * 1024)
        return cbor.decodeFromByteArray(decodeNative(html))
    }

    private external fun decodeNative(html: ByteArray): ByteArray
}
