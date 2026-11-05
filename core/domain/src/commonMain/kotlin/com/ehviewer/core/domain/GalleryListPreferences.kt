package com.ehviewer.core.domain

/** Match the original Android detail-list widths while sharing the same responsive cards. */
enum class GalleryListDensity(val minimumColumnWidth: Int) { COMFORTABLE(480), COMPACT(320) }

interface GalleryListPreferences {
    suspend fun density(): GalleryListDensity
    suspend fun setDensity(density: GalleryListDensity)
    suspend fun showComments(): Boolean
    suspend fun setShowComments(show: Boolean)
}

class GalleryListPreferencesException : Exception("Gallery layout could not be loaded or saved")
