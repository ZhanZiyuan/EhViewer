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
    @Test fun exportsPreserveCompressedBytesEncryptedContentAndDirectoryPixelsThroughRealJni() = runBlocking {
        System.loadLibrary("ehviewer")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "archive-export-product-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val source = File(root, "Owned comic.cbz").apply { writeBytes(archiveProductFixture()) }
        val library = FileArchiveLibrary(File(root, "books").toOkioPath(), NativeArchiveBridge::open)
        val reimport = FileArchiveLibrary(File(root, "reimport").toOkioPath(), NativeArchiveBridge::open)
        try {
            val book = library.importFile(source.absolutePath, "Owned export")
            val exported = File(root, "Exported comic.cbz")
            val snapshot = library.prepareExport(book)
            try {
                assertTrue(!snapshot.isDirectory && snapshot.name.endsWith(".cbz"))
                snapshot.saveTo(exported.absolutePath)
            } finally {
                snapshot.close()
            }
            assertArrayEquals(archiveProductFixture(), exported.readBytes())
            assertEquals(3, verifyArchiveProduct(NativeArchiveBridge.open(exported.absolutePath)))
            val images = NativeArchiveBridge.open(source.absolutePath).use { it.read(0) to it.read(1) }
            val input = File(root, "Owned image folder").apply { check(mkdir()) }
            File(input, "2.png").writeBytes(images.first)
            File(input, "10.png").writeBytes(images.second)
            val directoryBook = library.importDirectory(input.absolutePath, "Owned images")
            val folderExport = library.prepareExport(directoryBook)
            val folder = File(root, "Exported image folder")
            try {
                assertTrue(folderExport.isDirectory)
                folderExport.saveTo(folder.absolutePath)
            } finally {
                folderExport.close()
            }
            val imported = reimport.importDirectory(folder.absolutePath, "Exported round trip")
            assertEquals(directoryBook.id, imported.id)
            assertEquals(3, verifyArchiveProduct(reimport.open(imported)))
            val encrypted = File(root, "Owned encrypted.zip").apply { writeBytes(encryptedArchiveProductFixture()) }
            val protectedBook = library.importFile(encrypted.absolutePath, "Owned encrypted export")
            assertTrue(protectedBook.encrypted)
            val protectedExport = library.prepareExport(protectedBook)
            val encryptedOutput = File(root, "Exported encrypted.cbz")
            try {
                protectedExport.saveTo(encryptedOutput.absolutePath)
            } finally {
                protectedExport.close()
            }
            assertArrayEquals(encryptedArchiveProductFixture(), encryptedOutput.readBytes())
            verifyArchivePassword(NativeArchiveBridge.open(encryptedOutput.absolutePath))
            assertArrayEquals(archiveProductFixture(), source.readBytes())
            assertArrayEquals(images.first, File(input, "2.png").readBytes())
            assertArrayEquals(images.second, File(input, "10.png").readBytes())
        } finally {
            library.close()
            reimport.close()
            assertEquals(0 to 0, NativeArchiveBridge.activeResources())
            check(root.deleteRecursively())
        }
    }

    @Test fun damagedPrivateMetadataIsReportedWhileOtherBooksStillDecodeThroughRealJni() = runBlocking {
        System.loadLibrary("ehviewer")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "archive-recovery-product-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val input = File(root, "Owned source.cbz").apply { writeBytes(archiveProductFixture()) }
        val books = File(root, "books")
        val library = FileArchiveLibrary(books.toOkioPath(), NativeArchiveBridge::open)
        try {
            val book = library.importFile(input.absolutePath, "Healthy owned archive")
            val damaged = File(books, "d".repeat(64)).apply { check(mkdir()) }
            val damagedMetadata = byteArrayOf(0xff.toByte(), 1)
            File(damaged, "book.cbor").writeBytes(damagedMetadata)
            val inspected = library.inspect()
            assertEquals(listOf(book), inspected.books)
            assertEquals(1, inspected.unavailableCopies)
            assertEquals(3, verifyArchiveProduct(library.open(book)))
            assertArrayEquals(damagedMetadata, File(damaged, "book.cbor").readBytes())
            assertArrayEquals(archiveProductFixture(), input.readBytes())
            check(damaged.deleteRecursively())
            assertEquals(0, library.inspect().unavailableCopies)
            library.remove(book)
            assertTrue(library.inspect().books.isEmpty())
        } finally {
            library.close()
            assertEquals(0 to 0, NativeArchiveBridge.activeResources())
            check(root.deleteRecursively())
        }
    }

    @Test fun realImageFolderUsesBoundedAndroidEnumerationAndTheSharedOfflineReader() = runBlocking {
        System.loadLibrary("ehviewer")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "directory-product-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val fixture = File(root, "Owned source.cbz").apply { writeBytes(archiveProductFixture()) }
        val images = NativeArchiveBridge.open(fixture.absolutePath).use { it.read(0) to it.read(1) }
        val input = File(root, "Image folder 资料").apply { check(mkdirs()) }
        File(input, "10.png").writeBytes(images.second)
        File(input, "2.png").writeBytes(images.first)
        var library = FileArchiveLibrary(File(root, "books").toOkioPath(), NativeArchiveBridge::open)
        try {
            val book = library.importDirectory(input.absolutePath, "Owned image directory")
            assertEquals(2, book.pages)
            assertEquals(3, verifyArchiveProduct(library.open(book)))
            library.close()
            library = FileArchiveLibrary(File(root, "books").toOkioPath(), NativeArchiveBridge::open)
            assertEquals(listOf(book), library.recent())
            assertEquals(book, library.importDirectory(input.absolutePath, "Keep original directory title"))
            assertEquals(3, verifyArchiveProduct(library.open(book)))
            library.remove(book)
            assertTrue(library.recent().isEmpty())
            assertArrayEquals(images.first, File(input, "2.png").readBytes())
            assertArrayEquals(images.second, File(input, "10.png").readBytes())
        } finally {
            library.close()
            assertEquals(0 to 0, NativeArchiveBridge.activeResources())
            check(root.deleteRecursively())
        }
    }

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
