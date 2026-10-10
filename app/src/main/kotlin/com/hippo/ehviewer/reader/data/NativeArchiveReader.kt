package com.hippo.ehviewer.reader.data

import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.extractToFd
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.reader.ArchivePage
import com.hippo.ehviewer.reader.ArchiveReader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Keep the unchanged singleton JNI API behind an explicitly owned reader. */
class NativeArchiveReader private constructor(
    private val backend: ArchiveBackend,
    private val ownership: AtomicBoolean,
    override val pageCount: Int,
) : ArchiveReader {
    private var closed = false

    override val needsPassword: Boolean
        @Synchronized get() = active { backend.needsPassword() }

    @Synchronized
    override fun extension(index: Int) = page(index) { backend.extension(index) }

    @Synchronized
    override fun providePassword(password: String) = active { backend.providePassword(password) }

    @Synchronized
    override fun copyTo(index: Int, outputFd: Int) = page(index) { backend.copyTo(index, outputFd) }

    @Synchronized
    override fun read(index: Int): ArchivePage = page(index) {
        val data = checkNotNull(backend.read(index)) { "Extract archive content $index failed!" }
        check(data.isDirect) { "Expected a direct archive buffer" }
        object : ArchivePage {
            private val released = AtomicBoolean()
            override val buffer = data
            override fun close() {
                if (released.compareAndSet(false, true)) backend.release(data)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            backend.close()
        } finally {
            ownership.set(false)
        }
    }

    private inline fun <T> active(block: () -> T): T {
        check(!closed) { "Archive reader is closed" }
        return block()
    }

    private inline fun <T> page(index: Int, block: () -> T): T = active {
        require(index in 0 until pageCount) { "Invalid archive page index: $index" }
        block()
    }

    companion object {
        private val factory = Factory(JniArchiveBackend)

        fun open(fd: Int, size: Long, sortEntries: Boolean): ArchiveReader = factory.open(fd, size, sortEntries)
    }

    internal class Factory(private val backend: ArchiveBackend) {
        private val ownership = AtomicBoolean()

        fun open(fd: Int, size: Long, sortEntries: Boolean): ArchiveReader {
            check(ownership.compareAndSet(false, true)) { "Another archive reader is already open" }
            try {
                val count = backend.open(fd, size, sortEntries)
                if (count <= 0) {
                    backend.close()
                    error("Archive has no content!")
                }
                return NativeArchiveReader(backend, ownership, count)
            } catch (error: Throwable) {
                ownership.set(false)
                throw error
            }
        }
    }
}

internal interface ArchiveBackend {
    fun open(fd: Int, size: Long, sortEntries: Boolean): Int
    fun close()
    fun needsPassword(): Boolean
    fun providePassword(password: String): Boolean
    fun extension(index: Int): String
    fun read(index: Int): ByteBuffer?
    fun copyTo(index: Int, outputFd: Int): Boolean
    fun release(buffer: ByteBuffer)
}

private object JniArchiveBackend : ArchiveBackend {
    override fun open(fd: Int, size: Long, sortEntries: Boolean) = openArchive(fd, size, sortEntries)
    override fun close() = closeArchive()
    override fun needsPassword() = needPassword()
    override fun providePassword(password: String) = com.hippo.ehviewer.jni.providePassword(password)
    override fun extension(index: Int) = getExtension(index)
    override fun read(index: Int) = extractToByteBuffer(index)
    override fun copyTo(index: Int, outputFd: Int) = extractToFd(index, outputFd)
    override fun release(buffer: ByteBuffer) = releaseByteBuffer(buffer)
}
