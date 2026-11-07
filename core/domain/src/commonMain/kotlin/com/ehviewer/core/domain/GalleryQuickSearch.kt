package com.ehviewer.core.domain

/** A record from the original QUICK_SEARCH table, including its saved mode and filters. */
data class GalleryQuickSearch(
    val name: String,
    val mode: Int,
    val keyword: String?,
    val category: Int,
    val advanceSearch: Int,
    val minRating: Int,
    val pageFrom: Int,
    val pageTo: Int,
    val id: Long? = null,
) {
    /** Android writes the next gallery ID after an @ in the saved name. */
    val next: String? get() = name.substringAfterLast('@', "").takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }
}

enum class GalleryQuickSearchError { NAME_IN_USE, SAME_POSITION, UNAVAILABLE }

class GalleryQuickSearchException(val reason: GalleryQuickSearchError) : Exception("Quick search operation failed: $reason")

interface GalleryQuickSearchStore {
    suspend fun list(): List<GalleryQuickSearch>
    suspend fun save(search: GalleryQuickSearch)
    suspend fun remove(id: Long)
    suspend fun move(id: Long, before: Long?)
}

interface GalleryQuickSearchPreferences {
    suspend fun saveQuickSearchProgress(): Boolean
    suspend fun setSaveQuickSearchProgress(enabled: Boolean)
}
