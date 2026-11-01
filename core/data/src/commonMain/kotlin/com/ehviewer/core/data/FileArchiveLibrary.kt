@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveDocumentFactory
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveExport
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.ArchiveLibrary
import com.ehviewer.core.domain.reader.ArchiveLibrarySnapshot
import com.ehviewer.core.domain.reader.DirectoryImage
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** Atomic private-directory publication; original provider bytes and existing books are never replaced. */
class FileArchiveLibrary(private val directory: Path, private val factory: ArchiveDocumentFactory, private val files: FileSystem = FileSystem.SYSTEM) : ArchiveLibrary {
    private val mutex = Mutex()
    private val cbor = Cbor { encodeDefaults = true }
    private val leases = mutableMapOf<String, MutableList<ArchiveLease>>()
    private val directories = DirectoryImages(files)
    private var closed = false

    init {
        require(directory.isAbsolute)
    }

    private suspend fun <T> stored(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) throw ArchiveException(ArchiveFailure.CLOSED)
                block()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ArchiveException) {
        throw failure
    } catch (_: Exception) {
        throw ArchiveException(ArchiveFailure.STORAGE)
    }

    private fun root() {
        files.createDirectories(directory)
        check(files.metadata(directory).symlinkTarget == null)
    }

    private fun folder(id: String): Path {
        require(Regex("[a-f0-9]{64}").matches(id))
        return directory / id
    }

    private fun metadata(folder: Path): ArchiveBook {
        check(files.metadata(folder).isDirectory && files.metadata(folder).symlinkTarget == null)
        val file = folder / "book.cbor"
        val info = files.metadata(file)
        check(info.isRegularFile && info.symlinkTarget == null && info.size?.let { it in 1..16384 } == true)
        val book = cbor.decodeFromByteArray<ArchiveBook>(files.read(file) { readByteArray() }).also { it.validate() }
        check(book.id == folder.name)
        val archive = files.metadataOrNull(folder / "archive.bin")
        val images = files.metadataOrNull(folder / DirectoryImages.MANIFEST)
        check((archive == null) != (images == null))
        if (archive != null) {
            check(archive.isRegularFile && archive.symlinkTarget == null && archive.size == book.bytes)
        } else {
            checkNotNull(images)
            check(!book.encrypted && images.isRegularFile && images.symlinkTarget == null && images.size?.let { it in 1..DirectoryImages.MAX_MANIFEST_BYTES } == true)
        }
        return book
    }

    override suspend fun recent(): List<ArchiveBook> = stored {
        root()
        val folders = files.list(directory).filter { Regex("[a-f0-9]{64}").matches(it.name) }
        check(folders.size <= 500)
        folders.map(::metadata).sortedWith(compareBy<ArchiveBook> { it.title }.thenBy { it.id })
    }

    override suspend fun inspect(): ArchiveLibrarySnapshot = stored {
        root()
        val folders = files.list(directory).filter { Regex("[a-f0-9]{64}").matches(it.name) }
        check(folders.size <= 500)
        val books = mutableListOf<ArchiveBook>()
        var unavailable = 0
        for (folder in folders) {
            currentCoroutineContext().ensureActive()
            try {
                books += metadata(folder)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable++
            }
        }
        currentCoroutineContext().ensureActive()
        ArchiveLibrarySnapshot(books.sortedWith(compareBy<ArchiveBook> { it.title }.thenBy { it.id }), unavailable)
    }

    override suspend fun prepareExport(book: ArchiveBook): ArchiveExport = stored {
        book.validate()
        root()
        val source = folder(book.id)
        check(metadata(source) == book)
        val images = if (files.exists(source / DirectoryImages.MANIFEST)) directories.manifest(source, book) else null
        prepareArchiveExport(files, directory, book, images)
    }

    override suspend fun importFile(path: String, title: String): ArchiveBook = stored {
        require(title.isNotBlank() && title.length <= 1024 && title.none { it == '\u0000' || it == '\n' || it == '\r' })
        root()
        val input = path.toPath().also { require(it.isAbsolute) }
        val info = files.metadata(input)
        require(info.isRegularFile && info.symlinkTarget == null)
        val expected = checkNotNull(info.size)
        if (expected !in 1..ArchiveBook.MAX_ARCHIVE_BYTES) throw ArchiveException(ArchiveFailure.LIMIT)
        val existingCount = files.list(directory).count { Regex("[a-f0-9]{64}").matches(it.name) }
        check(existingCount <= 500)
        val temporary = directory / ".import-${Uuid.random()}"
        check(!files.exists(temporary))
        files.createDirectory(temporary)
        var failure: Throwable? = null
        try {
            var copied = 0L
            val hash = files.openReadWrite(temporary / "archive.bin", mustCreate = true).use { handle ->
                val hashing = HashingSink.sha256(handle.sink())
                hashing.buffer().use { output ->
                    files.source(input).buffer().use { source ->
                        val chunk = Buffer()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(chunk, 64 * 1024)
                            if (read == -1L) break
                            copied += read
                            if (copied > expected || copied > ArchiveBook.MAX_ARCHIVE_BYTES) throw ArchiveException(ArchiveFailure.LIMIT)
                            output.write(chunk, read)
                        }
                    }
                }
                handle.flush()
                check(copied == expected)
                hashing.hash.hex()
            }
            currentCoroutineContext().ensureActive()
            val target = folder(hash)
            if (files.exists(target)) {
                return@stored metadata(target).also { verifyContent(it) }
            }
            if (existingCount == 500) throw ArchiveException(ArchiveFailure.LIMIT)
            val book = factory.open((temporary / "archive.bin").toString()).use { archive -> ArchiveBook(hash, title, copied, archive.pageCount, archive.needsPassword).also { it.validate() } }
            currentCoroutineContext().ensureActive()
            val encoded = cbor.encodeToByteArray(book)
            check(encoded.size <= 16384)
            files.openReadWrite(temporary / "book.cbor", mustCreate = true).use { handle ->
                handle.sink().buffer().use { it.write(encoded) }
                handle.flush()
            }
            currentCoroutineContext().ensureActive()
            files.atomicMove(temporary, target)
            book
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            try {
                if (files.exists(temporary)) files.deleteRecursively(temporary)
            } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    override suspend fun importDirectory(path: String, title: String): ArchiveBook = stored {
        require(title.isNotBlank() && title.length <= 1024 && title.none { it == '\u0000' || it == '\n' || it == '\r' })
        root()
        val input = path.toPath().also { require(it.isAbsolute) }
        val info = files.metadata(input)
        require(info.isDirectory && info.symlinkTarget == null)
        val count = files.list(directory).count { Regex("[a-f0-9]{64}").matches(it.name) }
        check(count <= 500)
        val temporary = directory / ".import-${Uuid.random()}"
        check(!files.exists(temporary))
        files.createDirectory(temporary)
        var failure: Throwable? = null
        try {
            val book = directories.copy(input, temporary, title)
            currentCoroutineContext().ensureActive()
            val target = folder(book.id)
            if (files.exists(target)) {
                return@stored metadata(target).also { existing ->
                    val records = verifyContent(existing)
                    if (records != null) directories.verifyAll(target, records)
                }
            }
            if (count == 500) throw ArchiveException(ArchiveFailure.LIMIT)
            val encoded = cbor.encodeToByteArray(book)
            check(encoded.size <= 16384)
            files.openReadWrite(temporary / "book.cbor", mustCreate = true).use { handle ->
                handle.sink().buffer().use { it.write(encoded) }
                handle.flush()
            }
            currentCoroutineContext().ensureActive()
            files.atomicMove(temporary, target)
            book
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            try {
                if (files.exists(temporary)) files.deleteRecursively(temporary)
            } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun verifyContent(book: ArchiveBook): List<DirectoryImage>? {
        if (files.exists(folder(book.id) / DirectoryImages.MANIFEST)) return directories.manifest(folder(book.id), book)
        val hash = HashingSource.sha256(files.source(folder(book.id) / "archive.bin"))
        hash.buffer().use { source ->
            val chunk = Buffer()
            var read = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(chunk, 64 * 1024)
                if (count == -1L) break
                read += count
                check(read <= book.bytes)
                chunk.clear()
            }
            check(read == book.bytes)
        }
        if (hash.hash.hex() != book.id) throw ArchiveException(ArchiveFailure.INVALID_ARCHIVE)
        return null
    }

    override suspend fun open(book: ArchiveBook): ArchiveDocument {
        var opened: ArchiveDocument? = null
        try {
            stored {
                book.validate()
                check(metadata(folder(book.id)) == book)
                val directoryImages = verifyContent(book)
                currentCoroutineContext().ensureActive()
                val document = if (directoryImages == null) factory.open((folder(book.id) / "archive.bin").toString()) else directories.open(folder(book.id), directoryImages)
                opened = document
                check(document.pageCount == book.pages && document.needsPassword == book.encrypted)
                val lease = ArchiveLease(document)
                opened = lease
                leases.getOrPut(book.id) { mutableListOf() }.apply {
                    removeAll { it.isReleased }
                    add(lease)
                }
            }
            return checkNotNull(opened)
        } catch (failure: Throwable) {
            // withContext may cancel after a native open completed but before delivering its value.
            try {
                opened?.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override suspend fun remove(book: ArchiveBook) = stored {
        book.validate()
        check(metadata(folder(book.id)) == book)
        val owners = leases[book.id]
        owners?.removeAll { it.isReleased }
        if (!owners.isNullOrEmpty()) throw ArchiveException(ArchiveFailure.IN_USE)
        // A failure to delete a locked archive must not first remove its metadata.
        if (files.exists(folder(book.id) / "archive.bin")) {
            files.delete(folder(book.id) / "archive.bin", mustExist = true)
        } else {
            for (index in 0 until book.pages) {
                currentCoroutineContext().ensureActive()
                files.delete(DirectoryImages.page(folder(book.id), index), mustExist = false)
            }
        }
        files.deleteRecursively(folder(book.id), mustExist = true)
    }

    override suspend fun close() = withContext(Dispatchers.IO + NonCancellable) {
        var failed = false
        val owners = mutex.withLock {
            closed = true
            leases.values.flatten().also { list ->
                list.forEach {
                    try {
                        it.close()
                    } catch (_: Exception) {
                        failed = true
                    }
                }
            }
        }
        // A desktop DLL owner may be released only after already admitted IO calls have ended.
        while (owners.any { !it.isReleased && !it.isReleaseFailed }) {
            delay(10)
        }
        if (failed || owners.any { it.isReleaseFailed }) throw ArchiveException(ArchiveFailure.STORAGE)
    }
}
