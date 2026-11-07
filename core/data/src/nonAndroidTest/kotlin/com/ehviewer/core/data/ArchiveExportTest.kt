package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveDocument
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source

/** Real files and hashes; a controlled archive factory isolates storage from packaged Rust tests. */
class ArchiveExportTest {
    private val files = FileSystem.SYSTEM
    private val first = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
    private val second = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAYAAACZgbYnAAAADklEQVR4XmNg+A+CQAAAF/IF+w/LXpgAAAAASUVORK5CYII=")

    // Byte-identical owned two-image ZIP used by the shared packaged archive regression.
    private val zip = Base64.decode("UEsDBAoAAAgAAAAAIQDh2/GzRwAAAEcAAAAGAAkAMTAucG5nVVQFAAEAAAAAiVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAYAAACZgbYnAAAADklEQVR4XmNg+A+CQAAAF/IF+w/LXpgAAAAASUVORK5CYIJQSwMECgAACAAAAAAhANWroC9HAAAARwAAAAUACQAyLnBuZ1VUBQABAAAAAIlQTkcNChoKAAAADUlIRFIAAAACAAAAAQgGAAAA9CJ/igAAAA5JREFUeJxj+M/AAEL/AQ/5A/2FEZl2AAAAAElFTkSuQmCCUEsDBAoAAAgAAAAAIQDAI1YcHgAAAB4AAAAKAAkAUkVBRE1FLnR4dFVUBQABAAAAAEdlbmVyYXRlZCBFaFZpZXdlciB0ZXN0IGltYWdlc1BLAQIKAAoAAAgAAAAAIQDh2/GzRwAAAEcAAAAGAAkAAAAAAAAAAAAAAAAAAAAxMC5wbmdVVAUAAQAAAABQSwECCgAKAAAIAAAAACEA1augL0cAAABHAAAABQAJAAAAAAAAAAAAAAB0AAAAMi5wbmdVVAUAAQAAAABQSwECCgAKAAAIAAAAACEAwCNWHB4AAAAeAAAACgAJAAAAAAAAAAAAAADnAAAAUkVBRE1FLnR4dFVUBQABAAAAAFBLBQYAAAAAAwADALoAAAA2AQAAAAA=")
    private val factory = ArchiveDocumentFactory {
        object : ArchiveDocument {
            override val pageCount = 2
            override val needsPassword = false
            override fun extension(index: Int) = error("Export must not extract the archive")
            override fun read(index: Int): ByteArray = error("Export must preserve compressed bytes")
            override fun providePassword(password: String) = error("Export must not request a password")
            override fun close() = Unit
        }
    }

    private suspend fun owned(decorate: (FileSystem) -> FileSystem = { it }, block: suspend (FileArchiveLibrary, Path, Path, Path) -> Unit) {
        val owner = ownedDatabaseFile()
        val parent = (owner.path + "-export-owned").toPath()
        val input = parent / "input"
        val root = parent / "books"
        val output = parent / "output"
        files.createDirectories(input)
        files.createDirectories(output)
        val library = FileArchiveLibrary(root, factory, decorate(files))
        try {
            files.write(input / "2.png") { write(first) }
            files.write(input / "10.png") { write(second) }
            files.write(input / "comic.zip") { write(zip) }
            block(library, input, root, output)
        } finally {
            library.close()
            files.deleteRecursively(parent)
            owner.cleanup()
        }
    }

    @Test fun compressedBytesAndContainerSuffixSurviveExportAndPrivateCleanup() = runTest {
        owned { library, input, root, output ->
            val book = library.importFile((input / "comic.zip").toString(), "Do not expose this source name")
            val snapshot = library.prepareExport(book)
            val privatePath = snapshot.path.toPath()
            try {
                assertFalse(snapshot.isDirectory)
                assertEquals(zip.size.toLong(), snapshot.bytes)
                assertEquals("EhViewer-${book.id.take(16)}.cbz", snapshot.name)
                assertContentEquals(zip, files.read(privatePath) { readByteArray() })
                snapshot.saveTo((output / snapshot.name).toString())
                assertContentEquals(zip, files.read(output / snapshot.name) { readByteArray() })
            } finally {
                snapshot.close()
            }
            assertFalse(files.exists(privatePath.parent!!))
            assertEquals(listOf(book), library.recent())
            assertContentEquals(zip, files.read(input / "comic.zip") { readByteArray() })
            assertEquals(listOf(book.id), files.list(root).map { it.name })
        }
    }

    @Test fun orderedImageFolderRemainsIndependentAfterBookRemovalAndLibraryClose() = runTest {
        owned { library, input, root, output ->
            val book = library.importDirectory(input.toString(), "Original names stay private")
            val snapshot = library.prepareExport(book)
            try {
                assertTrue(snapshot.isDirectory)
                assertEquals((first.size + second.size).toLong(), snapshot.bytes)
                val path = snapshot.path.toPath()
                assertEquals(listOf("000001.png", "000002.png"), files.list(path).map { it.name }.sorted())
                library.remove(book)
                library.close()
                snapshot.saveTo((output / snapshot.name).toString())
                assertContentEquals(first, files.read(output / snapshot.name / "000001.png") { readByteArray() })
                assertContentEquals(second, files.read(output / snapshot.name / "000002.png") { readByteArray() })
            } finally {
                snapshot.close()
            }
            assertTrue(files.list(root).isEmpty())
            assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
            assertContentEquals(second, files.read(input / "10.png") { readByteArray() })
        }
    }

    @Test fun existingFilesFoldersAndPrivateDestinationsAreNeverReplacedOrMerged() = runTest {
        owned { library, input, root, output ->
            val book = library.importDirectory(input.toString(), "Keep")
            val snapshot = library.prepareExport(book)
            try {
                files.write(output / "existing") { writeUtf8("Keep user bytes") }
                files.createDirectory(output / "folder")
                files.write(output / "folder" / "user.txt") { writeUtf8("Keep user child") }
                for (target in listOf(output / "existing", output / "folder", root / "forbidden", snapshot.path.toPath() / "forbidden")) {
                    assertEquals(ArchiveFailure.STORAGE, assertFailsWith<ArchiveException> { snapshot.saveTo(target.toString()) }.failure)
                }
                assertEquals("Keep user bytes", files.read(output / "existing") { readUtf8() })
                assertEquals(listOf("user.txt"), files.list(output / "folder").map { it.name })
                assertEquals("Keep user child", files.read(output / "folder" / "user.txt") { readUtf8() })
                assertFalse(files.exists(root / "forbidden"))
                assertEquals(listOf(book), library.recent())
            } finally {
                snapshot.close()
            }
        }
    }

    @Test fun changedCompressedPayloadFailsWithoutPublishingOrDeletingItsBytes() = runTest {
        owned { library, input, root, _ ->
            val book = library.importFile((input / "comic.zip").toString(), "Keep corrupt bytes")
            val changed = zip.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            files.write(root / book.id / "archive.bin") { write(changed) }
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, assertFailsWith<ArchiveException> { library.prepareExport(book) }.failure)
            assertEquals(listOf(book.id), files.list(root).map { it.name })
            assertContentEquals(changed, files.read(root / book.id / "archive.bin") { readByteArray() })
            assertContentEquals(zip, files.read(input / "comic.zip") { readByteArray() })
        }
    }

    @Test fun changedDirectoryPayloadFailsWithoutChangingTheOriginalProviderOrBook() = runTest {
        owned { library, input, root, _ ->
            val book = library.importDirectory(input.toString(), "Keep corrupt copy")
            val changed = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            files.write(root / book.id / "image-0.bin") { write(changed) }
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, assertFailsWith<ArchiveException> { library.prepareExport(book) }.failure)
            assertEquals(listOf(book.id), files.list(root).map { it.name })
            assertContentEquals(changed, files.read(root / book.id / "image-0.bin") { readByteArray() })
            assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
        }
    }

    @Test fun cancellationDuringSnapshotCopyRemovesOnlyItsUnpublishedPrivateDirectory() = runTest {
        val worker = Job()
        var armed = false
        val io = object : ForwardingFileSystem(files) {
            override fun source(file: Path): Source {
                val source = super.source(file)
                return object : Source by source {
                    override fun read(sink: Buffer, byteCount: Long) = source.read(sink, byteCount).also { if (armed && file.name == "image-0.bin" && it > 0) worker.cancel() }
                }
            }
        }
        owned({ io }) { library, input, root, _ ->
            val book = library.importDirectory(input.toString(), "Cancel")
            armed = true
            assertFailsWith<CancellationException> { withContext(worker) { library.prepareExport(book) } }
            assertEquals(listOf(book.id), files.list(root).map { it.name })
            assertEquals(listOf(book), library.recent())
            assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
        }
        worker.cancel()
    }

    @Test fun interruptedExternalCopiesAreRetainedAndRetryUsesANewDestination() = runTest {
        var fail = false
        val io = object : ForwardingFileSystem(files) {
            override fun source(file: Path): Source {
                val source = super.source(file)
                return if (!fail || file.name != "000001.png") {
                    source
                } else {
                    object : Source by source {
                        var read = false
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (read) throw IOException("private-owned-export-read-path")
                            read = true
                            return source.read(sink, minOf(byteCount, 7))
                        }
                    }
                }
            }
        }
        owned({ io }) { library, input, _, output ->
            val book = library.importDirectory(input.toString(), "Retry")
            val snapshot = library.prepareExport(book)
            try {
                fail = true
                val error = assertFailsWith<ArchiveException> { snapshot.saveTo((output / "interrupted").toString()) }
                assertEquals(ArchiveFailure.STORAGE, error.failure)
                assertNull(error.cause)
                assertFalse(error.message.orEmpty().contains("private-owned"))
                val partial = files.read(output / "interrupted" / "000001.png") { readByteArray() }
                assertEquals(7, partial.size)
                fail = false
                snapshot.saveTo((output / "retry").toString())
                assertContentEquals(first, files.read(output / "retry" / "000001.png") { readByteArray() })
                assertContentEquals(second, files.read(output / "retry" / "000002.png") { readByteArray() })
                snapshot.close()
                assertContentEquals(partial, files.read(output / "interrupted" / "000001.png") { readByteArray() })
                assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
                assertEquals(listOf(book), library.recent())
            } finally {
                snapshot.close()
            }
        }
    }

    @Test fun externalCopyCancellationKeepsThePartialFolderAndAllowsANewDestination() = runTest {
        val worker = Job()
        var armed = false
        val io = object : ForwardingFileSystem(files) {
            override fun source(file: Path): Source {
                val source = super.source(file)
                return object : Source by source {
                    override fun read(sink: Buffer, byteCount: Long) = source.read(sink, byteCount).also { if (armed && file.name == "000001.png" && it > 0) worker.cancel() }
                }
            }
        }
        owned({ io }) { library, input, _, output ->
            val book = library.importDirectory(input.toString(), "Cancel external copy")
            val snapshot = library.prepareExport(book)
            try {
                armed = true
                assertFailsWith<CancellationException> { withContext(worker) { snapshot.saveTo((output / "cancelled").toString()) } }
                assertTrue(files.exists(output / "cancelled" / "000001.png"))
                assertFalse(files.exists(output / "cancelled" / "000002.png"))
                armed = false
                snapshot.saveTo((output / "retry").toString())
                assertContentEquals(first, files.read(output / "retry" / "000001.png") { readByteArray() })
                assertContentEquals(second, files.read(output / "retry" / "000002.png") { readByteArray() })
                assertEquals(listOf(book), library.recent())
            } finally {
                snapshot.close()
                worker.cancel()
            }
            assertTrue(files.exists(output / "cancelled" / "000001.png"))
            assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
        }
    }

    @Test fun destinationWriteFailureKeepsTheLibraryAndSourceSnapshotAvailable() = runTest {
        var denied: Path? = null
        val io = object : ForwardingFileSystem(files) {
            override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
                if (file == denied) throw IOException("private-owned-export-write-path")
                return super.openReadWrite(file, mustCreate, mustExist)
            }
        }
        owned({ io }) { library, input, _, output ->
            val book = library.importFile((input / "comic.zip").toString(), "Write failure")
            val snapshot = library.prepareExport(book)
            try {
                val target = output / "new.cbz"
                denied = files.canonicalize(output) / target.name
                assertEquals(ArchiveFailure.STORAGE, assertFailsWith<ArchiveException> { snapshot.saveTo(target.toString()) }.failure)
                assertFalse(files.exists(target))
                assertContentEquals(zip, files.read(snapshot.path.toPath()) { readByteArray() })
                denied = null
                snapshot.saveTo(target.toString())
                assertContentEquals(zip, files.read(target) { readByteArray() })
                assertEquals(listOf(book), library.recent())
            } finally {
                snapshot.close()
            }
        }
    }

    @Test fun closeIsIdempotentAndClosedSnapshotsCannotBeSavedOrExposed() = runTest {
        owned { library, input, _, output ->
            val snapshot = library.prepareExport(library.importDirectory(input.toString(), "Close"))
            val staging = snapshot.path.toPath().parent!!
            snapshot.close()
            snapshot.close()
            assertFalse(files.exists(staging))
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { snapshot.path }.failure)
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { snapshot.saveTo((output / "closed").toString()) }.failure)
            assertFalse(files.exists(output / "closed"))
        }
    }

    @Test fun cancelledCallerStillCompletesItsPrivateSnapshotCleanup() = runTest {
        owned { library, input, _, _ ->
            val snapshot = library.prepareExport(library.importDirectory(input.toString(), "Cancelled close"))
            val staging = snapshot.path.toPath().parent!!
            val closing = async(Dispatchers.Default) {
                currentCoroutineContext().cancel()
                snapshot.close()
            }
            closing.join()
            assertTrue(closing.isCancelled)
            assertFalse(files.exists(staging))
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { snapshot.path }.failure)
        }
    }

    @Test fun failedPrivateCleanupRemainsAFailureAndCanBeRetried() = runTest {
        var fail = false
        val io = object : ForwardingFileSystem(files) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (fail && path.name == "000001.png") throw IOException("private-owned-cleanup-path")
                super.delete(path, mustExist)
            }
        }
        owned({ io }) { library, input, _, _ ->
            val snapshot = library.prepareExport(library.importDirectory(input.toString(), "Retry cleanup"))
            val payload = snapshot.path.toPath()
            fail = true
            assertEquals(ArchiveFailure.STORAGE, assertFailsWith<ArchiveException> { snapshot.close() }.failure)
            assertContentEquals(first, files.read(payload / "000001.png") { readByteArray() })
            fail = false
            snapshot.close()
            assertFalse(files.exists(payload.parent!!))
        }
    }
}
