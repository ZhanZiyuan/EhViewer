package com.ehviewer.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListMode
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.GalleryListPresentationPreferences
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_list_comfortable
import ehviewer.app.shared.generated.resources.gallery_list_compact
import ehviewer.app.shared.generated.resources.gallery_list_layout
import ehviewer.app.shared.generated.resources.gallery_list_loading
import ehviewer.app.shared.generated.resources.gallery_list_save_failed
import ehviewer.app.shared.generated.resources.gallery_list_unavailable
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_settings_detail_thumbnail_size
import ehviewer.app.shared.generated.resources.gallery_settings_list_mode
import ehviewer.app.shared.generated.resources.gallery_settings_list_mode_detail
import ehviewer.app.shared.generated.resources.gallery_settings_list_mode_thumb
import ehviewer.app.shared.generated.resources.gallery_settings_thumbnail_columns
import ehviewer.app.shared.generated.resources.gallery_show_comments
import ehviewer.app.shared.generated.resources.gallery_show_comments_summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.SwitchPreference

@Composable
internal fun GalleryListSettings(
    preferences: GalleryListPreferences,
    density: GalleryListDensity?,
    comments: Boolean?,
    failed: Boolean,
    onChanged: (GalleryListDensity) -> Unit,
    onCommentsChanged: (Boolean) -> Unit,
    onRetry: () -> Unit,
    presentationPreferences: GalleryListPresentationPreferences? = null,
    presentation: GalleryListPresentation? = null,
    onPresentationChanged: (GalleryListPresentation) -> Unit = {},
) {
    var saving by remember(preferences) { mutableStateOf(false) }
    var saveFailed by remember(preferences) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(change: suspend () -> Unit) {
        if (saving || failed || saveFailed) return
        saving = true
        scope.launch {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GalleryListPreferencesException) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }
    Column {
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (density == null || saveFailed) {
            Text(
                galleryString(
                    if (saveFailed) {
                        Res.string.gallery_list_save_failed
                    } else if (failed) {
                        Res.string.gallery_list_unavailable
                    } else {
                        Res.string.gallery_list_loading
                    },
                ),
            )
            if (failed || saveFailed) {
                TextButton(onClick = {
                    saveFailed = false
                    onRetry()
                }, enabled = !saving) { Text(galleryString(Res.string.gallery_retry)) }
            }
        }
        val enabled = !failed && !saveFailed && !saving
        if (presentationPreferences != null && presentation != null) {
            val modes = listOf(GalleryListMode.DETAIL to galleryString(Res.string.gallery_settings_list_mode_detail), GalleryListMode.THUMBNAIL to galleryString(Res.string.gallery_settings_list_mode_thumb))
            GallerySettingsMenu(
                galleryString(Res.string.gallery_settings_list_mode),
                modes.first { it.first == presentation.mode }.second,
                presentation.mode,
                modes,
                enabled,
            ) { mode ->
                save {
                    presentationPreferences.setMode(mode)
                    onPresentationChanged(presentation.copy(mode = mode))
                }
            }
        }
        AnimatedVisibility(presentation?.mode != GalleryListMode.THUMBNAIL) {
            Column {
                if (presentationPreferences != null && presentation != null) {
                    GalleryIntSliderPreference(galleryString(Res.string.gallery_settings_detail_thumbnail_size), presentation.detailThumbnailSize, 20..60, 7, enabled) { size ->
                        save {
                            presentationPreferences.setDetailThumbnailSize(size)
                            onPresentationChanged(presentation.copy(detailThumbnailSize = size))
                        }
                    }
                }
                val choices = listOf(GalleryListDensity.COMFORTABLE to galleryString(Res.string.gallery_list_comfortable), GalleryListDensity.COMPACT to galleryString(Res.string.gallery_list_compact))
                GallerySettingsMenu(
                    galleryString(Res.string.gallery_list_layout),
                    choices.firstOrNull { it.first == density }?.second.orEmpty(),
                    density,
                    choices,
                    enabled = density != null && enabled,
                ) { value ->
                    save {
                        preferences.setDensity(value)
                        onChanged(value)
                    }
                }
            }
        }
        if (presentationPreferences != null && presentation != null) {
            GalleryIntSliderPreference(galleryString(Res.string.gallery_settings_thumbnail_columns), presentation.thumbnailColumns, 1..10, 8, enabled) { columns ->
                save {
                    presentationPreferences.setThumbnailColumns(columns)
                    onPresentationChanged(presentation.copy(thumbnailColumns = columns))
                }
            }
        }
        SwitchPreference(
            value = comments == true,
            onValueChange = { show ->
                save {
                    preferences.setShowComments(show)
                    onCommentsChanged(show)
                }
            },
            title = { Text(galleryString(Res.string.gallery_show_comments)) },
            summary = { Text(galleryString(Res.string.gallery_show_comments_summary)) },
            enabled = comments != null && enabled,
        )
    }
}
