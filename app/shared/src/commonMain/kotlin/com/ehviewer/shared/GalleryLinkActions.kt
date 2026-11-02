package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_link_copied
import ehviewer.app.shared.generated.resources.gallery_link_copy
import ehviewer.app.shared.generated.resources.gallery_link_copy_failed
import ehviewer.app.shared.generated.resources.gallery_link_open
import ehviewer.app.shared.generated.resources.gallery_link_open_failed
import ehviewer.app.shared.generated.resources.gallery_link_opened
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource

// CMP 1.11.1 has no common plain-text ClipEntry factory. This platform-backed API
// keeps actual Android/Desktop/UIKit clipboard behavior until that common API exists.
@Suppress("DEPRECATION")
@Composable
internal fun GalleryLinkActions(url: String) {
    val clipboard = LocalClipboardManager.current
    val browser = LocalUriHandler.current
    var message by remember(url) { mutableStateOf<StringResource?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    fun perform(success: StringResource, failure: StringResource, action: () -> Unit) {
        message = null
        try {
            action()
            failed = false
            message = success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            message = failure
        }
    }
    Column {
        FlowRow {
            TextButton(onClick = { perform(Res.string.gallery_link_copied, Res.string.gallery_link_copy_failed) { clipboard.setText(AnnotatedString(url)) } }) {
                Text(galleryString(Res.string.gallery_link_copy))
            }
            TextButton(onClick = { perform(Res.string.gallery_link_opened, Res.string.gallery_link_open_failed) { browser.openUri(url) } }) {
                Text(galleryString(Res.string.gallery_link_open))
            }
        }
        message?.let {
            Text(galleryString(it), color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
