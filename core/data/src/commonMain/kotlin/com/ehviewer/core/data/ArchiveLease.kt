@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** Do not remove a backing file until every already admitted native call has ended. */
internal class ArchiveLease(private val document: ArchiveDocument) : ArchiveDocument {
    private data class State(val closed: Boolean = false, val calls: Int = 0)
    private val state = AtomicReference(State())
    private val releasing = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val releaseFailed = AtomicBoolean(false)
    val isReleased: Boolean get() = released.load()
    val isReleaseFailed: Boolean get() = releaseFailed.load()
    override val pageCount = document.pageCount
    override val needsPassword = document.needsPassword

    private fun <T> call(block: () -> T): T {
        while (true) {
            val before = state.load()
            if (before.closed) throw ArchiveException(ArchiveFailure.CLOSED)
            if (state.compareAndSet(before, before.copy(calls = before.calls + 1))) break
        }
        try {
            return block()
        } finally {
            while (true) {
                val before = state.load()
                val after = before.copy(calls = before.calls - 1)
                if (state.compareAndSet(before, after)) {
                    if (after.closed && after.calls == 0) release()
                    break
                }
            }
        }
    }

    private fun release() {
        if (releasing.compareAndSet(false, true)) {
            try {
                document.close()
                released.store(true)
            } catch (failure: Throwable) {
                releaseFailed.store(true)
                throw failure
            }
        }
    }

    override fun read(index: Int): ByteArray = call { document.read(index) }
    override fun extension(index: Int): String = call { document.extension(index) }
    override fun providePassword(password: String): Boolean = call { document.providePassword(password) }

    override fun close() {
        while (true) {
            val before = state.load()
            if (before.closed) return
            if (state.compareAndSet(before, before.copy(closed = true))) {
                if (before.calls == 0) release()
                return
            }
        }
    }
}
