package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveDocumentFactory
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.DirectoryImage
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.use

class DirectoryLibraryTest {
    private val files = FileSystem.SYSTEM
    private val first = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
    private val second = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAYAAACZgbYnAAAADklEQVR4XmNg+A+CQAAAF/IF+w/LXpgAAAAASUVORK5CYII=")
    private val factory = ArchiveDocumentFactory { error("Image folders must not invoke a compressed archive factory") }

    private suspend fun owned(block: suspend (FileArchiveLibrary, Path, Path) -> Unit) {
        val owned = ownedDatabaseFile()
        val input = (owned.path + "-directory-input").toPath()
        val root = (owned.path + "-directory-books").toPath()
        files.createDirectories(input)
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

    private fun images(input: Path) {
        files.write(input / "10.png") { write(second) }
        files.write(input / "2.png") { write(first) }
        files.write(input / "ignored.txt") { writeUtf8("Owned ignored sidecar") }
    }

    @Test fun numericOrderPrivateCopiesReopenAndDeletePreserveAllOriginals() = runTest {
        owned { library, input, root ->
            images(input)
            val book = library.importDirectory(input.toString(), "Owned 资料 folder")
            assertEquals(2, book.pages)
            assertEquals((first.size + second.size).toLong(), book.bytes)
            assertFalse(book.encrypted)
            library.open(book).use { document ->
                assertContentEquals(first, document.read(0))
                assertContentEquals(second, document.read(1))
                assertEquals("png", document.extension(0))
            }
            library.close()
            val reopened = FileArchiveLibrary(root, factory)
            try {
                assertEquals(listOf(book), reopened.recent())
                reopened.open(book).use { assertContentEquals(second, it.read(1)) }
                reopened.remove(book)
                assertTrue(reopened.recent().isEmpty())
                assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
                assertContentEquals(second, files.read(input / "10.png") { readByteArray() })
                assertEquals("Owned ignored sidecar", files.read(input / "ignored.txt") { readUtf8() })
            } finally {
                reopened.close()
            }
        }
    }

    @Test fun nestedUnicodeNamesDeduplicateByOrderedContentAndKeepTheOriginalTitle() = runTest {
        owned { library, input, root ->
            files.createDirectories(input / "章2")
            files.createDirectories(input / "章10")
            files.write(input / "章10" / "Page100000000000000000000000000000.png") { write(second) }
            files.write(input / "章2" / "Page2.PNG") { write(first) }
            val book = library.importDirectory(input.toString(), "First title")
            assertEquals(book, library.importDirectory(input.toString(), "Do not replace title"))
            library.open(book).use {
                assertContentEquals(first, it.read(0))
                assertContentEquals(second, it.read(1))
            }
            assertEquals(listOf(book.id), files.list(root).map { it.name })
            assertTrue(files.list(root / book.id).none { it.name.contains("章") || it.name.contains("Page") })
        }
    }

    @Test fun emptyAndMislabeledImageFoldersFailWithoutPublishingOrChangingInputs() = runTest {
        owned { library, input, root ->
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, assertFailsWith<ArchiveException> { library.importDirectory(input.toString(), "Empty") }.failure)
            val wrong = "not a PNG".encodeToByteArray()
            files.write(input / "wrong.png") { write(wrong) }
            val failure = assertFailsWith<ArchiveException> { library.importDirectory(input.toString(), "Mislabeled") }
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, failure.failure)
            assertNull(failure.cause)
            assertTrue(library.recent().isEmpty())
            assertTrue(files.list(root).isEmpty())
            assertContentEquals(wrong, files.read(input / "wrong.png") { readByteArray() })
        }
    }

    @Test fun oversizedImagesAndDeepDirectoriesAreRejectedBeforePublication() = runTest {
        owned { library, input, root ->
            files.openReadWrite(input / "too-large.png", mustCreate = true).use { it.resize(DirectoryImage.MAX_IMAGE_BYTES + 1) }
            assertEquals(ArchiveFailure.LIMIT, assertFailsWith<ArchiveException> { library.importDirectory(input.toString(), "Too large") }.failure)
            files.delete(input / "too-large.png")
            var leaf = input
            repeat(33) {
                leaf /= "d"
                files.createDirectory(leaf)
            }
            files.write(leaf / "1.png") { write(first) }
            assertEquals(ArchiveFailure.LIMIT, assertFailsWith<ArchiveException> { library.importDirectory(input.toString(), "Too deep") }.failure)
            assertTrue(files.list(root).isEmpty())
        }
    }

    @Test fun atomicPublicationFailureKeepsExistingBooksAndRemovesOnlyItsOwnStaging() = runTest {
        owned { library, input, root ->
            images(input)
            val firstBook = library.importDirectory(input.toString(), "Keep me")
            files.write(input / "3.png") { write(first) }
            val failing = object : ForwardingFileSystem(files) {
                override fun atomicMove(source: Path, target: Path) = throw IOException("owned-private-source")
            }
            val attempted = FileArchiveLibrary(root, factory, failing)
            try {
                val failure = assertFailsWith<ArchiveException> { attempted.importDirectory(input.toString(), "Failed import") }
                assertEquals(ArchiveFailure.STORAGE, failure.failure)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("private-source"))
                assertEquals(listOf(firstBook), library.recent())
                assertEquals(listOf(firstBook.id), files.list(root).map { it.name })
                library.open(firstBook).use { assertContentEquals(first, it.read(0)) }
            } finally {
                attempted.close()
            }
        }
    }

    @Test fun cancellationDuringTheActualImageReadClosesItAndRemovesUnpublishedCopies() = runTest {
        owned { _, input, root ->
            images(input)
            val worker = Job()
            var closed = false
            val cancelling = object : ForwardingFileSystem(files) {
                override fun source(file: Path): Source {
                    val delegate = super.source(file)
                    return object : Source by delegate {
                        override fun read(sink: Buffer, byteCount: Long): Long = delegate.read(sink, byteCount).also { if (it > 0 && file == input / "2.png") worker.cancel() }
                        override fun close() {
                            delegate.close()
                            closed = true
                        }
                    }
                }
            }
            val library = FileArchiveLibrary(root, factory, cancelling)
            try {
                assertFailsWith<CancellationException> { withContext(worker) { library.importDirectory(input.toString(), "Cancelled") } }
                assertTrue(closed)
                assertTrue(files.list(root).isEmpty())
                assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
            } finally {
                library.close()
                worker.cancel()
            }
        }
    }

    @Test fun aSourceChangedDuringCopyNeverReplacesThePreviouslyImportedSnapshot() = runTest {
        owned { library, input, root ->
            images(input)
            val saved = library.importDirectory(input.toString(), "Keep snapshot")
            var changed = false
            val changing = object : ForwardingFileSystem(files) {
                override fun source(file: Path): Source {
                    val delegate = super.source(file)
                    return object : Source by delegate {
                        override fun close() {
                            delegate.close()
                            if (!changed && file == input / "2.png") {
                                changed = true
                                files.write(file) {
                                    write(first)
                                    writeByte(0)
                                }
                            }
                        }
                    }
                }
            }
            val attempted = FileArchiveLibrary(root, factory, changing)
            try {
                assertFailsWith<ArchiveException> { attempted.importDirectory(input.toString(), "Changing input") }
                assertTrue(changed)
                assertEquals(listOf(saved), library.recent())
                library.open(saved).use { assertContentEquals(first, it.read(0)) }
                assertEquals(listOf(saved.id), files.list(root).map { it.name })
            } finally {
                attempted.close()
            }
        }
    }

    @Test fun corruptedPrivatePixelsAndManifestsRemainExplicitFailuresAndCanBeDeleted() = runTest {
        owned { library, input, root ->
            images(input)
            val book = library.importDirectory(input.toString(), "Corruption test")
            val page = DirectoryImages.page(root / book.id, 0)
            val wrong = first.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
            files.write(page) { write(wrong) }
            library.open(book).use { document -> assertEquals(ArchiveFailure.INVALID_ARCHIVE, assertFailsWith<ArchiveException> { document.read(0) }.failure) }
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, assertFailsWith<ArchiveException> { library.importDirectory(input.toString(), "Do not overwrite corruption") }.failure)
            assertContentEquals(wrong, files.read(page) { readByteArray() })
            files.write(root / book.id / DirectoryImages.MANIFEST) { writeByte(0) }
            assertEquals(listOf(book), library.recent())
            assertFailsWith<ArchiveException> { library.open(book) }
            library.remove(book)
            assertTrue(library.recent().isEmpty())
            assertContentEquals(first, files.read(input / "2.png") { readByteArray() })
        }
    }

    @Test fun leasesPreventActiveRemovalAndRejectBoundsOrReadsAfterClose() = runTest {
        owned { library, input, root ->
            images(input)
            val book = library.importDirectory(input.toString(), "Lease")
            val manifest = DirectoryImages(files).manifest(root / book.id, book)
            val retained = ArchiveLease(DirectoryImages(files).open(root / book.id, manifest))
            assertTrue(retained.retainsDocument)
            retained.close()
            assertTrue(retained.isReleased)
            assertFalse(retained.retainsDocument)
            val document = library.open(book)
            assertFalse(document.needsPassword)
            assertTrue(document.providePassword("Not required"))
            assertEquals(ArchiveFailure.IN_USE, assertFailsWith<ArchiveException> { library.remove(book) }.failure)
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, assertFailsWith<ArchiveException> { document.read(2) }.failure)
            assertEquals(ArchiveFailure.INVALID_ARCHIVE, assertFailsWith<ArchiveException> { document.extension(-1) }.failure)
            document.close()
            document.close()
            assertEquals(ArchiveFailure.CLOSED, assertFailsWith<ArchiveException> { document.read(0) }.failure)
            library.open(book).use { assertContentEquals(first, it.read(0)) }
            library.remove(book)
            assertTrue(library.recent().isEmpty())
        }
    }

    @Test fun boundedPlatformEnumerationAndOverflowSafeSortingAreActuallyExecuted() = runTest {
        owned { _, input, _ ->
            images(input)
            assertEquals(ArchiveFailure.LIMIT, assertFailsWith<ArchiveException> { directoryNames(input, 2) }.failure)
            assertEquals(setOf("10.png", "2.png", "ignored.txt"), directoryNames(input, 3).toSet())
            val names = listOf("page10.png", "page2.png", "page999999999999999999999999999999.png", "page1000000000000000000000000000000.png", "page1.png")
            assertEquals(listOf(names[4], names[1], names[0], names[2], names[3]), names.sortedWith(::compareDirectoryNames))
            assertEquals(listOf("A2.png", "a2.png", "a10.png"), listOf("a10.png", "a2.png", "A2.png").sortedWith(::compareDirectoryNames))
            assertTrue(imageHeaderMatches("png", first))
            assertFalse(imageHeaderMatches("gif", first))
            assertFalse(imageHeaderMatches("png", first.copyOf(7)))
        }
    }
}
