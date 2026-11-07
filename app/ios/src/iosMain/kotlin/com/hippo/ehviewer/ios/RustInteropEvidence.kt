@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hippo.ehviewer.ios

import com.hippo.ehviewer.rust.capi.EHV_BUFFER_TOO_SMALL
import com.hippo.ehviewer.rust.capi.EHV_INVALID_ARGUMENT
import com.hippo.ehviewer.rust.capi.EHV_INVALID_HANDLE
import com.hippo.ehviewer.rust.capi.EHV_OK
import com.hippo.ehviewer.rust.capi.ehv_abi_version
import com.hippo.ehviewer.rust.capi.ehv_active_buffers
import com.hippo.ehviewer.rust.capi.ehv_buffer_copy
import com.hippo.ehviewer.rust.capi.ehv_buffer_free
import com.hippo.ehviewer.rust.capi.ehv_hash_sha1
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

/** Measured M1 interop evidence; archive/image features still require separate implementation. */
data class RustInteropEvidence(val completed: Int, val live: ULong)

fun verifyRustInterop(): RustInteropEvidence = memScoped {
    check(ehv_abi_version() == 1u)
    val handle = alloc<ULongVar>()
    val written = alloc<ULongVar>()
    val count = alloc<ULongVar>()
    var completed = 0
    repeat(1000) {
        for ((text, expected) in listOf("" to "da39a3ee5e6b4b0d3255bfef95601890afd80709", "abc" to "a9993e364706816aba3e25717850c26c9cd0d89d")) {
            val input = text.encodeToByteArray()
            input.usePinned { pinned ->
                check(ehv_hash_sha1(if (input.isEmpty()) null else pinned.addressOf(0).reinterpret(), input.size.convert(), handle.ptr) == EHV_OK)
            }
            try {
                check(handle.value != 0uL)
                check(ehv_buffer_copy(handle.value, null, 0u, written.ptr) == EHV_BUFFER_TOO_SMALL)
                check(written.value == 40uL)
                val output = allocArray<UByteVar>(40)
                check(ehv_buffer_copy(handle.value, output, 40u, written.ptr) == EHV_OK)
                check(output.reinterpret<ByteVar>().readBytes(40).decodeToString() == expected)
            } finally {
                check(ehv_buffer_free(handle.value) == EHV_OK)
            }
            check(ehv_buffer_free(handle.value) == EHV_INVALID_HANDLE)
            completed++
        }
    }
    check(ehv_hash_sha1(null, 1u, handle.ptr) == EHV_INVALID_ARGUMENT && handle.value == 0uL)
    check(ehv_buffer_copy(0u, null, 0u, written.ptr) == EHV_INVALID_HANDLE)
    check(ehv_active_buffers(count.ptr) == EHV_OK && count.value == 0uL)
    RustInteropEvidence(completed, count.value)
}
