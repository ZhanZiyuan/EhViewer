@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path
import platform.posix.__error
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

internal actual suspend fun directoryNames(directory: Path, maximum: Int): List<String> {
    require(maximum >= 0)
    val handle = opendir(directory.toString()) ?: throw ArchiveException(ArchiveFailure.STORAGE)
    try {
        val names = mutableListOf<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            __error()!!.pointed.value = 0
            val entry = readdir(handle)
            if (entry == null) {
                if (__error()!!.pointed.value != 0) throw ArchiveException(ArchiveFailure.STORAGE)
                return names
            }
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            if (names.size == maximum) throw ArchiveException(ArchiveFailure.LIMIT)
            names += name
        }
    } finally {
        closedir(handle)
    }
}
