@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveDocumentFactory
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** Actual file/hash/CBOR/atomic operations. Controlled factories isolate storage failures;
 * real Rust format/password/pixel execution is separately required in packaged regressions. */
class FileArchiveLibraryTest {
    private val files = FileSystem.SYSTEM

    private class Factory : ArchiveDocumentFactory {
        val live = AtomicInt(0)
        var onOpen: () -> Unit = {}
        var onRead: () -> Unit = {}
        var onClose: () -> Unit = {}
        override fun open(path: String): ArchiveDocument {
            val data = FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
            live.addAndFetch(1)
            onOpen()
            return object : ArchiveDocument {
                private var closed = false
                override val pageCount = 2
                override val needsPassword = false
                override fun extension(index: Int) = "png"
                override fun providePassword(password: String) = true
                override fun read(index: Int): ByteArray {
                    check(!closed)
                    onRead()
                    return data.copyOf()
                }
                override fun close() {
                    if (!closed) {
                        closed = true
                        live.addAndFetch(-1)
                        onClose()
                    }
                }
            }
        }
    }

    @Test fun aFailedCloseStillDrainsAnotherAdmittedReadBeforeReportingSanitizedFailure() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-close-books").toPath()
        val factory = Factory()
        val library = FileArchiveLibrary(root, factory)
        val releaseRead = CompletableDeferred<Unit>()
        try {
            files.write(input) { writeUtf8("First owned input") }
            val first = library.importFile(input.toString(), "First")
            files.write(input) { writeUtf8("Second owned input") }
            val second = library.importFile(input.toString(), "Second")
            val one = library.open(first)
            val two = library.open(second)
            val readStarted = CompletableDeferred<Unit>()
            factory.onRead = {
                readStarted.complete(Unit)
                runBlocking { releaseRead.await() }
            }
            val reading = async(Dispatchers.Default) { two.read(0) }
            readStarted.await()
            val attempts = AtomicInt(0)
            val firstCloseFailed = CompletableDeferred<Unit>()
            factory.onClose = {
                if (attempts.addAndFetch(1) == 1) {
                    firstCloseFailed.complete(Unit)
                    throw IOException("private-owned-close-path")
                }
            }
            val closing = async { runCatching { library.close() } }
            firstCloseFailed.await()
            assertFalse(closing.isCompleted)
            releaseRead.complete(Unit)
            assertEquals("Second owned input", reading.await().decodeToString())
            val error = assertFailsWith<ArchiveException> { closing.await().getOrThrow() }
            assertEquals(ArchiveFailure.STORAGE, error.failure)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private-owned"))
            assertEquals(2, attempts.load())
            assertEquals(0, factory.live.load())
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { one.read(0) }.failure)
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { two.read(0) }.failure)
            assertEquals("Second owned input", files.read(input) { readUtf8() })
            assertTrue(files.exists(root / first.id / "archive.bin"))
            assertTrue(files.exists(root / second.id / "archive.bin"))
        } finally {
            releaseRead.complete(Unit)
            factory.onClose = {}
            // A failed owner remains explicitly failed even after all other owners were drained.
            runCatching { library.close() }
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun cancelledCallerStillClosesAllAdmittedDocumentsBeforeReturning() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-cancel-close-books").toPath()
        val factory = Factory()
        val library = FileArchiveLibrary(root, factory)
        files.write(input) { writeUtf8("Owned input") }
        try {
            val book = library.importFile(input.toString(), "Owned")
            val document = library.open(book)
            val caller = Job()
            assertFailsWith<CancellationException> {
                withContext(caller) {
                    currentCoroutineContext().cancel()
                    library.close()
                }
            }
            assertEquals(0, factory.live.load())
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { document.read(0) }.failure)
            assertEquals("Owned input", files.read(input) { readUtf8() })
        } finally {
            library.close()
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun privateCopiesReopenDeduplicateAndRemoveWithoutChangingTheInput() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-books").toPath()
        val factory = Factory()
        var library = FileArchiveLibrary(root, factory)
        val bytes = byteArrayOf(1, 2, 3, 4)
        files.write(input) { write(bytes) }
        try {
            val book = library.importFile(input.toString(), "Local 日本語")
            assertEquals(64, book.id.length)
            assertEquals(0, factory.live.load())
            library.open(book).use { assertContentEquals(bytes, it.read(0)) }
            library.close()
            library = FileArchiveLibrary(root, factory)
            assertEquals(listOf(book), library.recent())
            assertEquals(book, library.importFile(input.toString(), "Keep the existing title"))
            library.remove(book)
            assertTrue(library.recent().isEmpty())
            assertContentEquals(bytes, files.read(input) { readByteArray() })
            assertFalse(files.exists(root / book.id))
            assertEquals(0, factory.live.load())
        } finally {
            library.close()
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun failedDirectoryPublicationKeepsInputAndLeavesNoPublishedBookOrStagingFile() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-books").toPath()
        files.write(input) { writeUtf8("original bytes") }
        val failing = object : ForwardingFileSystem(files) {
            override fun atomicMove(source: Path, target: Path) = throw IOException("test-only-private-path")
        }
        val factory = Factory()
        val library = FileArchiveLibrary(root, factory, failing)
        try {
            val error = assertFailsWith<ArchiveException> { library.importFile(input.toString(), "Owned") }
            assertEquals(ArchiveFailure.STORAGE, error.failure)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private-path"))
            assertTrue(library.recent().isEmpty())
            assertTrue(files.list(root).isEmpty())
            assertEquals("original bytes", files.read(input) { readUtf8() })
            assertEquals(0, factory.live.load())
        } finally {
            library.close()
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun cancellationDuringNativeValidationAndLateOpenClosesOwnersAndPreservesCommittedBooks() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-books").toPath()
        files.write(input) { writeUtf8("immutable input") }
        val factory = Factory()
        val library = FileArchiveLibrary(root, factory)
        try {
            val importing = Job()
            factory.onOpen = { importing.cancel() }
            assertFailsWith<CancellationException> { withContext(importing) { library.importFile(input.toString(), "Cancelled") } }
            assertTrue(library.recent().isEmpty())
            assertTrue(files.list(root).isEmpty())
            assertEquals(0, factory.live.load())
            factory.onOpen = {}
            val book = library.importFile(input.toString(), "Committed")
            val opening = Job()
            factory.onOpen = { opening.cancel() }
            assertFailsWith<CancellationException> { withContext(opening) { library.open(book) } }
            assertEquals(0, factory.live.load())
            assertEquals(listOf(book), library.recent())
            assertEquals("immutable input", files.read(input) { readUtf8() })
        } finally {
            library.close()
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun sameLengthCorruptionIsRejectedBeforeNativeOpenAndItsBytesArePreserved() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-books").toPath()
        files.write(input) { write(byteArrayOf(1, 2, 3)) }
        val factory = Factory()
        val library = FileArchiveLibrary(root, factory)
        try {
            val book = library.importFile(input.toString(), "Owned")
            val corrupted = byteArrayOf(9, 9, 9)
            files.write(root / book.id / "archive.bin") { write(corrupted) }
            val error = assertFailsWith<ArchiveException> { library.open(book) }
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, error.failure)
            assertEquals(0, factory.live.load())
            assertContentEquals(corrupted, files.read(root / book.id / "archive.bin") { readByteArray() })
            assertEquals(listOf(book), library.recent())
        } finally {
            library.close()
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun closingDuringAnAdmittedReadDefersNativeReleaseAndPreventsBackingFileDeletion() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-books").toPath()
        files.write(input) { writeUtf8("owned bytes") }
        val factory = Factory()
        val library = FileArchiveLibrary(root, factory)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val book = library.importFile(input.toString(), "Owned")
            val document = library.open(book)
            factory.onRead = {
                started.complete(Unit)
                runBlocking { release.await() }
            }
            val reading = async(Dispatchers.Default) { document.read(0) }
            try {
                started.await()
                document.close()
                assertEquals(1, factory.live.load())
                assertEquals(ArchiveFailure.IN_USE, assertFailsWith<ArchiveException> { library.remove(book) }.failure)
                assertTrue(files.exists(root / book.id / "archive.bin"))
            } finally {
                release.complete(Unit)
            }
            assertEquals("owned bytes", reading.await().decodeToString())
            assertEquals(0, factory.live.load())
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { document.read(0) }.failure)
            library.remove(book)
            assertTrue(library.recent().isEmpty())
        } finally {
            release.complete(Unit)
            library.close()
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun failedPayloadDeletionKeepsMetadataAndTheOriginalLocalCopy() = runTest {
        val owned = ownedDatabaseFile()
        val input = owned.path.toPath()
        val root = (owned.path + "-books").toPath()
        files.write(input) { writeUtf8("owned bytes") }
        var failDeletion = false
        val failing = object : ForwardingFileSystem(files) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (failDeletion && path.name == "archive.bin") throw IOException("owned deletion failure")
                super.delete(path, mustExist)
            }
        }
        val library = FileArchiveLibrary(root, Factory(), failing)
        try {
            val book = library.importFile(input.toString(), "Owned")
            failDeletion = true
            assertEquals(ArchiveFailure.STORAGE, assertFailsWith<ArchiveException> { library.remove(book) }.failure)
            assertEquals(listOf(book), library.recent())
            assertEquals("owned bytes", files.read(root / book.id / "archive.bin") { readUtf8() })
        } finally {
            library.close()
            files.deleteRecursively(root, mustExist = false)
            owned.cleanup()
        }
    }
}
