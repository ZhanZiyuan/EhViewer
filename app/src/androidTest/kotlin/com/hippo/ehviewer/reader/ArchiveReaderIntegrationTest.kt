package com.hippo.ehviewer.reader

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.image.ByteBufferSource
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
                    assertThrows(IllegalStateException::class.java) { NativeArchiveReader.open(fd.fd, file.length(), true) }
                    first.close()
                    NativeArchiveReader.open(fd.fd, file.length(), true).use { second ->
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
}
