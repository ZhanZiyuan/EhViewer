package com.hippo.ehviewer.spider

import com.ehviewer.core.files.openFileDescriptor
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path

/** Verify the central directory, full payload checksums, entry order, and gallery completeness. */
internal suspend fun verifyCbz(file: Path, pages: Int, comicInfoName: String) {
    file.openFileDescriptor("r").use { descriptor ->
        verifyCbz(File("/proc/self/fd/${descriptor.fd}"), pages, comicInfoName)
    }
}

internal suspend fun verifyCbz(file: File, pages: Int, comicInfoName: String) {
    check(pages > 0)
    ZipFile(file).use { zip ->
        check(zip.size() == pages + 1) { "CBZ entry count does not match gallery" }
        val entries = zip.entries()
        val bytes = ByteArray(64 * 1024)
        repeat(pages + 1) { index ->
            coroutineContext.ensureActive()
            val entry = entries.nextElement()
            val expectedName = if (index == pages) {
                entry.name == comicInfoName
            } else {
                val extension = entry.name.removePrefix(perFilename(index))
                extension.isNotEmpty() && extension.all { it.isLetterOrDigit() } &&
                    entry.name == perFilename(index, extension)
            }
            check(expectedName && entry.method == ZipEntry.STORED && entry.size > 0) {
                "Invalid CBZ entry at position $index"
            }
            val crc = CRC32()
            var size = 0L
            zip.getInputStream(entry).use { stream ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(bytes)
                    if (count == -1) break
                    check(count > 0) { "CBZ read made no progress" }
                    crc.update(bytes, 0, count)
                    size += count
                    check(size <= entry.size) { "CBZ entry exceeds its declared size" }
                }
            }
            check(size == entry.size && crc.value == entry.crc) { "Invalid CBZ checksum for ${entry.name}" }
        }
    }
}
