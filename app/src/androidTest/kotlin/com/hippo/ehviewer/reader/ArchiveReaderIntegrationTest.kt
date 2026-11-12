package com.hippo.ehviewer.reader

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.jni.archiveSessionPageCount
import com.hippo.ehviewer.jni.archiveSessionRead
import com.hippo.ehviewer.jni.closeArchiveSession
import com.hippo.ehviewer.jni.openArchiveSession
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.jni.trimArchiveCaches
import com.hippo.ehviewer.reader.data.NativeArchiveReader
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveReaderIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(name: String) = File(context.cacheDir, "reader-$name").apply {
        instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }

    @Test
    fun storedPageSurvivesCloseAndReopen() {
        val file = fixture("stored.zip")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val first = NativeArchiveReader.open(fd.fd, file.length(), true)
                val page = first.read(0)
                try {
                    NativeArchiveReader.open(fd.fd, file.length(), true).use { second ->
                        assertEquals(3, first.pageCount)
                        first.close()
                        assertEquals(3, second.pageCount)
                        val bytes = ByteArray(page.buffer.remaining())
                        page.buffer.get(bytes)
                        assertEquals("one", bytes.decodeToString())
                    }
                } finally {
                    first.close()
                    page.close()
                    page.close()
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun closedHandleCannotReadReplacementSessionAndLivePageSurvivesTrim() {
        val file = fixture("stored.zip")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val old = openArchiveSession(fd.fd, file.length(), true)
                val page = requireNotNull(archiveSessionRead(old, 0, false))
                closeArchiveSession(old)
                val current = openArchiveSession(fd.fd, file.length(), true)
                try {
                    trimArchiveCaches()
                    assertEquals(null, archiveSessionRead(old, 0, false))
                    assertThrows(RuntimeException::class.java) { archiveSessionPageCount(old) }
                    assertEquals(3, archiveSessionPageCount(current))
                    assertEquals('o'.code.toByte(), page.get(0))
                } finally {
                    closeArchiveSession(current)
                    releaseByteBuffer(page)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun passwordAndFileOutputUseReaderPort() {
        val file = fixture("password.zip")
        val output = File(context.cacheDir, "reader-output")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                NativeArchiveReader.open(fd.fd, file.length(), true).use { reader ->
                    assertTrue(reader.needsPassword)
                    assertFalse(reader.providePassword("wrong"))
                    assertTrue(reader.providePassword("baseline"))
                    assertEquals("jpg", reader.extension(1))
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use {
                        assertTrue(reader.copyTo(1, it.fd))
                    }
                    assertEquals("two", output.readText())
                }
            }
        } finally {
            file.delete()
            output.delete()
        }
    }

    @Test
    fun pageLoaderRetainsItsSourceAfterReaderScopeCloses() = runBlocking {
        val file = fixture("stored.zip")
        var source: ByteBufferSource? = null
        try {
            useArchivePageLoader(file.absolutePath.toPath(), passwdProvider = { error("Unexpected password request") }) { loader ->
                assertEquals(3, loader.size)
                source = loader.openSource(2) as ByteBufferSource
            }
            val buffer = requireNotNull(source).source
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            assertEquals("ten", bytes.decodeToString())
        } finally {
            source?.close()
            source?.close()
            file.delete()
        }
    }

    @Test
    fun actualImageDecoderConsumesReadonlyImagesAndTheGifWorkaround() = runBlocking {
        val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val png = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val jpeg = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }

        @Suppress("DEPRECATION")
        val webp = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val gif = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT)
        val file = File(context.cacheDir, "reader-real-images.zip")
        try {
            for (stored in listOf(true, false)) {
                java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                    for ((index, data) in listOf(png, jpeg, gif, webp).withIndex()) {
                        val entry = java.util.zip.ZipEntry("page${index + 1}.${listOf("png", "jpg", "gif", "webp")[index]}")
                        if (stored) {
                            entry.method = java.util.zip.ZipEntry.STORED
                            entry.size = data.size.toLong()
                            entry.compressedSize = entry.size
                            entry.crc = java.util.zip.CRC32().apply { update(data) }.value
                        }
                        zip.putNextEntry(entry)
                        zip.write(data)
                        zip.closeEntry()
                    }
                }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    NativeArchiveReader.open(fd.fd, file.length(), true).use { reader ->
                        for (index in 0..3) {
                            val page = reader.read(index)
                            page.use {
                                if (index != 2) assertTrue(page.buffer.isReadOnly)
                                val image = Image.decode(byteBufferSource(page.buffer) { page.close() })
                                try {
                                    val expected = if (index == 2) 1 else 16
                                    assertEquals(expected, image.intrinsicSize.width)
                                    assertEquals(expected, image.intrinsicSize.height)
                                } finally {
                                    image.unpin()
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
