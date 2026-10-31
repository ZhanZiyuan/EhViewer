@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.ehviewer.core.files

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class FilesystemTest {
    private inline fun owned(block: (Path) -> Unit) {
        val directory = SystemTemporaryDirectory.toString().toPath().resolve("ehviewer-files-${Uuid.random()}")
        FileSystem.SYSTEM.createDirectories(directory, mustCreate = true)
        try {
            block(directory)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = true)
        }
    }

    @Test fun realStreamsPreserveUnicodeExactBytesAndTruncation() = owned { directory ->
        val file = directory.resolve("资料 📚 Cafe\u0301.bin")
        val bytes = ByteArray(196_733) { (it % 251).toByte() }
        file.write { write(bytes) }
        assertContentEquals(bytes, file.read { readByteArray() })
        file.write { writeString("short 📚") }
        assertContentEquals("short 📚".encodeToByteArray(), file.read { readByteArray() })
        assertTrue(file.isFile)
        assertEquals("short 📚".encodeToByteArray().size.toLong(), file.metadataOrNull()?.size)
    }

    @Test fun metadataListCopyMoveAndDeletionUseTheSameRealFilesystem() = owned { directory ->
        val nested = directory.resolve("子 目录")
        nested.mkdirs()
        assertTrue(nested.isDirectory)
        val source = nested.resolve("original.bin")
        source.write { writeString("fixture") }
        val copied = nested.resolve("copy.bin")
        source sendTo copied
        assertContentEquals(source.read { readByteArray() }, copied.read { readByteArray() })
        val moved = nested.resolve("moved.bin")
        copied moveTo moved
        assertFalse(copied.exists())
        assertEquals(moved, nested.find("moved.bin"))
        assertEquals(setOf(source, moved), nested.list().toSet())
        nested.deleteContent()
        assertTrue(nested.list().isEmpty())
        nested.delete()
        assertFalse(nested.exists())
    }

    @Test fun missingSourcesAndDirectorySinksExposeRealIoErrors() = owned { directory ->
        assertFailsWith<IOException> { directory.resolve("missing.bin").read { readByteArray() } }
        assertFailsWith<IOException> { directory.write { writeString("invalid directory sink") } }
    }

    @Test fun callbackFailuresCloseBothStreamsAndPreserveTheOriginalFailure() = owned { directory ->
        val file = directory.resolve("callbacks.bin")
        file.write { writeString("fixture") }
        val expected = IllegalStateException("fixture callback failure")
        lateinit var source: Source
        assertSame(
            expected,
            assertFailsWith<IllegalStateException> {
                file.read {
                    source = this
                    throw expected
                }
            },
        )
        assertFailsWith<IllegalStateException> { source.readByte() }
        lateinit var sink: Sink
        assertSame(
            expected,
            assertFailsWith<IllegalStateException> {
                file.write {
                    sink = this
                    throw expected
                }
            },
        )
        assertFailsWith<IllegalStateException> { sink.writeByte(1) }
    }

    @Test fun realCoroutineCancellationClosesTheStreamAndPropagates() = owned { directory ->
        val file = directory.resolve("cancelled.bin")
        file.write { writeString("retained bytes") }
        lateinit var source: Source
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                file.read {
                    source = this
                    currentCoroutineContext().cancel()
                    currentCoroutineContext().ensureActive()
                }
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertFailsWith<IllegalStateException> { source.readByte() }
        assertContentEquals("retained bytes".encodeToByteArray(), file.read { readByteArray() })
    }
}
