@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GallerySearchResult
import com.ehviewer.core.network.session.SessionStorageException
import com.ehviewer.core.network.session.SessionVault
import com.hippo.ehviewer.rust.capi.EHV_BUFFER_TOO_SMALL
import com.hippo.ehviewer.rust.capi.EHV_NO_SESSION
import com.hippo.ehviewer.rust.capi.EHV_OK
import com.hippo.ehviewer.rust.capi.ehv_buffer_copy
import com.hippo.ehviewer.rust.capi.ehv_buffer_free
import com.hippo.ehviewer.rust.capi.ehv_parse_gallery_detail
import com.hippo.ehviewer.rust.capi.ehv_parse_gallery_list
import com.hippo.ehviewer.rust.capi.ehv_parse_gallery_page
import com.hippo.ehviewer.rust.capi.ehv_session_clear
import com.hippo.ehviewer.rust.capi.ehv_session_read
import com.hippo.ehviewer.rust.capi.ehv_session_write
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray

private fun copyOwned(handle: ULong, maximum: Int): ByteArray = memScoped {
    require(handle != 0uL)
    try {
        val count = alloc<ULongVar>()
        check(ehv_buffer_copy(handle, null, 0u, count.ptr) == EHV_BUFFER_TOO_SMALL)
        check(count.value in 1uL..maximum.toULong())
        val bytes = allocArray<UByteVar>(count.value.toLong())
        val expected = count.value
        check(ehv_buffer_copy(handle, bytes, expected, count.ptr) == EHV_OK && count.value == expected)
        bytes.reinterpret<ByteVar>().readBytes(expected.toInt())
    } finally {
        check(ehv_buffer_free(handle) == EHV_OK)
    }
}

object NativeGalleryDecoder : GalleryDocumentDecoder {
    private val cbor = Cbor { ignoreUnknownKeys = true }
    override fun decode(html: ByteArray): GallerySearchResult = cbor.decodeFromByteArray(parse(html, 0))
    override fun decodeDetails(html: ByteArray): GalleryDetail = cbor.decodeFromByteArray(parse(html, 1))
    override fun decodePage(html: ByteArray): GalleryImagePage = cbor.decodeFromByteArray(parse(html, 2))

    private fun parse(html: ByteArray, kind: Int): ByteArray = memScoped {
        require(html.isNotEmpty() && html.size <= 8 * 1024 * 1024)
        val handle = alloc<ULongVar>()
        html.usePinned { input ->
            val status = when (kind) {
                0 -> ehv_parse_gallery_list(input.addressOf(0).reinterpret(), html.size.convert(), handle.ptr)
                1 -> ehv_parse_gallery_detail(input.addressOf(0).reinterpret(), html.size.convert(), handle.ptr)
                else -> ehv_parse_gallery_page(input.addressOf(0).reinterpret(), html.size.convert(), handle.ptr)
            }
            check(status == EHV_OK) { "Invalid gallery response" }
        }
        copyOwned(handle.value, 8 * 1024 * 1024)
    }
}

/** The C ABI stores only the master key in iOS Keychain and an authenticated snapshot in the sandbox. */
class AppleSessionVault(private val directory: String) : SessionVault {
    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        val path = directory.encodeToByteArray()
        memScoped {
            val handle = alloc<ULongVar>()
            val status = path.usePinned { ehv_session_read(it.addressOf(0).reinterpret(), path.size.convert(), handle.ptr) }
            when (status) {
                EHV_NO_SESSION -> null
                EHV_OK -> copyOwned(handle.value, 256 * 1024)
                else -> throw SessionStorageException()
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val path = directory.encodeToByteArray()
        path.usePinned { root ->
            bytes.usePinned { input ->
                val status = ehv_session_write(root.addressOf(0).reinterpret(), path.size.convert(), if (bytes.isEmpty()) null else input.addressOf(0).reinterpret(), bytes.size.convert())
                if (status != EHV_OK) throw SessionStorageException()
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        val path = directory.encodeToByteArray()
        path.usePinned { if (ehv_session_clear(it.addressOf(0).reinterpret(), path.size.convert()) != EHV_OK) throw SessionStorageException() }
    }
}
