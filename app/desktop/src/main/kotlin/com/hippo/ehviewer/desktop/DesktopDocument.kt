package com.hippo.ehviewer.desktop

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveLibrary
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun desktopDocumentPath(arguments: Array<String>): Path {
    require(arguments.size == 2 && arguments[0] == "--open" && arguments[1].isNotBlank()) { "Expected --open <comic-archive-or-image-directory>" }
    return Path.of(arguments[1]).toAbsolutePath().normalize()
}

/** Reuse private copying and format validation; the external document is never used as backing storage. */
internal suspend fun importDesktopDocument(library: ArchiveLibrary, path: Path): ArchiveBook {
    val title = (path.fileName?.toString() ?: "Imported images").replace('\n', ' ').replace('\r', ' ').take(1024)
    return if (Files.isDirectory(path, NOFOLLOW_LINKS)) library.importDirectory(path.toString(), title) else library.importFile(path.toString(), title)
}
