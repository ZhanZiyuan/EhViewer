package com.ehviewer.core.data

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.domain.download.GalleryImageCheckpoint
import com.ehviewer.core.domain.download.GalleryImageIdentity
import com.ehviewer.core.domain.download.GalleryImageProgress.Companion.CHUNK_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

class PartialDownloadStorageTest {
    private fun task() = GalleryDownload(1, "0123456789", "Owned", 2, false, GalleryDownloadState.DOWNLOADING)
    private fun identity(total: Int) = GalleryImageIdentity("1111111111", "a".repeat(64), "b".repeat(64), "\"owned-validator\"", total.toLong())

    @Test fun realPrefixAndPartialChunkHashesSurviveRoomAndFileReopen() = runTest {
        val owned = ownedDatabaseFile()
        val path = owned.path + "-partial-db"
        val directory = (owned.path + "-partial-images").toPath()
        val bytes = ByteArray(CHUNK_BYTES * 2 + 93) { (it % 251).toByte() }
        var db = fileDownloadDatabase(path)
        try {
            var storage = RoomGalleryDownloadStorage(db, directory)
            storage.save(task())
            var expired: GalleryImageCheckpoint? = null
            storage.withPartialPage(1, 0) { image ->
                expired = image
                image.reset(identity(bytes.size))
                image.append(bytes.copyOfRange(0, 19))
                image.append(bytes.copyOfRange(19, CHUNK_BYTES + 19))
                assertEquals(2, image.progress().chunks.size)
                storage.save(task().copy(state = GalleryDownloadState.PAUSED))
                // A worker admitted before pause may publish its already-received tail.
                image.append(bytes.copyOfRange(CHUNK_BYTES + 19, CHUNK_BYTES + 53))
            }
            assertFailsWith<GalleryDownloadStorageException> { expired!!.progress() }
            db.close()
            db = fileDownloadDatabase(path)
            storage = RoomGalleryDownloadStorage(db, directory)
            storage.withPartialPage(1, 0) { image ->
                assertEquals((CHUNK_BYTES + 53).toLong(), image.progress().bytes)
                image.append(bytes.copyOfRange(CHUNK_BYTES + 53, CHUNK_BYTES * 2 + 53))
                image.append(bytes.copyOfRange(CHUNK_BYTES * 2 + 53, bytes.size))
                assertFalse(image.progress().complete)
                assertFailsWith<GalleryDownloadStorageException> { image.readComplete() }
            }
            // A failed admitted operation cannot be reused; a fresh owner validates the bytes again.
            storage.withPartialPage(1, 0) { image ->
                image.finish()
                assertContentEquals(bytes, image.readComplete())
                assertTrue(image.progress().complete)
            }
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun failedMetadataPublicationKeepsTheOldPrefixAndTruncatesOnlyItsUnpublishedTail() = runTest {
        val owned = ownedDatabaseFile()
        val directory = (owned.path + "-partial-images").toPath()
        val db = fileDownloadDatabase(owned.path + "-partial-db")
        var fail = false
        val files = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                if (fail && target.name == "partial-1.cbor") throw IOException("owned failure")
                super.atomicMove(source, target)
            }
        }
        try {
            val storage = RoomGalleryDownloadStorage(db, directory, files)
            storage.save(task())
            storage.writePage(1, 0, "0123456789", byteArrayOf(9, 8))
            storage.withPartialPage(1, 1) { image ->
                image.reset(identity(5))
                image.append(byteArrayOf(1, 2))
            }
            fail = true
            assertFailsWith<GalleryDownloadStorageException> { storage.withPartialPage(1, 1) { it.append(byteArrayOf(3, 4)) } }
            assertEquals(4L, FileSystem.SYSTEM.metadata(directory / "1" / "partial-1.data").size)
            fail = false
            storage.withPartialPage(1, 1) { image ->
                assertEquals(2, image.progress().bytes.toInt())
                assertEquals(2L, FileSystem.SYSTEM.metadata(directory / "1" / "partial-1.data").size)
                image.append(byteArrayOf(3, 4, 5))
                image.finish()
                assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), image.readComplete())
            }
            assertContentEquals(byteArrayOf(9, 8), storage.readPage(1, 0))
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun corruptedPrefixIsRejectedWithoutTruncatingItsEvidenceOrCommittedPages() = runTest {
        val owned = ownedDatabaseFile()
        val directory = (owned.path + "-partial-images").toPath()
        val db = fileDownloadDatabase(owned.path + "-partial-db")
        try {
            val storage = RoomGalleryDownloadStorage(db, directory)
            storage.save(task())
            storage.writePage(1, 0, "0123456789", byteArrayOf(9))
            storage.withPartialPage(1, 1) {
                it.reset(identity(4))
                it.append(byteArrayOf(1, 2))
            }
            val data = directory / "1" / "partial-1.data"
            FileSystem.SYSTEM.write(data) { write(byteArrayOf(8, 8, 8)) }
            val failure = assertFailsWith<GalleryDownloadStorageException> { storage.withPartialPage(1, 1) { error("Corrupt prefix must not be admitted") } }
            assertEquals(null, failure.cause)
            assertContentEquals(byteArrayOf(8, 8, 8), FileSystem.SYSTEM.read(data) { readByteArray() })
            assertContentEquals(byteArrayOf(9), storage.readPage(1, 0))
            storage.remove(1)
            assertFalse(FileSystem.SYSTEM.exists(directory / "1"))
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun resetFailureCannotBindOldBytesToANewValidator() = runTest {
        val owned = ownedDatabaseFile()
        val directory = (owned.path + "-partial-images").toPath()
        val db = fileDownloadDatabase(owned.path + "-partial-db")
        var publications = 0
        var failAt = Int.MAX_VALUE
        val files = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                if (target.name == "partial-0.cbor" && ++publications == failAt) throw IOException("owned reset failure")
                super.atomicMove(source, target)
            }
        }
        try {
            val storage = RoomGalleryDownloadStorage(db, directory, files)
            storage.save(task())
            storage.withPartialPage(1, 0) {
                it.reset(identity(4))
                it.append(byteArrayOf(1, 2))
            }
            failAt = publications + 2 // Empty metadata succeeds; the new identity publication fails.
            assertFailsWith<GalleryDownloadStorageException> { storage.withPartialPage(1, 0) { it.reset(identity(3).copy(etag = "\"new\"")) } }
            storage.withPartialPage(1, 0) { image ->
                assertEquals(null, image.progress().identity)
                assertEquals(0L, image.progress().bytes)
                image.reset(identity(3).copy(etag = "\"new\""))
                image.append(byteArrayOf(3, 4, 5))
                image.finish()
                assertContentEquals(byteArrayOf(3, 4, 5), image.readComplete())
            }
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun malformedOrOversizedMetadataAndShortPayloadAreRejected() = runTest {
        val owned = ownedDatabaseFile()
        val directory = (owned.path + "-partial-images").toPath()
        val db = fileDownloadDatabase(owned.path + "-partial-db")
        try {
            val storage = RoomGalleryDownloadStorage(db, directory)
            storage.save(task())
            for (size in listOf(0, 1, 16385)) {
                FileSystem.SYSTEM.createDirectories(directory / "1")
                FileSystem.SYSTEM.write(directory / "1" / "partial-0.cbor") { write(ByteArray(size)) }
                assertFailsWith<GalleryDownloadStorageException> { storage.withPartialPage(1, 0) { error("Invalid metadata admitted") } }
                FileSystem.SYSTEM.delete(directory / "1" / "partial-0.cbor")
            }
            storage.withPartialPage(1, 0) {
                it.reset(identity(4))
                it.append(byteArrayOf(1, 2))
            }
            FileSystem.SYSTEM.write(directory / "1" / "partial-0.data") { writeByte(1) }
            assertFailsWith<GalleryDownloadStorageException> { storage.withPartialPage(1, 0) { error("Short payload admitted") } }
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun removalWaitsForTheAdmittedOwnerAndThenDeletesItsPrivateCheckpoint() = runTest {
        val owned = ownedDatabaseFile()
        val directory = (owned.path + "-partial-images").toPath()
        val db = fileDownloadDatabase(owned.path + "-partial-db")
        try {
            val storage = RoomGalleryDownloadStorage(db, directory)
            storage.save(task())
            val admitted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = async {
                storage.withPartialPage(1, 0) {
                    it.reset(identity(2))
                    it.append(byteArrayOf(1))
                    admitted.complete(Unit)
                    release.await()
                }
            }
            admitted.await()
            val removal = async { storage.remove(1) }
            runCurrent()
            assertFalse(removal.isCompleted)
            release.complete(Unit)
            worker.await()
            removal.await()
            assertTrue(storage.list().isEmpty())
            assertFalse(FileSystem.SYSTEM.exists(directory / "1"))
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun reopeningCleansACompletedCheckpointOnlyAfterVerifyingItsCommittedPage() = runTest {
        val owned = ownedDatabaseFile()
        val directory = (owned.path + "-partial-images").toPath()
        val path = owned.path + "-partial-db"
        var db = fileDownloadDatabase(path)
        try {
            var storage = RoomGalleryDownloadStorage(db, directory)
            storage.save(task())
            storage.withPartialPage(1, 0) { image ->
                image.reset(identity(3))
                image.append(byteArrayOf(1, 2, 3))
                image.finish()
                storage.writePage(1, 0, "1111111111", image.readComplete())
                // The process stops before count publication and checkpoint discard.
            }
            assertTrue(FileSystem.SYSTEM.exists(directory / "1" / "partial-0.data"))
            db.close()
            db = fileDownloadDatabase(path)
            storage = RoomGalleryDownloadStorage(db, directory)
            FileSystem.SYSTEM.write(directory / "1" / "page-0.bin") { write(byteArrayOf(9, 9, 9)) }
            assertFailsWith<GalleryDownloadStorageException> { storage.pages(1) }
            assertTrue(FileSystem.SYSTEM.exists(directory / "1" / "partial-0.data"))
            FileSystem.SYSTEM.write(directory / "1" / "page-0.bin") { write(byteArrayOf(1, 2, 3)) }
            assertEquals(listOf(0), storage.pages(1).map { it.index })
            assertContentEquals(byteArrayOf(1, 2, 3), storage.readPage(1, 0))
            assertFalse(FileSystem.SYSTEM.exists(directory / "1" / "partial-0.data"))
            assertFalse(FileSystem.SYSTEM.exists(directory / "1" / "partial-0.cbor"))
            assertFailsWith<GalleryDownloadStorageException> { storage.withPartialPage(1, 0) { error("Committed page must not get a second writer") } }
        } finally {
            db.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
            owned.cleanup()
        }
    }
}
