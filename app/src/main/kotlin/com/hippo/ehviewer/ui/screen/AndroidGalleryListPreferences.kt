package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.domain.GalleryLaunchPage
import com.ehviewer.core.domain.GalleryLaunchPreferences
import com.ehviewer.core.domain.GalleryLaunchPreferencesException
import com.ehviewer.core.domain.GalleryLaunchSettings
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListMode
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.GalleryListPresentationPreferences
import com.ehviewer.core.domain.GalleryQuickSearchPreferences
import com.ehviewer.core.domain.GalleryToplistPeriod
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read/write the original preference directly; retain its default, key and unrelated settings. */
internal class AndroidGalleryListPreferences : GalleryListPreferences, GalleryListPresentationPreferences, GalleryLaunchPreferences, GalleryQuickSearchPreferences {
    private suspend fun <T> stored(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryListPreferencesException()
    }

    private fun decode(value: Int) = when (value) {
        0 -> GalleryListDensity.COMFORTABLE
        1 -> GalleryListDensity.COMPACT
        else -> error("Unknown saved gallery density")
    }

    override suspend fun density(): GalleryListDensity = stored { decode(Settings.snapshot()[Settings.detailSize]) }
    override suspend fun showComments(): Boolean = stored { Settings.snapshot()[Settings.showComments] }
    override suspend fun setShowComments(show: Boolean) = stored {
        Settings.withMutableSnapshot { it[Settings.showComments] = show }
    }

    override suspend fun setDensity(density: GalleryListDensity) = stored {
        Settings.withMutableSnapshot { snapshot ->
            decode(snapshot[Settings.detailSize])
            snapshot[Settings.detailSize] = if (density == GalleryListDensity.COMFORTABLE) 0 else 1
        }
    }

    private fun decodeMode(value: Int) = when (value) {
        0 -> GalleryListMode.DETAIL
        1 -> GalleryListMode.THUMBNAIL
        else -> error("Unknown gallery list mode")
    }

    override suspend fun presentation(): GalleryListPresentation = stored {
        val snapshot = Settings.snapshot()
        GalleryListPresentation(
            mode = decodeMode(snapshot[Settings.listMode]),
            detailThumbnailSize = snapshot[Settings.listThumbSize],
            thumbnailColumns = snapshot[Settings.thumbColumns],
            showPages = snapshot[Settings.showGalleryPages],
            showReadingProgress = snapshot[Settings.showReadingProgress],
        )
    }

    override suspend fun setMode(mode: GalleryListMode) = stored {
        Settings.withMutableSnapshot {
            decodeMode(it[Settings.listMode])
            it[Settings.listMode] = if (mode == GalleryListMode.DETAIL) 0 else 1
        }
    }

    override suspend fun setDetailThumbnailSize(size: Int) = stored {
        require(size in 20..60)
        Settings.withMutableSnapshot { it[Settings.listThumbSize] = size }
    }

    override suspend fun setThumbnailColumns(columns: Int) = stored {
        require(columns in 1..10)
        Settings.withMutableSnapshot { it[Settings.thumbColumns] = columns }
    }

    override suspend fun setShowPages(enabled: Boolean) = stored {
        Settings.withMutableSnapshot { it[Settings.showGalleryPages] = enabled }
    }

    override suspend fun setShowReadingProgress(enabled: Boolean) = stored {
        Settings.withMutableSnapshot { it[Settings.showReadingProgress] = enabled }
    }

    override suspend fun saveQuickSearchProgress() = stored { Settings.snapshot()[Settings.qSSaveProgress] }

    override suspend fun setSaveQuickSearchProgress(enabled: Boolean) = stored {
        Settings.withMutableSnapshot { it[Settings.qSSaveProgress] = enabled }
    }

    private suspend fun <T> storedLaunch(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryLaunchPreferencesException()
    }

    override suspend fun launchSettings() = storedLaunch {
        val index = Settings.snapshot()[Settings.launchPage]
        GalleryLaunchSettings(
            // The embedded shared browse destination is not one of the four original choices.
            page = if (index == 8) GalleryLaunchPage.HOMEPAGE else GalleryLaunchPage.entries.single { it.originalIndex == index },
            toplistPeriod = GalleryToplistPeriod.entries.single { it.originalCode == Settings.recentToplist },
        )
    }

    override suspend fun setLaunchPage(page: GalleryLaunchPage) = storedLaunch {
        Settings.withMutableSnapshot { it[Settings.launchPage] = page.originalIndex }
    }

    override suspend fun setToplistPeriod(period: GalleryToplistPeriod) = storedLaunch {
        Settings.recentToplist = period.originalCode
    }
}
