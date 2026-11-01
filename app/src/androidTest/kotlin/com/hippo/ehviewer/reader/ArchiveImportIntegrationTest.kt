package com.hippo.ehviewer.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ehviewer.core.data.FileArchiveLibrary
import com.ehviewer.core.network.archive.NativeArchiveBridge
import com.ehviewer.shared.archiveProductFixture
import com.ehviewer.shared.encryptedArchiveProductFixture
import com.ehviewer.shared.verifyArchivePassword
import com.ehviewer.shared.verifyArchiveProduct
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual APK JNI, immutable copies, Rust extraction, Bitmap pixels and persisted library reopen. */
@RunWith(AndroidJUnit4::class)
class ArchiveImportIntegrationTest {
    @Test fun realPrivateImportNaturalOrderPixelsReopenAndDeletePreserveOriginalInput() = runBlocking {
        System.loadLibrary("ehviewer")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "archive-product-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val input = File(root, "Local comic 资料.cbz")
        val fixture = archiveProductFixture()
        input.writeBytes(fixture)
        var library = FileArchiveLibrary(File(root, "books").toOkioPath(), NativeArchiveBridge::open)
        try {
            val encrypted = File(root, "Encrypted owned regression.zip").apply { writeBytes(encryptedArchiveProductFixture()) }
            verifyArchivePassword(NativeArchiveBridge.open(encrypted.absolutePath))
            val book = library.importFile(input.absolutePath, "Owned archive fixture")
            assertEquals(2, book.pages)
            assertEquals(3, verifyArchiveProduct(library.open(book)))
            library.close()
            library = FileArchiveLibrary(File(root, "books").toOkioPath(), NativeArchiveBridge::open)
            assertEquals(listOf(book), library.recent())
            assertEquals(book, library.importFile(input.absolutePath, "Retain original title"))
            assertEquals(3, verifyArchiveProduct(library.open(book)))
            library.remove(book)
            assertTrue(library.recent().isEmpty())
            assertArrayEquals(fixture, input.readBytes())
        } finally {
            library.close()
            assertEquals(0 to 0, NativeArchiveBridge.activeResources())
            check(root.deleteRecursively())
        }
    }
}
