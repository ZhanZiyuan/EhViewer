package com.ehviewer.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.domain.download.GalleryImageIdentity
import com.ehviewer.core.domain.download.GalleryImageProgress.Companion.CHUNK_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class DownloadStorageBudgetTest {
    private class OwnedPreferences(path: Path) {
        private val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(job + Dispatchers.Default)) { path }
        val preferences = DataStoreStoragePreferences(store)
        suspend fun close() = job.cancelAndJoin()
    }

    private fun task(gid: Long = 1) = GalleryDownload(gid, "0123456789", "Owned budget comic", 2, false, GalleryDownloadState.DOWNLOADING)
    private val files = FileSystem.SYSTEM

    @Test fun corruptPreferenceFileIsRejectedWithoutReplacingItsBytesEvenOnReset() = runTest {
        val owned = ownedDatabaseFile()
        val path = (owned.path + ".preferences_pb").toPath()
        val corrupt = byteArrayOf(0x0a, 0x7f, 0x01)
        files.write(path) { write(corrupt) }
        val preferences = OwnedPreferences(path)
        try {
            assertFailsWith<GalleryDownloadStorageException> { preferences.preferences.downloadLimitMiB() }
            assertFailsWith<GalleryDownloadStorageException> { preferences.preferences.setDownloadLimitMiB(null) }
            assertContentEquals(corrupt, files.read(path) { readByteArray() })
        } finally {
            preferences.close()
            owned.cleanup()
        }
    }

    @Test fun invalidDatabaseSizesCannotCancelOutTheBudgetAndAreNeverRepairedByWriting() = runTest {
        val owned = ownedDatabaseFile()
        val images = (owned.path + "-images").toPath()
        val preferences = OwnedPreferences((owned.path + ".preferences_pb").toPath())
        val db = fileDownloadDatabase(owned.path + "-downloads")
        try {
            val storage = RoomGalleryDownloadStorage(db, images, preferences = preferences.preferences)
            storage.save(task())
            storage.setDownloadLimitMiB(1)
            storage.writePage(1, 0, "0123456789", byteArrayOf(1, 2, 3))
            val original = checkNotNull(db.downloads().page(1, 0))
            db.downloads().save(original.copy(bytes = -100))
            assertFailsWith<GalleryDownloadStorageException> { storage.usage() }
            assertFailsWith<GalleryDownloadStorageException> { storage.writePage(1, 1, "abcdef0123", byteArrayOf(4)) }
            assertEquals(-100L, db.downloads().page(1, 0)?.bytes)
            assertNull(db.downloads().page(1, 1))
            assertContentEquals(byteArrayOf(1, 2, 3), files.read(images / "1" / "page-0.bin") { readByteArray() })
        } finally {
            preferences.close()
            db.close()
            files.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun actualImagesRemainReadableWhenLimitRejectsNextPageAndRaisingItAllowsRetry() = runTest {
        val owned = ownedDatabaseFile()
        val images = (owned.path + "-images").toPath()
        val preferencesPath = (owned.path + ".preferences_pb").toPath()
        var preferences = OwnedPreferences(preferencesPath)
        val db = fileDownloadDatabase(owned.path + "-downloads")
        val page = ByteArray(600 * 1024) { (it % 251).toByte() }
        try {
            var storage = RoomGalleryDownloadStorage(db, images, preferences = preferences.preferences)
            storage.save(task())
            assertNull(storage.usage().limitMiB)
            storage.setDownloadLimitMiB(1)
            storage.writePage(1, 0, "0123456789", page)
            storage.withPartialPage(1, 1) { checkpoint ->
                checkpoint.reset(GalleryImageIdentity("abcdef0123", "a".repeat(64), "b".repeat(64), "\"owned-budget-image\"", page.size.toLong()))
                for (offset in page.indices step CHUNK_BYTES) checkpoint.append(page.copyOfRange(offset, minOf(offset + CHUNK_BYTES, page.size)))
                checkpoint.finish()
                assertFailsWith<GalleryDownloadStorageException> { storage.writePage(1, 1, "abcdef0123", checkpoint.readComplete()) }
            }
            assertNull(db.downloads().page(1, 1))
            assertFalse(files.exists(images / "1" / "page-1.part"))
            assertContentEquals(page, storage.readPage(1, 0))
            assertEquals(page.size.toLong(), storage.usage().imageBytes)
            preferences.close()
            preferences = OwnedPreferences(preferencesPath)
            storage = RoomGalleryDownloadStorage(db, images, preferences = preferences.preferences)
            assertEquals(1, storage.usage().limitMiB)
            storage.setDownloadLimitMiB(2)
            storage.withPartialPage(1, 1) { checkpoint ->
                assertContentEquals(page, checkpoint.readComplete())
                storage.writePage(1, 1, "abcdef0123", checkpoint.readComplete())
                checkpoint.discard()
            }
            assertEquals(2L * page.size, storage.usage().imageBytes)
            storage.setDownloadLimitMiB(1) // Lowering the budget never evicts existing images.
            assertContentEquals(page, storage.readPage(1, 1))
            storage.setDownloadLimitMiB(null)
            assertNull(storage.usage().limitMiB)
        } finally {
            preferences.close()
            db.close()
            files.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun concurrentGalleriesShareOneRealDatabaseBudgetAndDuplicateWritesRemainIdempotent() = runTest {
        val owned = ownedDatabaseFile()
        val images = (owned.path + "-images").toPath()
        val preferences = OwnedPreferences((owned.path + ".preferences_pb").toPath())
        val db = fileDownloadDatabase(owned.path + "-downloads")
        val page = ByteArray(600 * 1024) { 7 }
        try {
            val first = RoomGalleryDownloadStorage(db, images, preferences = preferences.preferences)
            val second = RoomGalleryDownloadStorage(db, images, preferences = preferences.preferences)
            first.save(task(1))
            first.save(task(2))
            first.setDownloadLimitMiB(1)
            suspend fun admit(storage: RoomGalleryDownloadStorage, gid: Long): Boolean = try {
                storage.writePage(gid, 0, "0123456789", page)
                true
            } catch (_: GalleryDownloadStorageException) {
                false
            }
            val a = async(Dispatchers.Default) { admit(first, 1) }
            val b = async(Dispatchers.Default) { admit(second, 2) }
            val accepted = listOf(a.await(), b.await())
            assertEquals(1, accepted.count { it })
            val gid = if (accepted.first()) 1L else 2L
            first.writePage(gid, 0, "0123456789", page)
            assertEquals(page.size.toLong(), first.usage().imageBytes)
            first.remove(gid)
            assertEquals(0L, first.usage().imageBytes)
            assertEquals(1, first.usage().limitMiB)
        } finally {
            preferences.close()
            db.close()
            files.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }

    @Test fun futurePreferencesBlockNewWritesAndResetWithoutChangingExistingImagesOrSavedBytes() = runTest {
        val owned = ownedDatabaseFile()
        val images = (owned.path + "-images").toPath()
        val path = (owned.path + ".preferences_pb").toPath()
        val preferences = OwnedPreferences(path)
        val db = fileDownloadDatabase(owned.path + "-downloads")
        val page = byteArrayOf(1, 2, 3)
        try {
            val storage = RoomGalleryDownloadStorage(db, images, preferences = preferences.preferences)
            storage.save(task())
            storage.writePage(1, 0, "0123456789", page)
            preferences.store.edit { it[intPreferencesKey("schema")] = 2 }
            val before = files.read(path) { readByteArray() }
            assertFailsWith<GalleryDownloadStorageException> { storage.usage() }
            assertFailsWith<GalleryDownloadStorageException> { storage.setDownloadLimitMiB(null) }
            assertFailsWith<GalleryDownloadStorageException> { storage.writePage(1, 1, "abcdef0123", page) }
            assertContentEquals(before, files.read(path) { readByteArray() })
            assertContentEquals(page, storage.readPage(1, 0))
            assertNull(db.downloads().page(1, 1))
        } finally {
            preferences.close()
            db.close()
            files.deleteRecursively(images, mustExist = false)
            owned.cleanup()
        }
    }
}
