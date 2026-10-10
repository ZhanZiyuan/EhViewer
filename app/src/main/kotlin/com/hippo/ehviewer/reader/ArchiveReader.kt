package com.hippo.ehviewer.reader

import com.ehviewer.core.domain.reader.ArchiveCatalog
import java.nio.ByteBuffer

/** Android reader port. Descriptor and buffer adaptation belongs to its implementation. */
interface ArchiveReader : ArchiveCatalog, AutoCloseable {
    fun read(index: Int): ArchivePage
    fun copyTo(index: Int, outputFd: Int): Boolean
}

/** The original direct buffer remains owned until this page is closed. */
interface ArchivePage : AutoCloseable {
    val buffer: ByteBuffer
}
