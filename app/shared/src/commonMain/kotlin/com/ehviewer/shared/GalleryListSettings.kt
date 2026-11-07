package com.ehviewer.shared

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
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryListPreferencesException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_list_comfortable
import ehviewer.app.shared.generated.resources.gallery_list_compact
import ehviewer.app.shared.generated.resources.gallery_list_layout
import ehviewer.app.shared.generated.resources.gallery_list_loading
import ehviewer.app.shared.generated.resources.gallery_list_save_failed
import ehviewer.app.shared.generated.resources.gallery_list_unavailable
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_show_comments
import ehviewer.app.shared.generated.resources.gallery_show_comments_summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.SwitchPreference

@Composable
internal fun GalleryListSettings(preferences: GalleryListPreferences, density: GalleryListDensity?, comments: Boolean?, failed: Boolean, onChanged: (GalleryListDensity) -> Unit, onCommentsChanged: (Boolean) -> Unit, onRetry: () -> Unit) {
    var saving by remember(preferences) { mutableStateOf(false) }
    var saveFailed by remember(preferences) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
        val choices = listOf(GalleryListDensity.COMFORTABLE to galleryString(Res.string.gallery_list_comfortable), GalleryListDensity.COMPACT to galleryString(Res.string.gallery_list_compact))
        GallerySettingsMenu(
            galleryString(Res.string.gallery_list_layout),
            choices.firstOrNull { it.first == density }?.second.orEmpty(),
            density,
            choices,
            enabled = density != null && !failed && !saveFailed && !saving,
        ) { value ->
            saving = true
            scope.launch {
                try {
                    preferences.setDensity(value)
                    onChanged(value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: GalleryListPreferencesException) {
                    saveFailed = true
                } finally {
                    saving = false
                }
            }
        }
        val enabled = comments != null && !failed && !saveFailed && !saving
        SwitchPreference(
            value = comments == true,
            onValueChange = { show ->
                saving = true
                scope.launch {
                    try {
                        preferences.setShowComments(show)
                        onCommentsChanged(show)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: GalleryListPreferencesException) {
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            },
            title = { Text(galleryString(Res.string.gallery_show_comments)) },
            summary = { Text(galleryString(Res.string.gallery_show_comments_summary)) },
            enabled = enabled,
        )
    }
}
