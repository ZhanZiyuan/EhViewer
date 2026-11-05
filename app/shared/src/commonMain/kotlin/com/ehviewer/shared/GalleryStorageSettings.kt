package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.domain.download.GalleryStorageSettings
import com.ehviewer.core.domain.download.GalleryStorageUsage
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_storage
import ehviewer.app.shared.generated.resources.gallery_storage_help
import ehviewer.app.shared.generated.resources.gallery_storage_limit_mib
import ehviewer.app.shared.generated.resources.gallery_storage_loading
import ehviewer.app.shared.generated.resources.gallery_storage_platform
import ehviewer.app.shared.generated.resources.gallery_storage_reload
import ehviewer.app.shared.generated.resources.gallery_storage_save_failed
import ehviewer.app.shared.generated.resources.gallery_storage_unavailable
import ehviewer.app.shared.generated.resources.gallery_storage_unlimited
import ehviewer.app.shared.generated.resources.gallery_storage_usage_bytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GalleryStorageSettingsSection(settings: GalleryStorageSettings?, onPlatformSettings: (() -> Unit)?) {
    if (settings == null && onPlatformSettings == null) {
        Text(galleryString(Res.string.gallery_storage_unavailable))
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Text(galleryString(Res.string.gallery_storage), style = MaterialTheme.typography.titleMedium)
        onPlatformSettings?.let { TextButton(onClick = it) { Text(galleryString(Res.string.gallery_storage_platform)) } }
        if (settings != null) DownloadBudgetSettings(settings)
    }
}

@Composable
private fun DownloadBudgetSettings(settings: GalleryStorageSettings) {
    var usage by remember(settings) { mutableStateOf<GalleryStorageUsage?>(null) }
    var failed by remember(settings) { mutableStateOf(false) }
    var saveFailed by remember(settings) { mutableStateOf(false) }
    var busy by remember(settings) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(settings, attempt) {
        usage = null
        failed = false
        try {
            usage = settings.usage()
            saveFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryDownloadStorageException) {
            failed = true
        }
    }
    Text(galleryString(Res.string.gallery_storage_help), style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val current = usage
    if (current != null) Text(galleryString(Res.string.gallery_storage_usage_bytes, current.imageBytes))
    if (current == null || saveFailed) {
        Text(
            galleryString(
                if (saveFailed) {
                    Res.string.gallery_storage_save_failed
                } else if (failed) {
                    Res.string.gallery_storage_unavailable
                } else {
                    Res.string.gallery_storage_loading
                },
            ),
        )
    }
    TextButton(onClick = { attempt++ }, enabled = !busy) { Text(galleryString(Res.string.gallery_storage_reload)) }
    val choices = listOf(null, 256, 1024, 5120, 10240, 20480) + listOfNotNull(current?.limitMiB).filter { it !in listOf(256, 1024, 5120, 10240, 20480) }
    for (limit in choices) {
        SettingsChoice(
            if (limit == null) galleryString(Res.string.gallery_storage_unlimited) else galleryString(Res.string.gallery_storage_limit_mib, limit),
            current != null && current.limitMiB == limit,
            current != null && !saveFailed && !busy,
        ) {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        settings.setDownloadLimitMiB(limit)
                        usage = settings.usage()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: GalleryDownloadStorageException) {
                        saveFailed = true
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
