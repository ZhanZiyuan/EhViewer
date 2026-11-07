package com.ehviewer.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class GalleryLaunchPage(val originalIndex: Int) {
    HOMEPAGE(0),
    SUBSCRIPTION(1),
    WHATS_HOT(2),
    TOPLIST(3),
}

@Serializable
enum class GalleryToplistPeriod(val originalCode: String) {
    ALL_TIME("11"),
    PAST_YEAR("12"),
    PAST_MONTH("13"),
    YESTERDAY("15"),
}

data class GalleryLaunchSettings(
    val page: GalleryLaunchPage = GalleryLaunchPage.HOMEPAGE,
    val toplistPeriod: GalleryToplistPeriod = GalleryToplistPeriod.ALL_TIME,
)

class GalleryLaunchPreferencesException : Exception("Launch page preference is unavailable")

interface GalleryLaunchPreferences {
    suspend fun launchSettings(): GalleryLaunchSettings
    suspend fun setLaunchPage(page: GalleryLaunchPage)
    suspend fun setToplistPeriod(period: GalleryToplistPeriod)
}
