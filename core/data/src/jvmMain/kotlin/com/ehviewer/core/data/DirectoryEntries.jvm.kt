package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path

internal actual suspend fun directoryNames(directory: Path, maximum: Int): List<String> {
    require(maximum >= 0)
    val names = mutableListOf<String>()
    Files.newDirectoryStream(Paths.get(directory.toString())).use { entries ->
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (names.size == maximum) throw ArchiveException(ArchiveFailure.LIMIT)
            names += entry.fileName.toString()
        }
    }
    return names
}
