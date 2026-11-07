@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveDocumentFactory
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.archiveFailure
import com.hippo.ehviewer.rust.capi.EHV_OK
import com.hippo.ehviewer.rust.capi.ehv_archive_close
import com.hippo.ehviewer.rust.capi.ehv_archive_extension
import com.hippo.ehviewer.rust.capi.ehv_archive_info
import com.hippo.ehviewer.rust.capi.ehv_archive_open
import com.hippo.ehviewer.rust.capi.ehv_archive_password
import com.hippo.ehviewer.rust.capi.ehv_archive_read
import kotlin.concurrent.atomics.AtomicLong
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/** Actual Kotlin/Native calls into the same archive registry; no Swift or separate archive engine. */
object AppleArchiveFactory : ArchiveDocumentFactory {
    override fun open(path: String): ArchiveDocument = memScoped {
        val bytes = path.encodeToByteArray()
        require(bytes.size in 1..65536)
        val output = alloc<ULongVar>()
        val status = bytes.usePinned { ehv_archive_open(it.addressOf(0).reinterpret(), bytes.size.convert(), output.ptr) }
        if (status != EHV_OK) archiveFailure(status)
        val handle = output.value
        try {
            val pages = alloc<UIntVar>()
            val password = alloc<UByteVar>()
            val info = ehv_archive_info(handle, pages.ptr, password.ptr)
            if (info != EHV_OK) archiveFailure(info)
            check(pages.value in 1u..100_000u)
            Document(handle, pages.value.toInt(), password.value == 1u.toUByte())
        } catch (failure: Throwable) {
            ehv_archive_close(handle)
            throw failure
        }
    }

    private class Document(handle: ULong, override val pageCount: Int, override val needsPassword: Boolean) : ArchiveDocument {
        private val owned = AtomicLong(handle.toLong())
        private fun active(): ULong = owned.load().also { if (it == 0L) throw ArchiveException(ArchiveFailure.CLOSED) }.toULong()

        private fun page(index: Int, extension: Boolean): ByteArray = memScoped {
            require(index in 0 until pageCount)
            val output = alloc<ULongVar>()
            val status = if (extension) ehv_archive_extension(active(), index.toUInt(), output.ptr) else ehv_archive_read(active(), index.toUInt(), output.ptr)
            if (status != EHV_OK) archiveFailure(status)
            copyOwned(output.value, if (extension) 32 else 32 * 1024 * 1024).also { active() }
        }

        override fun read(index: Int): ByteArray = page(index, false)
        override fun extension(index: Int): String = page(index, true).decodeToString(throwOnInvalidSequence = true)

        override fun providePassword(password: String): Boolean {
            val bytes = password.encodeToByteArray()
            require(bytes.size <= 4096)
            try {
                return memScoped {
                    val accepted = alloc<UByteVar>()
                    val status = bytes.usePinned { ehv_archive_password(active(), if (bytes.isEmpty()) null else it.addressOf(0).reinterpret(), bytes.size.convert(), accepted.ptr) }
                    if (status != EHV_OK) archiveFailure(status)
                    active()
                    accepted.value == 1u.toUByte()
                }
            } finally {
                bytes.fill(0)
            }
        }

        override fun close() {
            val handle = owned.exchange(0L)
            if (handle != 0L) {
                val status = ehv_archive_close(handle.toULong())
                if (status != EHV_OK) archiveFailure(status)
            }
        }
    }
}
