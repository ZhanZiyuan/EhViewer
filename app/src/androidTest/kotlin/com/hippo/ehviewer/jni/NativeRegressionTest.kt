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

/** JNI compatibility and Rust ownership regressions against the packaged Android libraries. */
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
        for (name in listOf("stored.zip", "deflated.zip", "pages.tar", "pages.7z", "pages.tgz", "pages.txz")) checkArchive(name)
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

    @Test
    fun unicodePasswordsAndRar5() {
        for ((name, password, expected) in listOf(
            Triple("unicode.zip", "密码", "unicode password"),
            Triple("unicode-emoji.zip", "密码🔑", "unicode password"),
            Triple("stored.rar", "", "hello libarchive test suite!\n"),
        )) {
            val file = fixture(name)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                try {
                    assertEquals(1, openArchive(fd.fd, file.length(), true))
                    if (password.isNotEmpty()) {
                        assertTrue(needPassword())
                        assertFalse(providePassword("错误密码"))
                        assertTrue(providePassword(password))
                        assertFalse(providePassword("wrong-after-success"))
                    }
                    val buffer = requireNotNull(extractToByteBuffer(0))
                    try {
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        assertArrayEquals(expected.toByteArray(), bytes)
                    } finally {
                        releaseByteBuffer(buffer)
                    }
                } finally {
                    closeArchive()
                }
            }
            file.delete()
        }
    }

    @Test
    fun shortGifAndInvalidDescriptors() {
        for (size in 0..32) {
            val buffer = java.nio.ByteBuffer.allocateDirect(size)
            if (size >= 6) buffer.put("GIF89a".toByteArray())
            rewriteGifSource(buffer)
        }
        assertTrue(runCatching { rewriteGifSource(java.nio.ByteBuffer.allocate(8)) }.isFailure)
        assertTrue(runCatching { rewriteGifSource(java.nio.ByteBuffer.allocateDirect(8).asReadOnlyBuffer()) }.isFailure)
        assertTrue(runCatching { sha1(-1) }.isFailure)
        assertFalse(isGif(-1))
        assertEquals(null, mmap(-1))
        assertEquals(0, openArchive(-1, -1, true))
        assertEquals(null, extractToByteBuffer(-1))
        assertFalse(extractToFd(-1, -1))
        closeArchive()
        closeArchive()
    }

    @Test
    fun returnedBuffersOutliveCloseAndReopen() {
        for (name in listOf("stored.zip", "deflated.zip")) {
            val file = fixture(name)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                assertEquals(3, openArchive(fd.fd, file.length(), true))
                val first = requireNotNull(extractToByteBuffer(0))
                val second = requireNotNull(extractToByteBuffer(0))
                first.put(0, 'x'.code.toByte())
                closeArchive()
                assertEquals(3, openArchive(fd.fd, file.length(), true))
                assertEquals('o'.code.toByte(), second.get(0))
                assertEquals('x'.code.toByte(), first.get(0))
                releaseByteBuffer(first)
                releaseByteBuffer(first)
                releaseByteBuffer(second)
                closeArchive()
            }
            file.delete()
        }
    }

    @Test
    fun concurrentReadsAndRepeatedLifetimeDoNotLeakDescriptors() {
        val file = fixture("deflated.zip")
        val before = File("/proc/self/fd").list()?.size ?: 0
        repeat(30) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                try {
                    assertEquals(3, openArchive(fd.fd, file.length(), true))
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
                    try {
                        val jobs = (0..7).map { worker ->
                            pool.submit {
                                repeat(20) { n ->
                                    val index = (worker + n) % 3
                                    val buffer = requireNotNull(extractToByteBuffer(index))
                                    try {
                                        val bytes = ByteArray(buffer.capacity())
                                        buffer.get(bytes)
                                        assertArrayEquals(listOf("one", "two", "ten")[index].toByteArray(), bytes)
                                    } finally {
                                        releaseByteBuffer(buffer)
                                    }
                                }
                            }
                        }
                        jobs.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                    } finally {
                        pool.shutdownNow()
                    }
                } finally {
                    closeArchive()
                }
            }
        }
        assertTrue((File("/proc/self/fd").list()?.size ?: 0) <= before + 4)
        file.delete()
    }

    @Test
    fun archiveLimitsAndLargePage() {
        for (name in listOf("oversized.tar", "traversal.zip")) {
            val file = fixture(name)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                try {
                    assertEquals(0, openArchive(fd.fd, file.length(), true))
                } finally {
                    closeArchive()
                }
            }
            file.delete()
        }
        val file = fixture("large-deflated.zip")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            try {
                assertEquals(3, openArchive(fd.fd, file.length(), true))
                for (index in listOf(2, 0, 1, 0)) {
                    val buffer = requireNotNull(extractToByteBuffer(index))
                    try {
                        assertEquals(4 * 1024 * 1024, buffer.capacity())
                        val expected = listOf(1, 2, 10)[index].toByte()
                        repeat(buffer.capacity()) { assertEquals(expected, buffer.get(it)) }
                    } finally {
                        releaseByteBuffer(buffer)
                    }
                }
            } finally {
                closeArchive()
            }
        }
        file.delete()
    }

    @Test
    fun zipWriterValidatesArgumentsAndPreservesDescriptor() {
        val input = File(context.cacheDir, "batch-input").apply { writeText("xxxabc") }
        val output = File(context.cacheDir, "batch-result.zip")
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                assertTrue(runCatching { archiveFdBatch(intArrayOf(source.fd), arrayOf("../escape.jpg"), target.fd, 1) }.isFailure)
                assertTrue(runCatching { archiveFdBatch(intArrayOf(source.fd), arrayOf("page.jpg"), target.fd, 2) }.isFailure)
                Os.lseek(source.fileDescriptor, 3, OsConstants.SEEK_SET)
                archiveFdBatch(intArrayOf(source.fd), arrayOf("相册/page1.jpg"), target.fd, 1)
                assertEquals(6L, Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_CUR))
                try {
                    assertEquals(1, openArchive(target.fd, output.length(), true))
                    val buffer = requireNotNull(extractToByteBuffer(0))
                    try {
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        assertArrayEquals("abc".toByteArray(), bytes)
                    } finally {
                        releaseByteBuffer(buffer)
                    }
                } finally {
                    closeArchive()
                }
            }
        }
        input.delete()
        output.delete()
    }

    @Test
    fun compressedAndSolidRar5BackwardSeek() {
        val expected = org.json.JSONObject(instrumentation.context.assets.open("rar-expected.json").bufferedReader().use { it.readText() })
        for (name in listOf("stored.rar", "compressed.rar", "solid.rar", "multiple_files.rar")) {
            val file = fixture(name)
            val pages = expected.getJSONObject(name).getJSONArray("pages")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                try {
                    assertEquals(pages.length(), openArchive(fd.fd, file.length(), true))
                    repeat(4) {
                        for (index in (pages.length() - 1 downTo 0) + (0 until pages.length())) {
                            val buffer = requireNotNull(extractToByteBuffer(index))
                            try {
                                assertEquals(pages.getJSONObject(index).getInt("size"), buffer.capacity())
                                val bytes = ByteArray(buffer.capacity())
                                buffer.get(bytes)
                                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                                assertEquals(pages.getJSONObject(index).getString("sha256"), digest)
                            } finally {
                                releaseByteBuffer(buffer)
                            }
                        }
                    }
                } finally {
                    closeArchive()
                }
            }
            file.delete()
        }
    }

    @Test
    fun bitmapAndHardwareBufferBindings() {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        for (y in 8 until 24) for (x in 8 until 24) bitmap.setPixel(x, y, android.graphics.Color.BLACK)
        try {
            assertArrayEquals(intArrayOf(8, 8, 24, 24), com.hippo.ehviewer.image.detectBorder(bitmap))
            assertFalse(com.hippo.ehviewer.image.hasQrCode(bitmap))
            android.hardware.HardwareBuffer.create(8, 8, android.hardware.HardwareBuffer.RGBA_8888, 1, android.hardware.HardwareBuffer.USAGE_CPU_WRITE_OFTEN or android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).use { hardware ->
                com.hippo.ehviewer.image.copyBitmapToAHB(bitmap, hardware, 8, 8)
                assertTrue(runCatching { com.hippo.ehviewer.image.copyBitmapToAHB(bitmap, hardware, -1, 0) }.isFailure)
                // A failed call must not leave the buffer locked.
                com.hippo.ehviewer.image.copyBitmapToAHB(bitmap, hardware, 8, 8)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val wrapped = requireNotNull(android.graphics.Bitmap.wrapHardwareBuffer(hardware, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)))
                    try {
                        val readable = requireNotNull(wrapped.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                        try {
                            assertEquals(android.graphics.Color.BLACK, readable.getPixel(0, 0))
                        } finally {
                            readable.recycle()
                        }
                    } finally {
                        wrapped.recycle()
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
