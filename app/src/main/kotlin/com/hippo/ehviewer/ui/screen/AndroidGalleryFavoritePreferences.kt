package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.domain.GalleryFavoritePreferences
import com.ehviewer.core.domain.GalleryFavoritePreferencesException
import com.ehviewer.core.domain.GalleryFavoriteTarget
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The original settings screen and the shared UI observe the same preference key. */
internal class AndroidGalleryFavoritePreferences : GalleryFavoritePreferences {
    private suspend fun <T> stored(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryFavoritePreferencesException()
    }

    override suspend fun target() = stored { GalleryFavoriteTarget(Settings.snapshot()[Settings.defaultFavSlot]) }

    override suspend fun setTarget(target: GalleryFavoriteTarget) = stored {
        Settings.withMutableSnapshot {
            if (GalleryFavoriteTarget(it[Settings.defaultFavSlot]) != target) it[Settings.defaultFavSlot] = target.slot
        }
    }
}
