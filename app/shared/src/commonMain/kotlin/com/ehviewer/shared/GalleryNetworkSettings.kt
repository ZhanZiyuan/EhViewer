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
import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.GalleryNetworkPreferencesException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_network
import ehviewer.app.shared.generated.resources.gallery_network_loading
import ehviewer.app.shared.generated.resources.gallery_network_reload
import ehviewer.app.shared.generated.resources.gallery_network_save_failed
import ehviewer.app.shared.generated.resources.gallery_network_unavailable
import ehviewer.app.shared.generated.resources.gallery_timeout_default
import ehviewer.app.shared.generated.resources.gallery_timeout_help
import ehviewer.app.shared.generated.resources.gallery_timeout_seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GalleryNetworkSettings(preferences: GalleryNetworkPreferences) {
    var seconds by remember(preferences) { mutableStateOf<Int?>(null) }
    var ready by remember(preferences) { mutableStateOf(false) }
    var failed by remember(preferences) { mutableStateOf(false) }
    var saveFailed by remember(preferences) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(preferences, attempt) {
        ready = false
        failed = false
        try {
            seconds = preferences.requestTimeoutSeconds()
            ready = true
            saveFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryNetworkPreferencesException) {
            failed = true
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(galleryString(Res.string.gallery_network), style = MaterialTheme.typography.titleMedium)
        Text(galleryString(Res.string.gallery_timeout_help), style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!ready || saveFailed) {
            Text(
                galleryString(
                    if (saveFailed) {
                        Res.string.gallery_network_save_failed
                    } else if (failed) {
                        Res.string.gallery_network_unavailable
                    } else {
                        Res.string.gallery_network_loading
                    },
                ),
            )
            if (failed || saveFailed) TextButton(onClick = { attempt++ }, enabled = !busy) { Text(galleryString(Res.string.gallery_network_reload)) }
        }
        for (choice in listOf(null, 10, 30, 60, 120)) {
            SettingsChoice(
                if (choice == null) galleryString(Res.string.gallery_timeout_default) else galleryString(Res.string.gallery_timeout_seconds, choice),
                ready && seconds == choice,
                ready && !saveFailed && !busy,
            ) {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            preferences.setRequestTimeoutSeconds(choice)
                            seconds = choice
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: GalleryNetworkPreferencesException) {
                            saveFailed = true
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}
