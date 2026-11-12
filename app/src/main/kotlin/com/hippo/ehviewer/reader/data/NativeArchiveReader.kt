package com.hippo.ehviewer.reader.data

import android.os.Build
import com.hippo.ehviewer.jni.archiveSessionCopyTo
import com.hippo.ehviewer.jni.archiveSessionExtension
import com.hippo.ehviewer.jni.archiveSessionNeedsPassword
import com.hippo.ehviewer.jni.archiveSessionPageCount
import com.hippo.ehviewer.jni.archiveSessionProvidePassword
import com.hippo.ehviewer.jni.archiveSessionRead
import com.hippo.ehviewer.jni.closeArchiveSession
import com.hippo.ehviewer.jni.openArchiveSession
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.reader.ArchivePage
import com.hippo.ehviewer.reader.ArchiveReader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Reads can overlap; password changes and close wait for this reader's in-flight calls. */
class NativeArchiveReader private constructor(
    private val backend: ArchiveBackend,
    private val handle: Long,
    override val pageCount: Int,
) : ArchiveReader {
    private val lifecycle = ReentrantReadWriteLock(true)
    private var closed = false

    override val needsPassword: Boolean
        get() = lifecycle.read { active { backend.needsPassword(handle) } }

    override fun extension(index: Int) = page(index) { backend.extension(handle, index) }

    override fun providePassword(password: String) = lifecycle.write { active { backend.providePassword(handle, password) } }

    override fun copyTo(index: Int, outputFd: Int) = page(index) { backend.copyTo(handle, index, outputFd) }

    override fun read(index: Int): ArchivePage = page(index) {
        val data = checkNotNull(backend.read(handle, index)) { "Extract archive content $index failed!" }
        check(data.isDirect) { "Expected a direct archive buffer" }
        object : ArchivePage {
            private val released = AtomicBoolean()
            override val buffer = data
            override fun close() {
                if (released.compareAndSet(false, true)) backend.release(data)
            }
        }
    }

    override fun close() = lifecycle.write {
        if (closed) return@write
        closed = true
        backend.close(handle)
    }

    private inline fun <T> active(block: () -> T): T {
        check(!closed) { "Archive reader is closed" }
        return block()
    }

    private inline fun <T> page(index: Int, block: () -> T): T = lifecycle.read {
        active {
            require(index in 0 until pageCount) { "Invalid archive page index: $index" }
            block()
        }
    }

    companion object {
        private val factory = Factory(JniArchiveBackend)

        fun open(fd: Int, size: Long, sortEntries: Boolean): ArchiveReader = factory.open(fd, size, sortEntries)
    }

    internal class Factory(private val backend: ArchiveBackend) {
        fun open(fd: Int, size: Long, sortEntries: Boolean): ArchiveReader {
            val handle = backend.open(fd, size, sortEntries)
            check(handle > 0) { "Unable to open archive" }
            try {
                val count = backend.pageCount(handle)
                check(count > 0) { "Archive has no content!" }
                return NativeArchiveReader(backend, handle, count)
            } catch (error: Throwable) {
                runCatching { backend.close(handle) }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }
}

internal interface ArchiveBackend {
    fun open(fd: Int, size: Long, sortEntries: Boolean): Long
    fun pageCount(handle: Long): Int
    fun close(handle: Long)
    fun needsPassword(handle: Long): Boolean
    fun providePassword(handle: Long, password: String): Boolean
    fun extension(handle: Long, index: Int): String
    fun read(handle: Long, index: Int): ByteBuffer?
    fun copyTo(handle: Long, index: Int, outputFd: Int): Boolean
    fun release(buffer: ByteBuffer)
}

private object JniArchiveBackend : ArchiveBackend {
    override fun open(fd: Int, size: Long, sortEntries: Boolean) = openArchiveSession(fd, size, sortEntries)
    override fun pageCount(handle: Long) = archiveSessionPageCount(handle)
    override fun close(handle: Long) = closeArchiveSession(handle)
    override fun needsPassword(handle: Long) = archiveSessionNeedsPassword(handle)
    override fun providePassword(handle: Long, password: String) = archiveSessionProvidePassword(handle, password)
    override fun extension(handle: Long, index: Int) = archiveSessionExtension(handle, index)
    override fun read(handle: Long, index: Int) = archiveSessionRead(handle, index, Build.VERSION.SDK_INT in 28..33)
    override fun copyTo(handle: Long, index: Int, outputFd: Int) = archiveSessionCopyTo(handle, index, outputFd)
    override fun release(buffer: ByteBuffer) = releaseByteBuffer(buffer)
}
