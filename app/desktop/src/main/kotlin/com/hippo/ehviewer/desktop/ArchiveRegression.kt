package com.hippo.ehviewer.desktop

import com.ehviewer.core.data.FileArchiveLibrary
import com.ehviewer.core.domain.reader.ArchiveDocumentFactory
import com.ehviewer.core.network.archive.NativeArchiveBridge
import com.ehviewer.shared.archiveProductFixture
import com.ehviewer.shared.encryptedArchiveProductFixture
import com.ehviewer.shared.verifyArchivePassword
import com.ehviewer.shared.verifyArchiveProduct
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath

/** Explicit real private import/pixel/reopen/delete regression; never inspect an existing user's files. */
fun verifyDesktopArchives(library: Path): Int = if (System.getProperty("os.name").startsWith("Windows")) {
    WindowsArchiveFactory(library).use { factory -> verifyArchiveLibrary(factory).also { check(factory.activeResources() == (0 to 0)) } }
} else {
    verifyArchiveLibrary(NativeArchiveBridge::open).also { check(NativeArchiveBridge.activeResources() == (0 to 0)) }
}

private fun verifyArchiveLibrary(factory: ArchiveDocumentFactory): Int = runBlocking {
    val directory = Files.createTempDirectory("ehviewer-archive-runtime-")
    val input = directory.resolve("Local comic 资料.cbz")
    val original = archiveProductFixture()
    Files.write(input, original)
    val first = FileArchiveLibrary(directory.resolve("books").toOkioPath(), factory)
    var reopened: FileArchiveLibrary? = null
    try {
        val encrypted = directory.resolve("Encrypted owned regression.zip")
        Files.write(encrypted, encryptedArchiveProductFixture())
        verifyArchivePassword(factory.open(encrypted.toString()))
        val book = first.importFile(input.toString(), "Owned archive regression")
        check(book.pages == 2 && book.bytes == original.size.toLong() && !book.encrypted)
        check(verifyArchiveProduct(first.open(book)) == 3)
        val images = factory.open(input.toString()).use { it.read(0) to it.read(1) }
        val imageDirectory = Files.createDirectory(directory.resolve("Image folder 资料"))
        Files.write(imageDirectory.resolve("10.png"), images.second)
        Files.write(imageDirectory.resolve("2.png"), images.first)
        val directoryBook = first.importDirectory(imageDirectory.toString(), "Owned directory regression")
        check(verifyArchiveProduct(first.open(directoryBook)) == 3)
        first.close()
        val second = FileArchiveLibrary(directory.resolve("books").toOkioPath(), factory)
        reopened = second
        check(second.recent() == listOf(book, directoryBook))
        val damaged = Files.createDirectory(directory.resolve("books").resolve("d".repeat(64)))
        val damagedMetadata = byteArrayOf(0xff.toByte(), 1)
        Files.write(damaged.resolve("book.cbor"), damagedMetadata)
        val inspected = second.inspect()
        check(inspected.books == listOf(book, directoryBook) && inspected.unavailableCopies == 1)
        check(Files.readAllBytes(damaged.resolve("book.cbor")).contentEquals(damagedMetadata))
        check(verifyArchiveProduct(second.open(directoryBook)) == 3)
        Files.delete(damaged.resolve("book.cbor"))
        Files.delete(damaged)
        check(second.inspect().unavailableCopies == 0)
        check(second.importFile(input.toString(), "Duplicate must keep the original title") == book)
        check(verifyArchiveProduct(second.open(book)) == 3)
        check(second.importDirectory(imageDirectory.toString(), "Keep the original folder title") == directoryBook)
        check(verifyArchiveProduct(second.open(directoryBook)) == 3)
        second.remove(directoryBook)
        second.remove(book)
        check(second.recent().isEmpty())
        check(Files.readAllBytes(input).contentEquals(original))
        check(Files.readAllBytes(imageDirectory.resolve("2.png")).contentEquals(images.first))
        check(Files.readAllBytes(imageDirectory.resolve("10.png")).contentEquals(images.second))
        6
    } finally {
        first.close()
        reopened?.close()
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
