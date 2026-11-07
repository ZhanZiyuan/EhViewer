package com.ehviewer.core.network.gallery

import io.ktor.http.URLBuilder
import kotlinx.serialization.Serializable

/** The original gallery service category bits, shared by every product UI. */
enum class GalleryCategory(val mask: Int) {
    MISC(0x1),
    DOUJINSHI(0x2),
    MANGA(0x4),
    ARTIST_CG(0x8),
    GAME_CG(0x10),
    IMAGE_SET(0x20),
    COSPLAY(0x40),
    ASIAN_PORN(0x80),
    NON_H(0x100),
    WESTERN(0x200),
}

/** Null categories preserve the site's saved preferences instead of silently overriding them. */
@Serializable
data class GallerySearchFilters(
    val includedCategories: Int? = null,
    val minimumRating: Int? = null,
    val minimumPages: Int? = null,
    val maximumPages: Int? = null,
    val showExpunged: Boolean = false,
    val onlyWithTorrents: Boolean = false,
    val disableLanguageFilter: Boolean = false,
    val disableUploaderFilter: Boolean = false,
    val disableTagFilter: Boolean = false,
) {
    init {
        require(includedCategories == null || includedCategories in 1..ALL_CATEGORIES)
        require(minimumRating == null || minimumRating in 2..5)
        require(minimumPages == null || minimumPages > 0)
        require(maximumPages == null || maximumPages > 0)
        require(minimumPages == null || maximumPages == null || minimumPages <= maximumPages)
    }

    internal fun appendTo(url: URLBuilder) {
        includedCategories?.let { url.parameters.append("f_cats", (it xor ALL_CATEGORIES).toString()) }
        val flags = listOf(
            "f_sh" to showExpunged,
            "f_sto" to onlyWithTorrents,
            "f_sfl" to disableLanguageFilter,
            "f_sfu" to disableUploaderFilter,
            "f_sft" to disableTagFilter,
        )
        if (minimumRating == null && minimumPages == null && maximumPages == null && flags.none { it.second }) return
        url.parameters.append("advsearch", "1")
        flags.filter { it.second }.forEach { url.parameters.append(it.first, "on") }
        minimumRating?.let { url.parameters.append("f_srdd", it.toString()) }
        minimumPages?.let { url.parameters.append("f_spf", it.toString()) }
        maximumPages?.let { url.parameters.append("f_spt", it.toString()) }
    }

    companion object {
        const val ALL_CATEGORIES = 0x3ff
    }
}
