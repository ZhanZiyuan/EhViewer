package com.hippo.ehviewer.jni

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the original JNI contract against the libraries actually packaged for Android. */
@RunWith(AndroidJUnit4::class)
class NativeRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(name: String): File = File(context.cacheDir, "native-regression-$name").apply {
        instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }

    @Test
    fun hashPreservesCallerDescriptorAndReadsFromOffset() {
        val file = File(context.cacheDir, "native-regression-hash")
        for (data in listOf(byteArrayOf(), "abc".toByteArray(), ByteArray(17000) { it.toByte() })) {
            file.writeBytes(data)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val offset = if (data.size > 3) 3 else 0
                Os.lseek(fd.fileDescriptor, offset.toLong(), OsConstants.SEEK_SET)
                val expected = MessageDigest.getInstance("SHA-1").digest(data.copyOfRange(offset, data.size))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, sha1(fd.fd))
                assertEquals(data.size.toLong(), Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR))
            }
        }
        file.delete()
    }

    private fun checkArchive(name: String, encrypted: Boolean = false, sort: Boolean = true) {
        val file = fixture(name)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            try {
                assertEquals(name, 3, openArchive(fd.fd, file.length(), sort))
                assertEquals(encrypted, needPassword())
                if (encrypted) {
                    assertFalse(providePassword("wrong"))
                    assertTrue(providePassword("baseline"))
                }
                val content = if (sort) listOf("one", "two", "ten") else listOf("ten", "two", "one")
                val extensions = if (sort) listOf("gif", "jpg", "png") else listOf("png", "jpg", "gif")
                for (index in listOf(2, 0, 1, 2, 0)) {
                    val buffer = extractToByteBuffer(index)
                    assertNotNull(buffer)
                    requireNotNull(buffer)
                    try {
                        assertTrue(buffer.isDirect)
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        assertArrayEquals(content[index].toByteArray(), bytes)
                        assertEquals(extensions[index], getExtension(index))
                    } finally {
                        releaseByteBuffer(buffer)
                    }
                    val output = File(context.cacheDir, "native-regression-output")
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use {
                        assertTrue(extractToFd(index, it.fd))
                    }
                    assertArrayEquals(content[index].toByteArray(), output.readBytes())
                    output.delete()
                }
            } finally {
                closeArchive()
            }
        }
        file.delete()
    }

    @Test
    fun zipTarAndSevenZipRandomAccessAndNaturalSort() {
        for (name in listOf("stored.zip", "deflated.zip", "pages.tar", "pages.7z")) checkArchive(name)
        checkArchive("stored.zip", sort = false)
    }

    @Test
    fun aesAndZipCryptoPasswords() {
        checkArchive("password.zip", encrypted = true)
        checkArchive("zipcrypto.zip", encrypted = true)
    }

    @Test
    fun malformedArchiveHasNoEntries() {
        val file = fixture("broken.zip")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            try {
                assertEquals(0, openArchive(it.fd, file.length(), true))
            } finally {
                closeArchive()
            }
        }
        file.delete()
    }

    @Test
    fun gifMappingAndRewrite() {
        val file = File(context.cacheDir, "native-regression.gif")
        file.writeBytes(byteArrayOf(71, 73, 70, 56, 57, 97, 0, 33, -7, 4, 0, 1, 0, 0, 0))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            assertTrue(isGif(it.fd))
            val buffer = mmap(it.fd)
            requireNotNull(buffer)
            try {
                rewriteGifSource(buffer)
                assertEquals(10, buffer.get(11).toInt())
            } finally {
                munmap(buffer)
            }
        }
        file.delete()
    }
}
