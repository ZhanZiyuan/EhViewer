/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.util

import com.ehviewer.core.files.write
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.rethrowCloseCauseIfNeeded
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Sink
import okio.Path

object FileUtils {
    // Workaround for filename length limit on some devices
    // https://github.com/FooIbar/EhViewer/issues/2607
    private const val MAX_FILENAME_BYTES = 200

    fun ensureDirectory(file: File?) = file?.let { if (it.exists()) it.isDirectory else it.mkdirs() } == true

    /**
     * Convert byte to human readable string.
     *
     * @param bytes the bytes to convert
     * @return the human readable string
     */
    fun humanReadableByteCount(bytes: Long): String {
        val units = "KMGTPE"
        var exp = -1
        var result = bytes.toDouble()
        while (result >= 1024.0 && exp < units.length - 1) {
            exp++
            result /= 1024.0
        }
        return if (exp == -1) "$bytes B" else "%.1f %siB".format(result, units[exp])
    }

    fun cleanupDirectory(dir: File?, maxFiles: Int = 10) {
        dir?.listFiles()?.let { files ->
            files.sortByDescending { it.lastModified() }
            files.forEachIndexed { index, file ->
                if (index >= maxFiles) file.delete()
            }
        }
    }

    private fun isValidFatFilenameChar(c: Char) = when (c.code) {
        in 0x00..0x1F,
        '"'.code,
        '*'.code,
        '/'.code,
        ':'.code,
        '<'.code,
        '>'.code,
        '?'.code,
        '\\'.code,
        '|'.code,
        0x7F,
        -> false
        else -> true
    }

    /**
     * Mutate the given filename to make it valid for a FAT filesystem,
     * replacing any invalid characters with "_".
     *
     * @param name the original filename
     */
    // From https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/os/FileUtils.java;l=1142;drc=7b647e4ea0e92f33c19b315eaed364ee067ba0aa
    fun sanitizeFilename(name: String): String {
        // Windows does not support filenames ending with a space or a period
        val name = name.trimEnd(' ', '.')
        check(name.isNotEmpty()) { "Invalid filename" }
        return buildString(name.length) {
            name.forEach {
                if (isValidFatFilenameChar(it)) {
                    append(it)
                } else {
                    append('_')
                }
            }
            if (sizeInBytes > MAX_FILENAME_BYTES) {
                while (sizeInBytes > MAX_FILENAME_BYTES - 3) {
                    deleteCharAt(length / 2)
                }
                insert(length / 2, "...")
            }
        }
    }

    private val StringBuilder.sizeInBytes
        get() = toString().toByteArray().size

    /**
     * Get extension from filename
     *
     * @param filename the complete filename
     * @return null for can't find extension
     */
    fun getExtensionFromFilename(filename: String?) = filename?.substringAfterLast('.', "")?.ifEmpty { null }

    /**
     * Get name from filename
     *
     * @param filename the complete filename
     * @return null for start with . dot
     */
    fun getNameFromFilename(filename: String?) = filename?.substringBeforeLast('.')?.ifEmpty { null }
}

@OptIn(InternalAPI::class)
suspend fun ByteReadChannel.copyTo(file: Path) {
    file.write {
        while (!isClosedForRead) {
            transferFrom(readBuffer)
            awaitContent()
        }
        rethrowCloseCauseIfNeeded()
    }
}

/** Hash the same bytes that are written; the caller still owns and closes the sink. */
@OptIn(InternalAPI::class)
internal suspend fun ByteReadChannel.copyWithSha1(sink: Sink): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val bytes = ByteArray(64 * 1024)
    while (true) {
        coroutineContext.ensureActive()
        if (!awaitContent()) break
        val size = readAvailable(bytes, 0, bytes.size)
        if (size < 0) break
        if (size == 0) throw IOException("Image response read made no progress")
        sink.write(bytes, 0, size)
        digest.update(bytes, 0, size)
    }
    rethrowCloseCauseIfNeeded()
    coroutineContext.ensureActive()
    return digest.digest().toHexString()
}
