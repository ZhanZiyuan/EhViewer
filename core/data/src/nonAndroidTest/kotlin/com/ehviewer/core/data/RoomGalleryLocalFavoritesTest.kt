package com.ehviewer.core.data

import androidx.room.execSQL
import androidx.room.useWriterConnection
import com.ehviewer.core.database.model.LocalFavoriteInfo
import com.ehviewer.core.database.model.ProgressInfo
import com.ehviewer.core.domain.GalleryLocalFavoritesException
import com.ehviewer.core.model.BaseGalleryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RoomGalleryLocalFavoritesTest {
    private fun book(id: Long, title: String = "Owned gallery $id") = BaseGalleryInfo(id, "0123456789", title, pages = 12)

    @Test fun removingTheLocalFavoriteLeavesCloudMetadataHistoryAndProgress() = runTest {
        val database = historyTestDatabase()
        try {
            val history = RoomGalleryHistoryStore(database)
            val favorite = RoomGalleryLocalFavorites(database)
            history.record(book(1).also { it.favoriteSlot = 3 })
            database.progressDao().upsert(ProgressInfo(1, 4))

            assertTrue(favorite.toggle(book(1, "Stale metadata")))
            assertTrue(RoomGalleryLocalFavorites(database).contains(1))
            val entry = favorite.page(0, 20).single()
            assertEquals("Owned gallery 1", entry.title)
            assertEquals(3, entry.favoriteSlot)
            assertFalse(RoomGalleryLocalFavorites(database).toggle(entry))
            assertEquals(emptyList(), favorite.page(0, 20))
            assertEquals(3, history.recent().single().favoriteSlot)
            assertEquals(4, database.progressDao().load(1)?.page)
        } finally {
            database.close()
        }
    }

    @Test fun theSameTimestampStillProducesStablePagesAndAnAbortedFavoriteCannotCreateAnOrphan() = runTest {
        val database = historyTestDatabase()
        try {
            val favorite = RoomGalleryLocalFavorites(database)
            for (gid in 1L..3L) {
                assertTrue(favorite.toggle(book(gid)))
                database.localFavoritesDao().upsert(LocalFavoriteInfo(gid, 100L))
            }
            assertEquals(listOf(3L, 2L), favorite.page(0, 2).map { it.gid })
            assertEquals(listOf(1L), favorite.page(2, 2).map { it.gid })

            database.useWriterConnection { it.execSQL("CREATE TRIGGER reject_favorite BEFORE INSERT ON LOCAL_FAVORITES BEGIN SELECT RAISE(ABORT, 'owned test failure'); END") }
            assertFailsWith<GalleryLocalFavoritesException> { favorite.toggle(book(4)) }
            assertNull(database.galleryDao().load(4))
            assertFalse(favorite.contains(4))
        } finally {
            database.close()
        }
    }
}
