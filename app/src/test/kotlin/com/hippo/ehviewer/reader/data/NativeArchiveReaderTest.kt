package com.hippo.ehviewer.reader.data

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeArchiveReaderTest {
    private class FakeBackend : ArchiveBackend {
        var count = 3
        var opens = 0
        var closes = 0
        var releases = 0
        var failOpen = false
        var failClose = false
        var password = ""
        var copied = -1
        val bytes = ByteBuffer.allocateDirect(3).apply {
            put(byteArrayOf(1, 2, 3))
            flip()
        }
        override fun open(fd: Int, size: Long, sortEntries: Boolean): Int {
            opens++
            check(!failOpen)
            return count
        }
        override fun close() {
            closes++
            check(!failClose)
        }
        override fun needsPassword() = true
        override fun providePassword(password: String): Boolean {
            this.password = password
            return password == "密码🔑"
        }
        override fun extension(index: Int) = "jpg"
        override fun read(index: Int) = bytes
        override fun copyTo(index: Int, outputFd: Int): Boolean {
            copied = outputFd
            return true
        }
        override fun release(buffer: ByteBuffer) {
            assertSame(bytes, buffer)
            releases++
        }
    }

    @Test
    fun exposesCatalogAndForwardsPasswordAndOutputDescriptor() {
        val backend = FakeBackend()
        NativeArchiveReader.Factory(backend).open(7, 100, true).use { reader ->
            assertEquals(3, reader.pageCount)
            assertTrue(reader.needsPassword)
            assertEquals("jpg", reader.extension(2))
            assertFalse(reader.providePassword("wrong"))
            assertTrue(reader.providePassword("密码🔑"))
            assertEquals("密码🔑", backend.password)
            assertTrue(reader.copyTo(1, 42))
            assertEquals(42, backend.copied)
        }
        assertEquals(1, backend.closes)
    }

    @Test
    fun pageOwnershipSurvivesCloseAndReopenAndReleasesOnce() {
        val backend = FakeBackend()
        val factory = NativeArchiveReader.Factory(backend)
        val first = factory.open(7, 100, false)
        val page = first.read(0)
        first.close()
        factory.open(8, 100, true).use {
            first.close()
            assertEquals(1, backend.closes)
            assertEquals(1, page.buffer.get(0).toInt())
            page.close()
            page.close()
            assertEquals(1, backend.releases)
        }
        assertEquals(2, backend.closes)
    }

    @Test
    fun rejectsInvalidIndicesAndUseAfterClose() {
        val backend = FakeBackend()
        val reader = NativeArchiveReader.Factory(backend).open(7, 100, false)
        assertThrows(IllegalArgumentException::class.java) { reader.read(-1) }
        assertThrows(IllegalArgumentException::class.java) { reader.copyTo(3, 9) }
        reader.close()
        assertThrows(IllegalStateException::class.java) { reader.read(0) }
        assertThrows(IllegalStateException::class.java) { reader.providePassword("x") }
        assertThrows(IllegalStateException::class.java) { reader.extension(0) }
        assertThrows(IllegalStateException::class.java) { reader.needsPassword }
    }

    @Test
    fun rejectsOverlappingReadersWithoutReplacingTheActiveSession() {
        val backend = FakeBackend()
        val factory = NativeArchiveReader.Factory(backend)
        factory.open(7, 100, false).use {
            assertThrows(IllegalStateException::class.java) { factory.open(8, 100, true) }
            assertEquals(1, backend.opens)
            assertEquals(0, backend.closes)
        }
        factory.open(8, 100, true).close()
        assertEquals(2, backend.opens)
    }

    @Test
    fun failedOpenReleasesOwnershipForRetry() {
        val backend = FakeBackend().apply { failOpen = true }
        val factory = NativeArchiveReader.Factory(backend)
        assertThrows(IllegalStateException::class.java) { factory.open(7, 100, false) }
        backend.failOpen = false
        factory.open(7, 100, false).close()
    }

    @Test
    fun emptyArchiveIsClosedAndCanBeRetried() {
        val backend = FakeBackend().apply { count = 0 }
        val factory = NativeArchiveReader.Factory(backend)
        assertThrows(IllegalStateException::class.java) { factory.open(7, 100, false) }
        assertEquals(1, backend.closes)
        backend.count = 3
        factory.open(7, 100, false).close()
        assertEquals(2, backend.closes)
    }

    @Test
    fun closeFailureStillReleasesFactoryOwnership() {
        val backend = FakeBackend().apply { failClose = true }
        val factory = NativeArchiveReader.Factory(backend)
        val reader = factory.open(7, 100, false)
        assertThrows(IllegalStateException::class.java) { reader.close() }
        reader.close()
        backend.failClose = false
        factory.open(7, 100, false).close()
        assertEquals(2, backend.closes)
    }
}
