package com.ehviewer.core.data

import com.ehviewer.core.database.download.DownloadDatabase
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

internal expect fun fileDownloadDatabase(path: String): DownloadDatabase

class DownloadStorageTest {
    private fun task() = GalleryDownload(1, "0123456789", "Owned 日本語", 2, false, GalleryDownloadState.DOWNLOADING)

    @Test fun committedImagesAndSeparateRoomQueueSurviveClosingAndReopening() = runTest {
        val owned = ownedDatabaseFile()
        val path = owned.path + "-downloads"
        val images = (owned.path + "-images").toPath()
        var db = fileDownloadDatabase(path)
        try {
            var storage = RoomGalleryDownloadStorage(db, images)
            storage.save(task())
            storage.writePage(1, 0, "0123456789", byteArrayOf(1, 2, 3))
            // Simulate termination after image+page commit and before the visible count update.
            assertEquals(0, storage.list().single().finished)
            db.close()
            db = fileDownloadDatabase(path)
            storage = RoomGalleryDownloadStorage(db, images)
            assertEquals(listOf(0), storage.pages(1).map { it.index })
            assertContentEquals(byteArrayOf(1, 2, 3), storage.readPage(1, 0))
            storage.save(task().copy(finished = 1, state = GalleryDownloadState.PAUSED))
            assertEquals(1, storage.list().single().finished)
            storage.remove(1)
            assertTrue(storage.list().isEmpty())
            assertFalse(FileSystem.SYSTEM.exists(images / "1"))
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun failedAtomicImageMoveNeverCreatesAPageRecordOrDamagesThePreviousPage() = runTest {
        val owned = ownedDatabaseFile()
        val db = fileDownloadDatabase(owned.path + "-downloads")
        val images = (owned.path + "-images").toPath()
        val failing = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                if (target.name == "page-1.bin") throw IOException("test-only-private-path")
                super.atomicMove(source, target)
            }
        }
        try {
            val storage = RoomGalleryDownloadStorage(db, images, failing)
            storage.save(task())
            storage.writePage(1, 0, "0123456789", byteArrayOf(1, 2))
            val failure = assertFailsWith<GalleryDownloadStorageException> { storage.writePage(1, 1, "abcdef0123", byteArrayOf(3, 4)) }
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("private"))
            assertEquals(listOf(0), storage.pages(1).map { it.index })
            assertContentEquals(byteArrayOf(1, 2), storage.readPage(1, 0))
            assertNull(db.downloads().page(1, 1))
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun sameSizeImageCorruptionIsDetectedAndPreservedForExplicitRecovery() = runTest {
        val owned = ownedDatabaseFile()
        val db = fileDownloadDatabase(owned.path + "-downloads")
        val images = (owned.path + "-images").toPath()
        try {
            val storage = RoomGalleryDownloadStorage(db, images)
            storage.save(task())
            storage.writePage(1, 0, "0123456789", byteArrayOf(1, 2))
            FileSystem.SYSTEM.write(images / "1" / "page-0.bin") { write(byteArrayOf(9, 9)) }
            assertFailsWith<GalleryDownloadStorageException> { storage.readPage(1, 0) }
            assertContentEquals(byteArrayOf(9, 9), FileSystem.SYSTEM.read(images / "1" / "page-0.bin") { readByteArray() })
            assertEquals(1, db.downloads().pages(1).size)
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun cancellationBetweenRealFileAndDatabaseCommitRollsBackAndAllowsRetry() = runTest {
        val owned = ownedDatabaseFile()
        val db = fileDownloadDatabase(owned.path + "-downloads")
        val images = (owned.path + "-images").toPath()
        val writer = Job()
        var cancel = true
        val cancelling = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                super.atomicMove(source, target)
                if (cancel) writer.cancel()
            }
        }
        try {
            val storage = RoomGalleryDownloadStorage(db, images, cancelling)
            storage.save(task())
            assertFailsWith<CancellationException> { withContext(writer) { storage.writePage(1, 0, "0123456789", byteArrayOf(1, 2)) } }
            assertNull(db.downloads().page(1, 0))
            cancel = false
            storage.writePage(1, 0, "0123456789", byteArrayOf(3, 4))
            assertContentEquals(byteArrayOf(3, 4), storage.readPage(1, 0))
        } finally {
            writer.cancelAndJoin()
            db.close()
            FileSystem.SYSTEM.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }
}
