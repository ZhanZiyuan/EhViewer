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

interface ArchiveLibrary {
    suspend fun recent(): List<ArchiveBook>

    /** Copy the selected regular file; never give an external provider's mutable file to mmap. */
    suspend fun importFile(path: String, title: String): ArchiveBook
    suspend fun open(book: ArchiveBook): ArchiveDocument
    suspend fun remove(book: ArchiveBook)
    suspend fun close()
}
