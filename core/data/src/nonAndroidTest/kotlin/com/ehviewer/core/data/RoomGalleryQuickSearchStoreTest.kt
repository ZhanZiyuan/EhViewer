package com.ehviewer.core.data

import com.ehviewer.core.database.model.QuickSearch
import com.ehviewer.core.domain.GalleryQuickSearch
import com.ehviewer.core.domain.GalleryQuickSearchError
import com.ehviewer.core.domain.GalleryQuickSearchException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class RoomGalleryQuickSearchStoreTest {
    @Test fun theOriginalTableRetainsProgressAndStoredOrderAcrossStoreInstances() = runTest {
        val database = historyTestDatabase()
        try {
            val original = QuickSearch(name = "Original@43", mode = 2, keyword = "language:english", category = 512, advanceSearch = 2, minRating = 3)
            val firstId = database.quickSearchDao().insert(original)
            val store = RoomGalleryQuickSearchStore(database)
            val legacy = store.list().single()
            assertEquals(firstId, legacy.id)
            assertEquals("43", legacy.next)
            assertEquals(512, legacy.category)
            assertEquals(2, legacy.advanceSearch)
            assertEquals(3, legacy.minRating)
            val duplicate = assertFailsWith<GalleryQuickSearchException> { store.save(legacy.copy(name = "Another name@43", id = null)) }
            assertEquals(GalleryQuickSearchError.SAME_POSITION, duplicate.reason)
            store.save(GalleryQuickSearch("New", 0, "tag:test", -1, -1, -1, -1, -1))
            val secondId = requireNotNull(store.list().last().id)
            store.move(secondId, before = firstId)
            assertEquals(listOf(secondId, firstId), RoomGalleryQuickSearchStore(database).list().map { it.id })
            store.remove(secondId)
            assertEquals(listOf(legacy), RoomGalleryQuickSearchStore(database).list())
            assertEquals(0, database.quickSearchDao().list().single().position)
        } finally {
            database.close()
        }
    }
}
