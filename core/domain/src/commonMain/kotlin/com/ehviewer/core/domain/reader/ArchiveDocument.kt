package com.ehviewer.core.domain.reader

/** Owns an immutable private archive copy; returned pages are independent bounded byte copies. */
interface ArchiveDocument : ArchiveCatalog, AutoCloseable {
    fun read(index: Int): ByteArray
}

enum class ArchiveFailure { INVALID_ARCHIVE, STORAGE, LIMIT, CLOSED, IN_USE }

/** Keep filesystem paths, input names and passwords out of exposed native failure messages. */
class ArchiveException(val failure: ArchiveFailure) : Exception("Archive operation failed: $failure")

fun archiveFailure(status: Int): Nothing = throw ArchiveException(
    when (status) {
        2 -> ArchiveFailure.CLOSED
        4 -> ArchiveFailure.LIMIT
        8 -> ArchiveFailure.STORAGE
        else -> ArchiveFailure.INVALID_ARCHIVE
    },
)
