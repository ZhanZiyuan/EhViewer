@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.DirectoryImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use

/** Private immutable image snapshots use the existing local-book identity and reader contract. */
internal class DirectoryImages(private val files: FileSystem) {
    private val cbor = Cbor { encodeDefaults = true }
    private data class Input(val relative: String, val extension: String, val bytes: Long)

    suspend fun copy(input: Path, target: Path, title: String): ArchiveBook {
        val images = mutableListOf<Input>()
        var entries = 0
        var pathBytes = 0L
        var imageBytes = 0L
        suspend fun scan(directory: Path, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 32) limit()
            val metadata = files.metadata(directory)
            check(metadata.isDirectory && metadata.symlinkTarget == null)
            val names = directoryNames(directory, MAX_ENTRIES - entries)
            entries += names.size
            for (name in names) {
                currentCoroutineContext().ensureActive()
                if (name.isEmpty() || name.any { it in "/\\:\u0000" || it < ' ' } || name in setOf(".", "..")) invalidDirectory()
                val child = directory / name
                val relative = child.relativeTo(input).toString().replace('\\', '/')
                pathBytes += relative.encodeToByteArray().size
                if (pathBytes > 4L * 1024 * 1024) limit()
                val info = files.metadata(child)
                if (info.symlinkTarget != null) invalidDirectory()
                if (info.isDirectory) {
                    scan(child, depth + 1)
                } else if (info.isRegularFile) {
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension !in DirectoryImage.EXTENSIONS) continue
                    val size = checkNotNull(info.size)
                    if (size !in 1..DirectoryImage.MAX_IMAGE_BYTES) limit()
                    imageBytes += size
                    if (imageBytes > ArchiveBook.MAX_ARCHIVE_BYTES) limit()
                    images += Input(relative, extension, size)
                }
            }
        }
        scan(input, 0)
        if (images.isEmpty()) invalidDirectory()
        images.sortWith { a, b -> compareDirectoryNames(a.relative, b.relative) }
        val records = images.mapIndexed { index, image ->
            currentCoroutineContext().ensureActive()
            val sourcePath = input / image.relative
            val before = files.metadata(sourcePath)
            check(before.isRegularFile && before.symlinkTarget == null && before.size == image.bytes)
            var copied = 0L
            val hash = files.openReadWrite(page(target, index), mustCreate = true).use { handle ->
                val digest = HashingSink.sha256(handle.sink())
                digest.buffer().use { output ->
                    files.source(sourcePath).buffer().use { source ->
                        val header = source.peek().readByteArray(minOf(image.bytes, 16))
                        if (!imageHeaderMatches(image.extension, header)) invalidDirectory()
                        val chunk = Buffer()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(chunk, 64 * 1024)
                            if (count == -1L) break
                            copied += count
                            if (copied > image.bytes) invalidDirectory()
                            output.write(chunk, count)
                        }
                    }
                }
                handle.flush()
                check(copied == image.bytes)
                digest.hash.hex()
            }
            val after = files.metadata(sourcePath)
            check(after.isRegularFile && after.symlinkTarget == null && after.size == image.bytes && after.lastModifiedAtMillis == before.lastModifiedAtMillis)
            DirectoryImage(image.extension, copied, hash).also { it.validate() }
        }
        val encoded = cbor.encodeToByteArray(records)
        if (encoded.size > MAX_MANIFEST_BYTES) limit()
        files.openReadWrite(target / MANIFEST, mustCreate = true).use { handle ->
            handle.sink().buffer().use { it.write(encoded) }
            handle.flush()
        }
        return ArchiveBook(identity(encoded), title, imageBytes, records.size, false).also { it.validate() }
    }

    fun manifest(folder: Path, book: ArchiveBook): List<DirectoryImage> {
        val metadata = files.metadata(folder / MANIFEST)
        check(metadata.isRegularFile && metadata.symlinkTarget == null && metadata.size?.let { it in 1..MAX_MANIFEST_BYTES } == true)
        val encoded = files.read(folder / MANIFEST) { readByteArray(checkNotNull(metadata.size)) }
        if (identity(encoded) != book.id) invalidDirectory()
        val records = cbor.decodeFromByteArray<List<DirectoryImage>>(encoded)
        check(!book.encrypted && records.size == book.pages && records.size in 1..MAX_ENTRIES)
        records.forEach { it.validate() }
        check(records.sumOf { it.bytes } == book.bytes)
        records.forEachIndexed { index, image -> validatePage(files, page(folder, index), image) }
        return records
    }

    suspend fun verifyAll(folder: Path, records: List<DirectoryImage>) {
        records.forEachIndexed { index, image ->
            val hashing = HashingSource.sha256(files.source(page(folder, index)))
            hashing.buffer().use { source ->
                val chunk = Buffer()
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(chunk, 64 * 1024)
                    if (count == -1L) break
                    bytes += count
                    if (bytes > image.bytes) invalidDirectory()
                    chunk.clear()
                }
                if (bytes != image.bytes) invalidDirectory()
            }
            if (hashing.hash.hex() != image.sha256) invalidDirectory()
        }
    }

    fun open(folder: Path, records: List<DirectoryImage>): ArchiveDocument = object : ArchiveDocument {
        private var closed = false
        override val pageCount = records.size
        override val needsPassword = false
        private fun record(index: Int): DirectoryImage {
            if (closed) throw ArchiveException(ArchiveFailure.CLOSED)
            return records.getOrNull(index) ?: invalidDirectory()
        }
        override fun extension(index: Int) = record(index).extension
        override fun providePassword(password: String): Boolean {
            if (closed) throw ArchiveException(ArchiveFailure.CLOSED)
            return true // Image directories have no encrypted document layer.
        }
        override fun read(index: Int): ByteArray = try {
            val image = record(index)
            val path = page(folder, index)
            validatePage(files, path, image)
            files.read(path) { readByteArray(image.bytes) }.also {
                if (it.toByteString().sha256().hex() != image.sha256) invalidDirectory()
            }
        } catch (failure: ArchiveException) {
            throw failure
        } catch (_: Exception) {
            throw ArchiveException(ArchiveFailure.STORAGE)
        }
        override fun close() {
            closed = true
        }
    }

    private fun identity(bytes: ByteArray): String {
        val digest = HashingSink.sha256(blackholeSink())
        digest.buffer().use {
            it.writeUtf8("EhViewer image directory v1\n")
            it.write(bytes)
        }
        return digest.hash.hex()
    }

    companion object {
        const val MANIFEST = "pages.cbor"
        const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
        const val MAX_ENTRIES = 100_000
        fun page(folder: Path, index: Int) = folder / "image-$index.bin"
        private fun validatePage(files: FileSystem, path: Path, image: DirectoryImage) {
            val info = files.metadata(path)
            check(info.isRegularFile && info.symlinkTarget == null && info.size == image.bytes)
        }
        private fun invalidDirectory(): Nothing = throw ArchiveException(ArchiveFailure.INVALID_ARCHIVE)
        private fun limit(): Nothing = throw ArchiveException(ArchiveFailure.LIMIT)
    }
}

/** Identify supported containers before publication; the bounded platform decoder validates pixels. */
internal fun imageHeaderMatches(extension: String, bytes: ByteArray): Boolean {
    fun begins(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { bytes[it].toInt() and 255 == signature[it] }
    fun text(offset: Int, size: Int) = if (bytes.size >= offset + size) bytes.copyOfRange(offset, offset + size).decodeToString() else ""
    return when (extension) {
        "jpg", "jpeg" -> begins(255, 216, 255)
        "png" -> begins(137, 80, 78, 71, 13, 10, 26, 10)
        "gif" -> text(0, 6) in setOf("GIF87a", "GIF89a")
        "webp" -> text(0, 4) == "RIFF" && text(8, 4) == "WEBP"
        "bmp" -> text(0, 2) == "BM"
        "ico" -> begins(0, 0, 1, 0) && bytes.size >= 6 && (bytes[4].toInt() and 255 != 0 || bytes[5].toInt() and 255 != 0)
        "wbmp" -> begins(0, 0) && bytes.size >= 4
        "heic", "heif", "avif" -> text(4, 4) == "ftyp" && text(8, 4) in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis")
        else -> false
    }
}
