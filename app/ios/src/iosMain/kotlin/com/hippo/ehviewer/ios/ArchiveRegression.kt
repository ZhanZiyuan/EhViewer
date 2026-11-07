@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.core.data.FileArchiveLibrary
import com.ehviewer.shared.archiveProductFixture
import com.ehviewer.shared.encryptedArchiveProductFixture
import com.ehviewer.shared.verifyArchivePassword
import com.ehviewer.shared.verifyArchiveProduct
import com.hippo.ehviewer.rust.capi.EHV_OK
import com.hippo.ehviewer.rust.capi.ehv_active_archives
import kotlin.uuid.Uuid
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path.Companion.toPath

/** Explicit regression only: private real files, Rust C ABI, platform pixels and persisted reopen. */
internal suspend fun verifyAppleArchives(applicationDirectory: String): Int {
    val files = FileSystem.SYSTEM
    val directory = "$applicationDirectory/archive-regression-${Uuid.random()}".toPath()
    files.createDirectory(directory)
    val input = directory / "Local comic 资料.cbz"
    val fixture = archiveProductFixture()
    files.write(input) { write(fixture) }
    val first = FileArchiveLibrary(directory / "books", AppleArchiveFactory)
    var reopened: FileArchiveLibrary? = null
    try {
        val encrypted = directory / "Encrypted owned regression.zip"
        files.write(encrypted) { write(encryptedArchiveProductFixture()) }
        verifyArchivePassword(AppleArchiveFactory.open(encrypted.toString()))
        val book = first.importFile(input.toString(), "Owned archive regression")
        check(book.pages == 2 && book.bytes == fixture.size.toLong() && !book.encrypted)
        check(verifyArchiveProduct(first.open(book)) == 3)
        val images = AppleArchiveFactory.open(input.toString()).use { it.read(0) to it.read(1) }
        val imageDirectory = directory / "Image folder 资料"
        files.createDirectory(imageDirectory)
        files.write(imageDirectory / "10.png") { write(images.second) }
        files.write(imageDirectory / "2.png") { write(images.first) }
        val directoryBook = first.importDirectory(imageDirectory.toString(), "Owned directory regression")
        check(verifyArchiveProduct(first.open(directoryBook)) == 3)
        first.close()
        val second = FileArchiveLibrary(directory / "books", AppleArchiveFactory)
        reopened = second
        check(second.recent() == listOf(book, directoryBook))
        val damaged = directory / "books" / "d".repeat(64)
        files.createDirectory(damaged)
        val damagedMetadata = byteArrayOf(0xff.toByte(), 1)
        files.write(damaged / "book.cbor") { write(damagedMetadata) }
        val inspected = second.inspect()
        check(inspected.books == listOf(book, directoryBook) && inspected.unavailableCopies == 1)
        check(files.read(damaged / "book.cbor") { readByteArray() }.contentEquals(damagedMetadata))
        check(verifyArchiveProduct(second.open(directoryBook)) == 3)
        files.deleteRecursively(damaged, mustExist = true)
        check(second.inspect().unavailableCopies == 0)
        check(second.importFile(input.toString(), "Duplicate keeps original title") == book)
        check(verifyArchiveProduct(second.open(book)) == 3)
        check(second.importDirectory(imageDirectory.toString(), "Keep original directory title") == directoryBook)
        check(verifyArchiveProduct(second.open(directoryBook)) == 3)
        val exportedFile = directory / "Exported owned comic.cbz"
        val archiveExport = second.prepareExport(book)
        try {
            check(!archiveExport.isDirectory && archiveExport.name.endsWith(".cbz"))
            archiveExport.saveTo(exportedFile.toString())
        } finally {
            archiveExport.close()
        }
        check(files.read(exportedFile) { readByteArray() }.contentEquals(fixture))
        check(verifyArchiveProduct(AppleArchiveFactory.open(exportedFile.toString())) == 3)
        val exportedFolder = directory / "Exported owned images"
        val directoryExport = second.prepareExport(directoryBook)
        try {
            check(directoryExport.isDirectory)
            directoryExport.saveTo(exportedFolder.toString())
        } finally {
            directoryExport.close()
        }
        val reimport = FileArchiveLibrary(directory / "exported-reimport", AppleArchiveFactory)
        try {
            val imported = reimport.importDirectory(exportedFolder.toString(), "Exported image round trip")
            check(imported.id == directoryBook.id && verifyArchiveProduct(reimport.open(imported)) == 3)
        } finally {
            reimport.close()
        }
        val protectedBook = second.importFile(encrypted.toString(), "Owned encrypted export")
        val protectedExport = second.prepareExport(protectedBook)
        val encryptedOutput = directory / "Exported owned encrypted.cbz"
        try {
            check(protectedBook.encrypted)
            protectedExport.saveTo(encryptedOutput.toString())
        } finally {
            protectedExport.close()
        }
        check(files.read(encryptedOutput) { readByteArray() }.contentEquals(encryptedArchiveProductFixture()))
        verifyArchivePassword(AppleArchiveFactory.open(encryptedOutput.toString()))
        second.remove(protectedBook)
        second.remove(directoryBook)
        second.remove(book)
        check(second.recent().isEmpty())
        check(files.read(input) { readByteArray() }.contentEquals(fixture))
        check(files.read(imageDirectory / "2.png") { readByteArray() }.contentEquals(images.first))
        check(files.read(imageDirectory / "10.png") { readByteArray() }.contentEquals(images.second))
        return 9
    } finally {
        first.close()
        reopened?.close()
        memScoped {
            val count = alloc<ULongVar>()
            check(ehv_active_archives(count.ptr) == EHV_OK && count.value == 0uL)
        }
        files.deleteRecursively(directory, mustExist = true)
    }
}
