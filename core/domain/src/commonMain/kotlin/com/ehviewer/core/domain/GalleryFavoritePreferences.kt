package com.ehviewer.core.domain

import kotlin.jvm.JvmInline

/** The v1.15.10 values are preserved: -2 asks, -1 is local, 0..9 are cloud slots. */
@JvmInline
value class GalleryFavoriteTarget(val slot: Int) {
    init {
        require(slot in -2..9)
    }

    companion object {
        val Ask = GalleryFavoriteTarget(-2)
        val Local = GalleryFavoriteTarget(-1)
    }
}

class GalleryFavoritePreferencesException : Exception("Default favorites collection is unavailable")

interface GalleryFavoritePreferences {
    suspend fun target(): GalleryFavoriteTarget
    suspend fun setTarget(target: GalleryFavoriteTarget)
}
