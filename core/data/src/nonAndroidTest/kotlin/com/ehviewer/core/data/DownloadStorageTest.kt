package com.ehviewer.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.ehviewer.core.database.download.DownloadDatabase
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadMetadata
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
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

internal expect fun fileDownloadDatabase(path: String): DownloadDatabase
internal expect fun rawDownloadConnection(path: String): SQLiteConnection

class DownloadStorageTest {
    private fun task() = GalleryDownload(1, "0123456789", "Owned 日本語", 2, false, GalleryDownloadState.DOWNLOADING)

    @Test fun schemaOneMigrationPreservesCommittedImagesAndAddsOptionalPublicMetadata() = runTest {
        val owned = ownedDatabaseFile()
        val path = owned.path + "-downloads"
        val images = (owned.path + "-images").toPath()
        val original = byteArrayOf(1, 2, 3, 4)
        FileSystem.SYSTEM.createDirectories(images / "1")
        FileSystem.SYSTEM.write(images / "1" / "page-0.bin") { write(original) }
        // Exact v1 schema/identity from the retained Room schema, not a new-version fixture.
        rawDownloadConnection(path).use { connection ->
            connection.execSQL("CREATE TABLE IF NOT EXISTS `TASKS` (`gid` INTEGER NOT NULL, `token` TEXT NOT NULL, `title` TEXT NOT NULL, `pages` INTEGER NOT NULL, `restricted` INTEGER NOT NULL, `state` TEXT NOT NULL, `finished` INTEGER NOT NULL, `failure` TEXT, `position` INTEGER NOT NULL, PRIMARY KEY(`gid`))")
            connection.execSQL("CREATE TABLE IF NOT EXISTS `PAGES` (`gid` INTEGER NOT NULL, `index` INTEGER NOT NULL, `token` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, PRIMARY KEY(`gid`, `index`), FOREIGN KEY(`gid`) REFERENCES `TASKS`(`gid`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            connection.execSQL("INSERT INTO room_master_table VALUES (42,'f6a8cdf9993ed897d4ef43629e702acf')")
            connection.execSQL("INSERT INTO TASKS VALUES (1,'0123456789','Owned old book',2,0,'PAUSED',1,NULL,7)")
            connection.execSQL("INSERT INTO PAGES VALUES (1,0,'0123456789',4,'${original.toByteString().sha256().hex()}')")
            connection.execSQL("PRAGMA user_version=1")
        }
        var db = fileDownloadDatabase(path)
        try {
            var storage = RoomGalleryDownloadStorage(db, images)
            val old = storage.list().single()
            assertNull(old.metadata)
            assertEquals(1, old.finished)
            assertEquals(7L, old.position)
            assertContentEquals(original, storage.readPage(1, 0))
            val metadata = GalleryDownloadMetadata("Owned artist", false, 4.5f, 512)
            storage.save(old.copy(metadata = metadata))
            db.close()
            db = fileDownloadDatabase(path)
            storage = RoomGalleryDownloadStorage(db, images)
            assertEquals(old.copy(metadata = metadata), storage.list().single())
            assertContentEquals(original, storage.readPage(1, 0))
            assertEquals(1, db.downloads().pages(1).size)
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }

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
