package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.CUSTOM_DOWNLOAD_POSITION_STEP
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.GalleryEntity
import com.hippo.ehviewer.ui.screen.DownloadsFilterState
import com.hippo.ehviewer.ui.screen.take
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomDownloadOrderTest {
    @Test
    fun historicalPreferenceFlagsKeepTheirMeaning() {
        val historical = listOf(5, 4, 1, 0, 2, 3, 8, 9)
        assertEquals(historical, SortMode.All.take(8).map { it.flag })
        for (mode in SortMode.All) {
            for (grouped in listOf(false, true)) {
                val value = mode.copy(groupByDownloadLabel = grouped)
                assertEquals(value, SortMode.from(value.flag))
            }
        }
        assertEquals(SortMode(SortMode.Field.TIME, SortMode.Order.DESC), SortMode.Default)
        assertEquals(10, SortMode.Custom.flag)
    }

    @Test
    fun customSortSurvivesAutomaticOrderingAndGrouping() {
        val rows = listOf(row(12, 0, "b"), row(9, 3, "a"), row(11, 5, "b"))
        rows[0].time = 3
        rows[1].time = 8
        rows[2].time = 5
        rows[0].pages = 3
        rows[1].pages = 1
        rows[2].pages = 1
        assertEquals(listOf(12L, 9L, 11L), rows.sortedWith(SortMode.Custom.comparator()).map { it.gid })
        assertEquals(listOf(9L, 11L, 12L), rows.sortedWith(SortMode.Default.comparator()).map { it.gid })
        assertEquals(listOf(9L, 11L, 12L), rows.sortedWith(SortMode.All[6].comparator()).map { it.gid })
        assertEquals(listOf(9L, 12L, 11L), rows.sortedWith(SortMode.Custom.copy(groupByDownloadLabel = true).comparator()).map { it.gid })
        assertEquals(listOf(12L, 9L, 11L), rows.sortedWith(SortMode.Custom.comparator()).map { it.gid })
    }

    @Test
    fun labelMovesUseGlobalNeighborsAndKeepOtherLabelsInOrder() {
        val rows = mutableListOf(
            row(1, 0, "moving"),
            row(2, 20, "hidden"),
            row(3, 40, "moving"),
            row(4, 60, "hidden"),
        )
        val label = DownloadsFilterState(DownloadsFilterMode.CUSTOM, "moving")
        assertEquals(listOf(1L, 3L), rows.filter(label::take).map { it.gid })
        val update = planDownloadMove(rows, index = 0, anchorIndex = 2, before = false)
        assertEquals(mapOf(1L to 50L), update.positions)
        rows.applyMove(0, update)
        assertEquals(listOf(2L, 3L, 1L, 4L), rows.map { it.gid })
        assertEquals(listOf(3L, 1L), rows.filter(label::take).map { it.gid })
        assertEquals(listOf(2L, 4L), rows.filter { it.label == "hidden" }.map { it.gid })
        rows.first { it.gid == 1L }.label = "hidden"
        assertEquals(listOf(2L, 3L, 1L, 4L), rows.map { it.gid })
    }

    @Test
    fun onlyClearSearchAndSafeFiltersAllowDrag() {
        val all = DownloadsFilterState(DownloadsFilterMode.ARTIST, "")
        assertTrue(all.allowsGalleryReorder)
        assertFalse(all.copy(label = null).allowsGalleryReorder)
        assertFalse(all.copy(label = "artist").allowsGalleryReorder)
        assertFalse(all.copy(state = DownloadInfo.STATE_FINISH).allowsGalleryReorder)
        assertFalse(all.copy(keyword = "query").allowsGalleryReorder)
        assertTrue(all.copy(mode = DownloadsFilterMode.CUSTOM, label = null).allowsGalleryReorder)
        assertTrue(all.copy(mode = DownloadsFilterMode.CUSTOM, label = "named").allowsGalleryReorder)
    }

    @Test
    fun adjacentKeysRebalanceAndExtremesDoNotOverflow() {
        assertNull(positionBetween(Long.MAX_VALUE, null))
        assertNull(positionBetween(null, Long.MIN_VALUE))
        assertEquals(-1L, positionBetween(Long.MIN_VALUE, Long.MAX_VALUE))
        val rows = mutableListOf(row(1, 9), row(2, 10), row(3, Long.MAX_VALUE))
        val update = planDownloadMove(rows, index = 2, anchorIndex = 1, before = true)
        assertEquals(mapOf(1L to 0L, 3L to CUSTOM_DOWNLOAD_POSITION_STEP, 2L to CUSTOM_DOWNLOAD_POSITION_STEP * 2), update.positions)
        rows.applyMove(2, update)
        assertEquals(listOf(1L, 3L, 2L), rows.map { it.gid })
    }

    @Test
    fun repeatedEdgeMovesNormallyWriteOneRowAtEachListSize() {
        for (size in listOf(100, 1_000, 10_000)) {
            val rows = MutableList(size) { row(it.toLong(), it * CUSTOM_DOWNLOAD_POSITION_STEP) }
            repeat(100) {
                val from = if (it % 2 == 0) rows.lastIndex else 0
                val anchor = if (from == 0) rows.lastIndex else 0
                val update = planDownloadMove(rows, from, anchor, before = anchor == 0)
                assertEquals(1, update.positions.size)
                rows.applyMove(from, update)
            }
            assertEquals(size, rows.map { it.downloadInfo.customPosition }.toSet().size)
            assertEquals(rows.map { it.gid }, rows.sortedWith(SortMode.Custom.comparator()).map { it.gid })
        }
    }

    private fun MutableList<DownloadInfo>.applyMove(from: Int, update: CustomOrderUpdate) {
        for (row in this) update.positions[row.gid]?.let { row.downloadInfo.customPosition = it }
        add(update.toIndex, removeAt(from))
    }

    private fun row(gid: Long, position: Long, label: String? = null) = DownloadInfo(
        GalleryEntity(gid, "fixture", "Gallery $gid", null, null, 0, null, null, 0f, null, 0, null, -2),
        null,
    ).apply {
        this.label = label
        downloadInfo.customPosition = position
    }
}
