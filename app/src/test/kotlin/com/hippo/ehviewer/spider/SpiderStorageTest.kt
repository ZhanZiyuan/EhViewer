package com.hippo.ehviewer.spider

import com.hippo.ehviewer.util.copyWithSha1
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.Path.Companion.toPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpiderStorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun indexesAlternateExtensionsAndRefreshRemovesStalePaths() {
        val jpg = "/downloads/00000001.jpg".toPath()
        val webp = "/cache/00000001.webp".toPath()
        val partial = "/cache/00000001.png.tmp".toPath()
        val index = PageFileIndex()
        index.refresh(sequenceOf(jpg, webp, partial, "/cache/ComicInfo.xml".toPath()))
        assertEquals(listOf(jpg, webp), index.candidates(0, temporary = false))
        assertEquals(listOf(partial), index.candidates(0, temporary = true))
        index.remove(jpg)
        assertEquals(listOf(webp), index.candidates(0, temporary = false))
        index.refresh(sequenceOf(jpg))
        assertEquals(listOf(jpg), index.candidates(0, temporary = false))
        assertTrue(index.candidates(0, temporary = true).isEmpty())
    }

    @Test
    fun hashesExactlyTheBytesWritten() = runBlocking {
        val sink = Buffer()
        val bytes = "abc".encodeToByteArray()
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ByteReadChannel(bytes).copyWithSha1(sink))
        assertArrayEquals(bytes, sink.readByteArray())
    }

    @Test
    fun cancelledCopyDoesNotReportAHash() = runBlocking {
        val channel = ByteChannel()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            channel.copyWithSha1(Buffer())
            error("Cancellation should not commit a digest")
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun cbzValidationRejectsCorruptPayloadAndMissingCentralDirectory() = runBlocking {
        val good = folder.newFile("valid.cbz")
        writeStoredArchive(good, listOf("00000001.jpg", "ComicInfo.xml"))
        verifyCbz(good, 1, "ComicInfo.xml")
        val corrupt = folder.newFile("corrupt.part")
        good.copyTo(corrupt, overwrite = true)
        RandomAccessFile(corrupt, "rw").use {
            it.seek(30L + "00000001.jpg".length)
            it.writeByte(0)
        }
        assertTrue(runCatching { verifyCbz(corrupt, 1, "ComicInfo.xml") }.isFailure)
        RandomAccessFile(corrupt, "rw").use { it.setLength(36L) }
        assertTrue(runCatching { verifyCbz(corrupt, 1, "ComicInfo.xml") }.isFailure)
    }

    @Test
    fun cbzValidationRequiresOrderedPagesAndComicInfo() = runBlocking {
        val bad = folder.newFile("unordered.part")
        writeStoredArchive(bad, listOf("00000002.jpg", "00000001.jpg", "ComicInfo.xml"))
        assertTrue(runCatching { verifyCbz(bad, 2, "ComicInfo.xml") }.isFailure)
    }

    @Test
    fun fingerprintsIncludeLengthAndContent() = runBlocking {
        val original = "archive data".byteInputStream().use { copyCbz(it) }
        assertEquals("12", original.substringBefore(':'))
        assertEquals(original, "archive data".byteInputStream().use { copyCbz(it) })
        assertTrue(original != "archive Data".byteInputStream().use { copyCbz(it) })
        assertTrue(original != "archive dat".byteInputStream().use { copyCbz(it) })
    }

    private fun writeStoredArchive(file: File, names: List<String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            names.forEach { name ->
                val bytes = name.encodeToByteArray()
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
