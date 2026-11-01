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
        check(second.importFile(input.toString(), "Duplicate keeps original title") == book)
        check(verifyArchiveProduct(second.open(book)) == 3)
        check(second.importDirectory(imageDirectory.toString(), "Keep original directory title") == directoryBook)
        check(verifyArchiveProduct(second.open(directoryBook)) == 3)
        second.remove(directoryBook)
        second.remove(book)
        check(second.recent().isEmpty())
        check(files.read(input) { readByteArray() }.contentEquals(fixture))
        check(files.read(imageDirectory / "2.png") { readByteArray() }.contentEquals(images.first))
        check(files.read(imageDirectory / "10.png") { readByteArray() }.contentEquals(images.second))
        return 6
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
