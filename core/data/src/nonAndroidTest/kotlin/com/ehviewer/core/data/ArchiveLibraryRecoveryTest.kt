package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveDocumentFactory
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source

/** Real private files and CBOR; fault injection affects only one owned metadata read. */
class ArchiveLibraryRecoveryTest {
    private val files = FileSystem.SYSTEM
    private val image = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
    private val factory = ArchiveDocumentFactory { error("Directory reading must not open compressed input") }

    private suspend fun owned(block: suspend (FileArchiveLibrary, Path, Path) -> Unit) {
        val owned = ownedDatabaseFile()
        val input = (owned.path + "-recovery-input").toPath()
        val root = (owned.path + "-recovery-books").toPath()
        files.createDirectories(input)
        files.write(input / "1.png") { write(image) }
        val library = FileArchiveLibrary(root, factory)
        try {
            block(library, input, root)
        } finally {
            library.close()
            files.deleteRecursively(root, mustExist = false)
            files.deleteRecursively(input, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun damagedMetadataDoesNotHideHealthyBooksAndRestoredBytesBecomeVisibleAgain() = runTest {
        owned { library, input, root ->
            val first = library.importDirectory(input.toString(), "First")
            files.write(input / "2.png") { write(image) }
            val second = library.importDirectory(input.toString(), "Second")
            val metadata = root / first.id / "book.cbor"
            val original = files.read(metadata) { readByteArray() }
            val damaged = byteArrayOf(0xff.toByte(), 0x01)
            files.write(metadata) { write(damaged) }
            val snapshot = library.inspect()
            assertEquals(listOf(second), snapshot.books)
            assertEquals(1, snapshot.unavailableCopies)
            library.open(second).use { assertContentEquals(image, it.read(1)) }
            assertContentEquals(damaged, files.read(metadata) { readByteArray() })
            assertContentEquals(image, files.read(input / "1.png") { readByteArray() })
            // The strict API still reports corruption rather than silently changing its contract.
            assertEquals(ArchiveFailure.STORAGE, assertFailsWith<ArchiveException> { library.recent() }.failure)
            files.write(metadata) { write(original) }
            assertEquals(listOf(first, second), library.inspect().books)
            assertEquals(0, library.inspect().unavailableCopies)
            library.open(first).use { assertContentEquals(image, it.read(0)) }
        }
    }

    @Test fun malformedAndMissingHeadersAreCountedWithoutDeletingAnyCopies() = runTest {
        owned { library, input, root ->
            val healthy = library.importDirectory(input.toString(), "Healthy")
            val missing = root / "a".repeat(64)
            files.createDirectory(missing)
            files.write(missing / "book.cbor") { writeUtf8("damaged owned metadata") }
            val third = root / "b".repeat(64)
            files.createDirectory(third)
            files.write(third / "keep.bin") { writeUtf8("preserve unknown bytes") }
            // Unknown staging names are retained and are never counted as published books.
            val staging = root / ".import-owned-interrupted"
            files.createDirectory(staging)
            files.write(staging / "archive.bin") { writeUtf8("interrupted owned input") }
            val snapshot = library.inspect()
            assertEquals(listOf(healthy), snapshot.books)
            assertEquals(2, snapshot.unavailableCopies)
            assertEquals("damaged owned metadata", files.read(missing / "book.cbor") { readUtf8() })
            assertEquals("preserve unknown bytes", files.read(third / "keep.bin") { readUtf8() })
            assertEquals("interrupted owned input", files.read(staging / "archive.bin") { readUtf8() })
            assertEquals(4, files.list(root).size)
        }
    }

    @Test fun perCopyStorageFailureIsVisibleButRootFailureCannotPretendTheLibraryIsEmpty() = runTest {
        owned { library, input, root ->
            val book = library.importDirectory(input.toString(), "Owned")
            val bytes = files.read(root / book.id / "book.cbor") { readByteArray() }
            var rootFailure = false
            val failing = object : ForwardingFileSystem(files) {
                override fun source(file: Path): Source {
                    if (file == root / book.id / "book.cbor") throw IOException("private-owned-path")
                    return super.source(file)
                }
                override fun list(dir: Path): List<Path> {
                    if (rootFailure && dir == root) throw IOException("private-owned-root")
                    return super.list(dir)
                }
            }
            val inspected = FileArchiveLibrary(root, factory, failing)
            try {
                assertEquals(1, inspected.inspect().unavailableCopies)
                assertEquals(emptyList(), inspected.inspect().books)
                rootFailure = true
                val error = assertFailsWith<ArchiveException> { inspected.inspect() }
                assertEquals(ArchiveFailure.STORAGE, error.failure)
                assertNull(error.cause)
                assertFalse(error.toString().contains("private-owned"))
                assertContentEquals(bytes, files.read(root / book.id / "book.cbor") { readByteArray() })
            } finally {
                inspected.close()
            }
        }
    }

    @Test fun cancelledInspectionPropagatesCancellationAndPreservesAllBytes() = runTest {
        owned { library, input, root ->
            val book = library.importDirectory(input.toString(), "Owned")
            val job = Job()
            val cancelling = object : ForwardingFileSystem(files) {
                override fun source(file: Path): Source {
                    if (file == root / book.id / "book.cbor") {
                        job.cancel()
                        throw CancellationException("owned inspection cancellation")
                    }
                    return super.source(file)
                }
            }
            val inspected = FileArchiveLibrary(root, factory, cancelling)
            try {
                assertFailsWith<CancellationException> { withContext(job) { inspected.inspect() } }
                assertEquals(listOf(book), library.recent())
                assertContentEquals(image, files.read(input / "1.png") { readByteArray() })
            } finally {
                inspected.close()
            }
        }
    }
}
