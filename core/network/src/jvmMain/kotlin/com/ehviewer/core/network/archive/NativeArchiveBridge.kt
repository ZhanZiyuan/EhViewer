package com.ehviewer.core.network.archive

import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.archiveFailure
import java.util.concurrent.atomic.AtomicLong

/** JVM/Android bridge to the same owned C ABI, without altering the original Android reader. */
object NativeArchiveBridge {
    /** Actual native diagnostics used by explicit lifecycle regressions. */
    fun activeResources(): Pair<Int, Int> = activeNative().let { ((it ushr 32).toInt()) to ((it and 0xffff_ffffL).toInt()) }

    /** The application owns loading Rust and keeping this private file immutable until close. */
    fun open(path: String): ArchiveDocument {
        val bytes = path.encodeToByteArray()
        require(bytes.size in 1..65536)
        val handle = openNative(bytes).also { if (it <= 0) archiveFailure((-it).toInt()) }
        try {
            val info = infoNative(handle).also { if (it < 0) archiveFailure((-it).toInt()) }
            val pages = (info and 0xffff_ffffL).toInt()
            check(pages in 1..100_000)
            return Document(handle, pages, info ushr 32 == 1L)
        } catch (failure: Throwable) {
            closeNative(handle)
            throw failure
        }
    }

    private class Document(handle: Long, override val pageCount: Int, override val needsPassword: Boolean) : ArchiveDocument {
        private val owned = AtomicLong(handle)
        private fun active() = owned.get().also { if (it == 0L) throw ArchiveException(ArchiveFailure.CLOSED) }
        private fun index(index: Int) = require(index in 0 until pageCount)

        override fun extension(index: Int): String {
            index(index)
            return copy(extensionNative(active(), index)).decodeToString(throwOnInvalidSequence = true)
        }

        override fun read(index: Int): ByteArray {
            index(index)
            return copy(pageNative(active(), index))
        }

        private fun copy(result: Long): ByteArray {
            if (result <= 0) archiveFailure((-result).toInt())
            // JNI owns the result until this call ends, including Java allocation failure.
            return copyAndFreeNative(result).also { active() }
        }

        override fun providePassword(password: String): Boolean {
            val bytes = password.encodeToByteArray()
            require(bytes.size <= 4096)
            try {
                val accepted = passwordNative(active(), bytes)
                if (accepted < 0) archiveFailure(-accepted)
                active()
                return accepted == 1
            } finally {
                bytes.fill(0)
            }
        }

        override fun close() {
            val handle = owned.getAndSet(0)
            if (handle != 0L) {
                val status = closeNative(handle)
                if (status != 0) archiveFailure(status)
            }
        }
    }

    private external fun openNative(path: ByteArray): Long
    private external fun infoNative(handle: Long): Long
    private external fun passwordNative(handle: Long, password: ByteArray): Int
    private external fun pageNative(handle: Long, index: Int): Long
    private external fun extensionNative(handle: Long, index: Int): Long
    private external fun copyAndFreeNative(handle: Long): ByteArray
    private external fun closeNative(handle: Long): Int
    private external fun activeNative(): Long
}
