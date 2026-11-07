@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveExport
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.DirectoryImage
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

private data class ExportEntry(val name: String, val bytes: Long, val sha256: String)

/** Copy and hash once; never publish a snapshot whose payload differs from the library identity. */
internal suspend fun prepareArchiveExport(files: FileSystem, root: Path, book: ArchiveBook, images: List<DirectoryImage>?): ArchiveExport {
    val staging = root / ".export-${Uuid.random()}"
    files.createDirectory(staging, mustCreate = true)
    try {
        val source = root / book.id
        val prefix = "EhViewer-${book.id.take(16)}"
        val name = if (images == null) {
            val header = files.read(source / "archive.bin") { readByteArray(minOf(book.bytes, 512)) }
            "$prefix.${archiveExportExtension(header)}"
        } else {
            prefix
        }
        val payload = staging / name
        val entries = if (images == null) {
            listOf(ExportEntry(name, book.bytes, book.id)).also { copyExportFile(files, source / "archive.bin", payload, it.single()) }
        } else {
            files.createDirectory(payload, mustCreate = true)
            images.mapIndexed { index, image ->
                currentCoroutineContext().ensureActive()
                val entry = ExportEntry("${(index + 1).toString().padStart(6, '0')}.${image.extension}", image.bytes, image.sha256)
                copyExportFile(files, DirectoryImages.page(source, index), payload / entry.name, entry)
                entry
            }
        }
        currentCoroutineContext().ensureActive()
        return FileArchiveExport(files, root, staging, payload, images != null, book.bytes, entries)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            try {
                files.deleteRecursively(staging)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
        }
        throw failure
    }
}

/** Owns only its private snapshot, independently of the library or a native reader lease. */
private class FileArchiveExport(
    private val files: FileSystem,
    private val libraryRoot: Path,
    private val staging: Path,
    private val payload: Path,
    override val isDirectory: Boolean,
    override val bytes: Long,
    private val entries: List<ExportEntry>,
) : ArchiveExport {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    override val name get() = payload.name
    override val path get() = payload.toString().also { ensureOpen() }

    private fun ensureOpen() {
        if (closed.load()) throw ArchiveException(ArchiveFailure.CLOSED)
    }

    override suspend fun saveTo(path: String) = exportStorage {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                val requested = path.toPath(normalize = true)
                require(requested.isAbsolute && requested.name.isNotEmpty())
                val parent = files.canonicalize(checkNotNull(requested.parent))
                check(files.metadata(parent).isDirectory)
                val privateRoot = files.canonicalize(libraryRoot)
                var ancestor: Path? = parent
                while (ancestor != null) {
                    check(ancestor != privateRoot) { "An export cannot replace private library data" }
                    ancestor = ancestor.parent
                }
                val target = parent / requested.name
                check(files.metadataOrNull(target) == null) { "Choose a new destination" }
                currentCoroutineContext().ensureActive()
                if (isDirectory) {
                    files.createDirectory(target, mustCreate = true)
                    for (entry in entries) copyExportFile(files, payload / entry.name, target / entry.name, entry)
                } else {
                    copyExportFile(files, payload, target, entries.single())
                }
                // Deliberately retain an interrupted external copy. It may already contain user
                // changes; only the private staging directory belongs to this object's cleanup.
            }
        }
    }

    override suspend fun close() = exportStorage {
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                if (!closed.load()) {
                    val metadata = files.metadataOrNull(staging)
                    if (metadata != null) {
                        check(metadata.isDirectory && metadata.symlinkTarget == null)
                        files.deleteRecursively(staging)
                    }
                    closed.store(true)
                }
            }
        }
    }
}

private suspend fun copyExportFile(files: FileSystem, source: Path, target: Path, entry: ExportEntry) {
    currentCoroutineContext().ensureActive()
    val before = files.metadata(source)
    if (!before.isRegularFile || before.symlinkTarget != null || before.size != entry.bytes) invalidExport()
    var copied = 0L
    val hash = files.openReadWrite(target, mustCreate = true).use { handle ->
        val hashing = HashingSink.sha256(handle.sink())
        hashing.buffer().use { output ->
            files.source(source).buffer().use { input ->
                val chunk = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(chunk, 64 * 1024)
                    if (count == -1L) break
                    copied += count
                    if (copied > entry.bytes) invalidExport()
                    output.write(chunk, count)
                }
            }
        }
        handle.flush()
        hashing.hash.hex()
    }
    val after = files.metadata(source)
    if (copied != entry.bytes || hash != entry.sha256 || !after.isRegularFile || after.symlinkTarget != null || after.size != entry.bytes || after.lastModifiedAtMillis != before.lastModifiedAtMillis) invalidExport()
    currentCoroutineContext().ensureActive()
}

/** Preserve the actual outer container. Unknown/SFX formats keep a generic archive suffix. */
private fun archiveExportExtension(bytes: ByteArray): String {
    fun begins(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { bytes[it].toInt() and 255 == signature[it] }
    return when {
        begins(80, 75, 3, 4) || begins(80, 75, 5, 6) || begins(80, 75, 7, 8) -> "cbz"
        begins(82, 97, 114, 33, 26, 7) -> "cbr"
        begins(55, 122, 188, 175, 39, 28) -> "cb7"
        begins(31, 139) -> "gz"
        begins(253, 55, 122, 88, 90, 0) -> "xz"
        bytes.size >= 262 && bytes.copyOfRange(257, 262).decodeToString() == "ustar" -> "cbt"
        else -> "archive"
    }
}

private fun invalidExport(): Nothing = throw ArchiveException(ArchiveFailure.INVALID_ARCHIVE)

private suspend fun <T> exportStorage(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: ArchiveException) {
    throw failure
} catch (_: Exception) {
    throw ArchiveException(ArchiveFailure.STORAGE)
}
