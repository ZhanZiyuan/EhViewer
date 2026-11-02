package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GalleryListSettings(preferences: GalleryListPreferences, density: GalleryListDensity?, failed: Boolean, onChanged: (GalleryListDensity) -> Unit, onRetry: () -> Unit) {
    var saving by remember(preferences) { mutableStateOf(false) }
    var saveFailed by remember(preferences) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        Text(galleryString(Res.string.gallery_list_layout), style = MaterialTheme.typography.titleMedium)
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
        for (value in GalleryListDensity.entries) {
            SettingsChoice(
                galleryString(if (value == GalleryListDensity.COMFORTABLE) Res.string.gallery_list_comfortable else Res.string.gallery_list_compact),
                selected = density == value,
                enabled = density != null && !failed && !saveFailed && !saving,
            ) {
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
        }
    }
}
