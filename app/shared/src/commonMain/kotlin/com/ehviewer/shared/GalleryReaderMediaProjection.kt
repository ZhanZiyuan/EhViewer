package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ehviewer.core.network.gallery.GalleryFailure
import kotlinx.coroutines.CancellationException

/** A view over the same decoded poster. Turning cropping off restores the exact original media. */
internal class GalleryReaderMediaProjection(source: GalleryMedia?) {
    var media by mutableStateOf(source)
    var failed by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)
}

@Composable
internal fun rememberGalleryReaderMedia(source: GalleryMedia?, crop: Boolean): GalleryReaderMediaProjection {
    val state = remember(source, crop) { GalleryReaderMediaProjection(source) }
    LaunchedEffect(state, state.attempt) {
        state.failed = false
        if (source != null && crop) {
            try {
                state.media = source.withoutBorders()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.failed = true
            }
        }
    }
    return state
}

@Composable
internal fun GalleryReaderMediaImage(state: GalleryReaderMediaProjection, description: String, modifier: Modifier, visible: Boolean = true) {
    val media = state.media ?: return
    Box(modifier, contentAlignment = Alignment.Center) {
        GalleryMediaImage(media, description, Modifier.fillMaxSize(), visible)
        if (state.failed) Surface { GalleryError(GalleryFailure.INVALID_RESPONSE) { state.attempt++ } }
    }
}
