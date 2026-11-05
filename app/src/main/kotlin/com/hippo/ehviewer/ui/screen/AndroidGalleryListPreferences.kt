package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read/write the original preference directly; retain its default, key and unrelated settings. */
internal class AndroidGalleryListPreferences : GalleryListPreferences {
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
}
