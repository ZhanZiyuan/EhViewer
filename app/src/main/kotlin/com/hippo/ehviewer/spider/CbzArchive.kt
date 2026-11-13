package com.hippo.ehviewer.spider

import android.provider.DocumentsContract
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.openInputStream
import com.ehviewer.core.files.openOutputStream
import com.ehviewer.core.files.toUri
import com.ehviewer.core.util.logcat
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

private val cbzStagingDirectory by lazy {
    File(appCtx.cacheDir, "cbz-staging").apply {
        check(deleteRecursively() && mkdir()) { "Unable to prepare CBZ staging directory" }
    }
}

internal suspend fun <T> withCbzTemp(block: suspend (File) -> T): T = useCbzStage(File.createTempFile("ehviewer-", ".cbz.part", cbzStagingDirectory), block)

private suspend fun <T> useCbzStage(file: File, block: suspend (File) -> T): T {
    try {
        return block(file)
    } finally {
        if (!file.delete() && file.exists()) logcat("CbzArchive") { "Unable to remove local CBZ staging file" }
    }
}

internal suspend fun copyCbz(input: InputStream, output: OutputStream? = null, maxBytes: Long = Long.MAX_VALUE): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count == -1) break
        check(count > 0) { "CBZ stream made no progress" }
        check(count <= maxBytes - size) { "CBZ stream exceeds its expected length" }
        output?.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        size += count
    }
    coroutineContext.ensureActive()
    return "$size:${digest.digest().toHexString()}"
}

internal suspend fun copyVerifiedCbz(file: File, target: Path, create: Boolean, fingerprint: String) {
    coroutineContext.ensureActive()
    val copied = file.inputStream().use { source -> target.openOutputStream(create).use { copyCbz(source, it) } }
    check(copied == fingerprint) { "Local CBZ changed during copy" }
    val actual = target.openInputStream().use { copyCbz(it, maxBytes = file.length()) }
    check(actual == fingerprint) { "CBZ readback differs from the verified archive" }
}

internal fun Path.cbzSidecar(suffix: String) = checkNotNull(parent).resolve(name + suffix)

internal fun Path.isSameCbzDocument(other: Path): Boolean {
    if (this == other) return true
    if (isAbsolute && other.isAbsolute) return toFile().canonicalFile == other.toFile().canonicalFile
    if (isAbsolute || other.isAbsolute) return false
    val first = toUri()
    val second = other.toUri()
    return first.authority == second.authority && DocumentsContract.isDocumentUri(appCtx, first) &&
        DocumentsContract.isDocumentUri(appCtx, second) &&
        DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
}

// The marker hides incomplete copies from the reader and identifies files we may resume.
internal class CbzCommit(private val target: Path) {
    private val pending = target.cbzSidecar(".pending")

    suspend fun recover(pages: Int): Boolean {
        if (!target.exists()) return false
        val marker = pendingMarker()
        if (marker != null && marker != LEGACY_CBZ_MARKER && !CbzFingerprint.matches(marker)) return false
        val verified = withLocalCbz(target) { local ->
            if (marker != null && marker != LEGACY_CBZ_MARKER && local.inputStream().use { copyCbz(it) } != marker) {
                return@withLocalCbz false
            }
            verifyCbz(local, pages, COMIC_INFO_FILE)
            true
        }
        if (!verified) return false
        coroutineContext.ensureActive()
        finish()
        return true
    }

    suspend fun write(pages: Int, onStage: (String) -> Unit = {}, build: suspend (File) -> Unit) {
        // Never overwrite a pre-existing archive that is not owned by an interrupted commit.
        if (target.exists()) {
            check(pendingFingerprint() != null && canReplaceIncompleteArchive()) {
                "Existing CBZ could not be safely replaced"
            }
        }
        val stage: suspend (File) -> Unit = { staged ->
            build(staged)
            onStage("validation")
            verifyCbz(staged, pages, COMIC_INFO_FILE)
            onStage("commit")
            val fingerprint = staged.inputStream().use { copyCbz(it) }
            mark(fingerprint)
            if (target.isAbsolute) {
                coroutineContext.ensureActive()
                staged.toOkioPath() moveTo target
            } else {
                copyVerifiedCbz(staged, target, create = true, fingerprint = fingerprint)
            }
            coroutineContext.ensureActive()
            finish()
        }
        if (target.isAbsolute) {
            val file = target.cbzSidecar(".part").toFile()
            check(!file.exists() || file.delete()) { "Unable to clear stale CBZ staging file" }
            useCbzStage(file, stage)
        } else {
            withCbzTemp(stage)
        }
    }

    private suspend fun canReplaceIncompleteArchive(): Boolean = try {
        // Preserve a readable existing ZIP even if its page count or marker no longer matches.
        withLocalCbz(target) { ZipFile(it).use { false } }
    } catch (_: ZipException) {
        true
    }

    private fun pendingFingerprint() = pendingMarker()?.takeIf(CbzFingerprint::matches)

    private fun pendingMarker(): String? {
        if (!pending.exists()) return null
        return pending.openInputStream().buffered().use { input ->
            buildString {
                repeat(128) {
                    val byte = input.read()
                    if (byte == -1) return@buildString
                    append(byte.toChar())
                }
            }
        }
    }

    private fun mark(fingerprint: String) {
        pending.openOutputStream().use { it.write(fingerprint.encodeToByteArray()) }
        check(pendingFingerprint() == fingerprint) { "Unable to create CBZ commit marker" }
    }

    private fun finish() {
        pending.delete()
        check(!pending.exists()) { "Unable to clear CBZ commit marker" }
        runCatching { target.cbzSidecar(".part").delete() }
            .onFailure { logcat("CbzArchive") { "Unable to remove an old CBZ staging file" } }
    }
}

private val CbzFingerprint = Regex("[0-9]+:[0-9a-f]{64}")
private const val LEGACY_CBZ_MARKER = "CBZ commit in progress"

internal suspend fun verifyCbz(file: Path, pages: Int, comicInfoName: String) {
    withLocalCbz(file) { verifyCbz(it, pages, comicInfoName) }
}

internal suspend fun <T> withLocalCbz(file: Path, block: suspend (File) -> T): T = if (file.isAbsolute) {
    block(file.toFile())
} else {
    withCbzTemp { staged ->
        file.openInputStream().use { source -> staged.outputStream().use { copyCbz(source, it) } }
        block(staged)
    }
}

/** Verify the central directory, full payload checksums, entry order, and gallery completeness. */
internal suspend fun verifyCbz(file: File, expectedPages: Int?, comicInfoName: String) {
    ZipFile(file).use { zip ->
        val pages = zip.size() - 1
        check(pages > 0 && (expectedPages == null || pages == expectedPages)) { "CBZ entry count does not match gallery" }
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
