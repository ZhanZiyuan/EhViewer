@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.core.data.FileArchiveLibrary
import com.ehviewer.shared.archiveProductFixture
import com.ehviewer.shared.verifyArchiveProduct
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController

/** Startup regression only: real UIKit presentation/cancellation, actual files and Rust decoding. */
internal suspend fun verifyAppleArchiveSharing(applicationDirectory: String, presenter: () -> UIViewController?): Int = withContext(Dispatchers.Main) {
    val files = FileSystem.SYSTEM
    val root = "$applicationDirectory/archive-share-regression-${Uuid.random()}".toPath()
    files.createDirectory(root, mustCreate = true)
    val input = root / "Owned sharing comic.cbz"
    val fixture = archiveProductFixture()
    files.write(input) { write(fixture) }
    val books = root / "books"
    val library = FileArchiveLibrary(books, AppleArchiveFactory)
    val share = AppleArchiveShare(presenter)
    try {
        val book = library.importFile(input.toString(), "Owned sharing regression")
        val images = AppleArchiveFactory.open(input.toString()).use { it.read(0) to it.read(1) }
        val folder = root / "Owned sharing folder"
        files.createDirectory(folder, mustCreate = true)
        files.write(folder / "2.png") { write(images.first) }
        files.write(folder / "10.png") { write(images.second) }
        val directoryBook = library.importDirectory(folder.toString(), "Owned sharing images")
        fun snapshotPaths() = files.list(books).filter { it.name.startsWith(".export-") }
        val absent = AppleArchiveShare { null }
        try {
            check(runCatching { absent.share(library, book) }.exceptionOrNull() is IllegalStateException)
            check(snapshotPaths().isEmpty())
        } finally {
            absent.close()
        }
        // The same adapter must recover after cancellation for both archive and directory items.
        for ((index, selected) in listOf(book, directoryBook, book).withIndex()) {
            supervisorScope {
                val request = async { share.share(library, selected) }
                try {
                    withTimeout(15_000) {
                        while (true) {
                            if (request.isCompleted) {
                                request.await()
                                error("Share ended before presentation")
                            }
                            val sheet = presenter()?.presentedViewController as? UIActivityViewController
                            if (sheet != null && (index == 2 || !sheet.beingPresented && sheet.view.window != null)) break
                            delay(25)
                        }
                    }
                    check(snapshotPaths().size == 1)
                    share.close()
                    request.join()
                    check(request.isCancelled)
                    check(presenter()?.presentedViewController == null)
                    check(snapshotPaths().isEmpty())
                    check(verifyArchiveProduct(library.open(selected)) == 3)
                } finally {
                    withContext(NonCancellable) { request.cancelAndJoin() }
                }
            }
        }
        check(library.recent() == listOf(directoryBook, book)) { "Sharing changed the existing title-ordered library" }
        check(files.read(input) { readByteArray() }.contentEquals(fixture))
        check(files.read(folder / "2.png") { readByteArray() }.contentEquals(images.first))
        check(files.read(folder / "10.png") { readByteArray() }.contentEquals(images.second))
        4
    } finally {
        share.close()
        withContext(NonCancellable) {
            library.close()
            files.deleteRecursively(root, mustExist = true)
        }
    }
}
