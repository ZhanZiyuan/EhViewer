package com.ehviewer.core.data

import androidx.room.execSQL
import androidx.room.useWriterConnection
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.model.HistoryInfo
import com.ehviewer.core.database.model.ProgressInfo
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadMetadata
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.model.BaseGalleryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

internal expect fun historyTestDatabase(): EhDatabase

class RoomGalleryHistoryStoreTest {
    private fun gallery(gid: Long, title: String = "History $gid") = BaseGalleryInfo(gid = gid, token = "0123456789", title = title, pages = 42)

    @Test fun independentlyRestoredDownloadsCreateOnlyTheirActualMetadataAndPreserveKnownFavoritesAndProgress() = runTest {
        val db = historyTestDatabase()
        try {
            val store = RoomGalleryHistoryStore(db)
            val downloaded = GalleryDownload(1, "0123456789", "Actual downloaded book", 42, false, GalleryDownloadState.COMPLETE, 42, metadata = GalleryDownloadMetadata("Owned artist", false, 4.5f, 512))
            store.recordDownloaded(downloaded)
            val restored = store.recent().single()
            assertEquals(downloaded.title, restored.title)
            assertEquals(42, restored.pages)
            assertEquals("Owned artist", restored.uploader)
            assertEquals(4.5f, restored.rating)
            assertEquals(512, restored.category)
            assertNull(db.progressDao().load(1), "Restoring actual metadata must not invent reading progress")

            val full = gallery(1, "Full known title").also {
                it.titleJpn = "日本語"
                it.simpleTags = listOf("language:english")
                it.favoriteSlot = 3
            }
            store.record(full)
            val before = checkNotNull(db.galleryDao().load(1))
            db.progressDao().upsert(ProgressInfo(1, 12))
            store.forget(1)
            store.recordDownloaded(downloaded)
            val known = store.recent().single()
            assertEquals(full.title, known.title)
            assertEquals(full.titleJpn, known.titleJpn)
            assertEquals(full.simpleTags, known.simpleTags)
            assertEquals(full.favoriteSlot, known.favoriteSlot)
            assertEquals(before, db.galleryDao().load(1), "Keep every actual persisted gallery field")
            assertEquals(12, db.progressDao().load(1)?.page)
            assertFailsWith<GalleryHistoryException> { store.recordDownloaded(downloaded.copy(token = "aaaaaaaaaa")) }
            assertEquals(before, db.galleryDao().load(1))
            assertFailsWith<GalleryHistoryException> { store.recordDownloaded(downloaded.copy(gid = 2, state = GalleryDownloadState.PAUSED)) }
            assertNull(db.galleryDao().load(2))
        } finally {
            db.close()
        }
    }

    @Test fun offlineReadingRestoresHistoryWithoutReplacingExistingMetadataOrProgress() = runTest {
        val db = historyTestDatabase()
        try {
            val store = RoomGalleryHistoryStore(db)
            store.record(
                gallery(1).also {
                    it.uploader = "Owned uploader"
                    it.titleJpn = "日本語"
                    it.simpleTags = listOf("language:english")
                },
            )
            db.progressDao().upsert(ProgressInfo(1, 12))
            store.forget(1)
            store.touch(1)
            val restored = store.recent().single()
            assertEquals("Owned uploader", restored.uploader)
            assertEquals("日本語", restored.titleJpn)
            assertEquals(listOf("language:english"), restored.simpleTags)
            assertEquals(42, restored.pages)
            assertEquals(12, db.progressDao().load(1)?.page)
            assertFailsWith<GalleryHistoryException> { store.touch(2) }
            assertEquals(listOf(1L), store.recent().map { it.gid })
        } finally {
            db.close()
        }
    }

    @Test fun realRoomMetadataUpdatesAndBoundedHistoryRemainUniqueAndOrdered() = runTest {
        val db = historyTestDatabase()
        try {
            val store = RoomGalleryHistoryStore(db)
            store.record(gallery(1))
            store.record(gallery(2))
            store.record(gallery(1, "Changed title"))
            db.historyDao().upsert(HistoryInfo(1, 100))
            db.historyDao().upsert(HistoryInfo(2, 200))
            assertEquals(listOf(2L), store.recent(1).map { it.gid })
            assertEquals(listOf(2L, 1L), store.recent().map { it.gid })
            assertEquals("Changed title", store.recent().last().title)
            assertFailsWith<IllegalArgumentException> { store.recent(501) }
        } finally {
            db.close()
        }
    }

    @Test fun clearingHistoryRemovesEveryEntryButKeepsGalleriesAndReadingPositions() = runTest {
        val db = historyTestDatabase()
        try {
            val store = RoomGalleryHistoryStore(db)
            store.record(gallery(1))
            store.record(gallery(2))
            db.progressDao().upsert(ProgressInfo(2, 7))
            store.clear()
            assertEquals(emptyList(), store.recent())
            assertEquals("History 1", db.galleryDao().load(1)?.title)
            assertEquals(7, db.progressDao().load(2)?.page)
            store.touch(1) // Opening a cleared gallery again restores its entry.
            assertEquals(listOf(1L), store.recent().map { it.gid })
        } finally {
            db.close()
        }
    }

    @Test fun forgettingHistoryPreservesTheActualGalleryAndReadingPosition() = runTest {
        val db = historyTestDatabase()
        try {
            val store = RoomGalleryHistoryStore(db)
            store.record(gallery(1))
            db.progressDao().upsert(ProgressInfo(1, 12))
            store.forget(1)
            assertEquals(emptyList(), store.recent())
            assertEquals("History 1", db.galleryDao().load(1)?.title)
            assertEquals(12, db.progressDao().load(1)?.page)
        } finally {
            db.close()
        }
    }

    @Test fun actualSqliteAbortRollsBackMetadataAndRedactsTheFailure() = runTest {
        val db = historyTestDatabase()
        try {
            val store = RoomGalleryHistoryStore(db)
            // Force the SECOND write to fail in the real engine, after metadata has been upserted.
            db.useWriterConnection { it.execSQL("CREATE TRIGGER reject_history BEFORE INSERT ON HISTORY BEGIN SELECT RAISE(ABORT, 'test-only-private-database-value'); END") }
            val error = assertFailsWith<GalleryHistoryException> { store.record(gallery(1)) }
            assertNull(db.galleryDao().load(1))
            assertEquals(emptyList(), store.recent())
            assertNull(error.cause)
            assertFalse(error.toString().contains("private"))
            assertFailsWith<GalleryHistoryException> { store.recordDownloaded(GalleryDownload(1, "0123456789", "Actual downloaded book", 42, false, GalleryDownloadState.COMPLETE, 42)) }
            assertNull(db.galleryDao().load(1), "The failed history write must roll back restored download metadata")
            assertEquals(emptyList(), store.recent())
        } finally {
            db.close()
        }
    }

    @Test fun concurrentWritersUseTheRealRoomPoolAndNeverLoseHistoryEntries() = runTest {
        val db = historyTestDatabase()
        try {
            val store = RoomGalleryHistoryStore(db)
            (1L..12L).map { gid -> async { store.record(gallery(gid)) } }.awaitAll()
            assertEquals((1L..12L).toSet(), store.recent().map { it.gid }.toSet())
            assertEquals(12, db.galleryDao().list().size)
        } finally {
            db.close()
        }
    }
}
