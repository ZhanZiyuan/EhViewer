package com.ehviewer.core.domain.reader

import kotlinx.serialization.Serializable

/** Content identity belongs to the local library, independently of online gallery IDs. */
@Serializable
data class ArchiveBook(val id: String, val title: String, val bytes: Long, val pages: Int, val encrypted: Boolean) {
    fun validate() {
        require(Regex("[a-f0-9]{64}").matches(id))
        require(title.isNotBlank() && title.length <= 1024 && title.none { it == '\u0000' || it == '\n' || it == '\r' })
        require(bytes in 1..MAX_ARCHIVE_BYTES && pages in 1..100_000)
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024
    }
}

fun interface ArchiveDocumentFactory {
    /** The caller owns this immutable private backing file until the document is closed. */
    fun open(path: String): ArchiveDocument
}

/** Private image-directory manifest; filenames and external paths are never used as storage keys. */
@Serializable
data class DirectoryImage(val extension: String, val bytes: Long, val sha256: String) {
    fun validate() {
        require(extension in EXTENSIONS && bytes in 1..MAX_IMAGE_BYTES && Regex("[a-f0-9]{64}").matches(sha256))
    }

    companion object {
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        val EXTENSIONS = setOf("jpeg", "jpg", "png", "gif", "webp", "bmp", "ico", "wbmp", "heic", "heif", "avif")
    }
}

/** Unreadable copies stay on disk; one damaged index must not hide other local books. */
data class ArchiveLibrarySnapshot(val books: List<ArchiveBook>, val unavailableCopies: Int) {
    init {
        require(unavailableCopies >= 0 && books.size + unavailableCopies <= 500)
    }
}

interface ArchiveLibrary {
    suspend fun recent(): List<ArchiveBook>
    suspend fun inspect(): ArchiveLibrarySnapshot

    /** Copy the selected regular file; never give an external provider's mutable file to mmap. */
    suspend fun importFile(path: String, title: String): ArchiveBook
    suspend fun importDirectory(path: String, title: String): ArchiveBook
    suspend fun prepareExport(book: ArchiveBook): ArchiveExport
    suspend fun open(book: ArchiveBook): ArchiveDocument
    suspend fun remove(book: ArchiveBook)
    suspend fun close()
}

/** Independent immutable snapshot. The caller keeps it alive through the platform export UI. */
interface ArchiveExport {
    val name: String
    val path: String
    val isDirectory: Boolean
    val bytes: Long

    /** Create a new destination only. Interrupted external copies are retained, never overwritten. */
    suspend fun saveTo(path: String)
    suspend fun close()
}
