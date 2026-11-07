package com.ehviewer.shared

import com.ehviewer.core.domain.GalleryFavoritePreferences
import com.ehviewer.core.domain.GalleryFavoriteTarget
import com.ehviewer.core.domain.GalleryLocalFavorites
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository

class GalleryFavoriteControls(
    val preferences: GalleryFavoritePreferences,
    val local: GalleryLocalFavorites,
)

internal enum class GalleryFavoriteActionResult { SHOW_EDITOR, LOCAL_UPDATED, ONLINE_UPDATED }

internal suspend fun GalleryFavoriteControls.activate(
    gallery: GalleryInfo,
    online: GalleryFavoritesRepository,
): GalleryFavoriteActionResult {
    val target = preferences.target()
    if (target == GalleryFavoriteTarget.Ask) {
        if (online.isSignedIn()) return GalleryFavoriteActionResult.SHOW_EDITOR
        local.toggle(gallery)
        return GalleryFavoriteActionResult.LOCAL_UPDATED
    }

    // Inspect the current account and actual favorite; a stale detail cannot choose
    // whether to add or remove a cloud item. The repository also rechecks identity at POST.
    val state = online.inspectIfSignedIn(gallery.gid, gallery.token)
    return when {
        state == null -> {
            local.toggle(gallery)
            GalleryFavoriteActionResult.LOCAL_UPDATED
        }
        state.category != null -> {
            online.update(state, null, "")
            GalleryFavoriteActionResult.ONLINE_UPDATED
        }
        target == GalleryFavoriteTarget.Local || local.contains(gallery.gid) -> {
            local.toggle(gallery)
            GalleryFavoriteActionResult.LOCAL_UPDATED
        }
        else -> {
            online.update(state, target.slot, "")
            GalleryFavoriteActionResult.ONLINE_UPDATED
        }
    }
}
