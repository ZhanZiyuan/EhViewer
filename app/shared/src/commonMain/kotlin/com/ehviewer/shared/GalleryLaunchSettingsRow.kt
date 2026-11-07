package com.ehviewer.shared

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.ehviewer.core.domain.GalleryLaunchPage
import com.ehviewer.core.domain.GalleryToplistPeriod
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_feed_homepage
import ehviewer.app.shared.generated.resources.gallery_feed_subscription
import ehviewer.app.shared.generated.resources.gallery_feed_toplist
import ehviewer.app.shared.generated.resources.gallery_feed_whats_hot
import ehviewer.app.shared.generated.resources.gallery_launch_preferences_unavailable
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_settings_launch_page
import ehviewer.app.shared.generated.resources.gallery_toplist_all_time
import ehviewer.app.shared.generated.resources.gallery_toplist_past_month
import ehviewer.app.shared.generated.resources.gallery_toplist_past_year
import ehviewer.app.shared.generated.resources.gallery_toplist_yesterday

@Composable
internal fun GalleryLaunchSettingsRow(page: GalleryLaunchPage?, failed: Boolean, saving: Boolean, onSelect: (GalleryLaunchPage) -> Unit, onRetry: () -> Unit) {
    GallerySettingsMenu(
        galleryString(Res.string.gallery_settings_launch_page),
        page?.let { galleryLaunchPageLabel(it) }.orEmpty(),
        page,
        GalleryLaunchPage.entries.map { it to galleryLaunchPageLabel(it) },
        enabled = page != null && !failed && !saving,
        onChoose = onSelect,
    )
    if (failed) {
        Text(galleryString(Res.string.gallery_launch_preferences_unavailable))
        TextButton(onClick = onRetry) { Text(galleryString(Res.string.gallery_retry)) }
    }
}

@Composable
internal fun galleryLaunchPageLabel(page: GalleryLaunchPage) = galleryString(
    when (page) {
        GalleryLaunchPage.HOMEPAGE -> Res.string.gallery_feed_homepage
        GalleryLaunchPage.SUBSCRIPTION -> Res.string.gallery_feed_subscription
        GalleryLaunchPage.WHATS_HOT -> Res.string.gallery_feed_whats_hot
        GalleryLaunchPage.TOPLIST -> Res.string.gallery_feed_toplist
    },
)

@Composable
internal fun galleryToplistPeriodLabel(period: GalleryToplistPeriod) = galleryString(
    when (period) {
        GalleryToplistPeriod.ALL_TIME -> Res.string.gallery_toplist_all_time
        GalleryToplistPeriod.PAST_YEAR -> Res.string.gallery_toplist_past_year
        GalleryToplistPeriod.PAST_MONTH -> Res.string.gallery_toplist_past_month
        GalleryToplistPeriod.YESTERDAY -> Res.string.gallery_toplist_yesterday
    },
)
