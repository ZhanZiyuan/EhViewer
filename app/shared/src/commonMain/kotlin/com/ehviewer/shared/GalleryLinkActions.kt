package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_link_copied
import ehviewer.app.shared.generated.resources.gallery_link_copy
import ehviewer.app.shared.generated.resources.gallery_link_copy_failed
import ehviewer.app.shared.generated.resources.gallery_link_open
import ehviewer.app.shared.generated.resources.gallery_link_open_failed
import ehviewer.app.shared.generated.resources.gallery_link_opened
import ehviewer.app.shared.generated.resources.gallery_link_share
import ehviewer.app.shared.generated.resources.gallery_link_share_failed
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource

// CMP 1.11.1 has no common plain-text ClipEntry factory. This platform-backed API
// keeps actual Android/Desktop/UIKit clipboard behavior until that common API exists.
@Suppress("DEPRECATION")
@Composable
internal fun GalleryLinkActions(url: String, content: @Composable (copy: () -> Unit, open: () -> Unit, share: () -> Unit) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val browser = LocalUriHandler.current
    val nativeShare = rememberGalleryLinkSharer()
    var message by remember(url) { mutableStateOf<StringResource?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var shareOpen by remember(url) { mutableStateOf(false) }
    var notice by remember(url) { mutableIntStateOf(0) }
    val snackbar = remember(url) { SnackbarHostState() }
    val noticeText = message?.let { galleryString(it) }
    LaunchedEffect(notice, noticeText, shareOpen) {
        if (!shareOpen && noticeText != null) snackbar.showSnackbar(noticeText)
    }
    fun perform(success: StringResource?, failure: StringResource, action: () -> Unit) {
        notice++
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
    val copy = { perform(Res.string.gallery_link_copied, Res.string.gallery_link_copy_failed) { clipboard.setText(AnnotatedString(url)) } }
    val open = { perform(Res.string.gallery_link_opened, Res.string.gallery_link_open_failed) { browser.openUri(url) } }
    val share = {
        if (nativeShare == null) {
            message = null
            shareOpen = true
        } else {
            // Presenting a chooser is not evidence that the user shared anything.
            perform(null, Res.string.gallery_link_share_failed) { nativeShare(url) }
        }
    }

    @Composable
    fun feedback() {
        message?.let {
            Text(galleryString(it), color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    // Overlay feedback over the whole detail viewport; never move the title, cover or grid.
    Box(Modifier.fillMaxSize()) {
        content(copy, open, share)
        if (!shareOpen) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    if (shareOpen) {
        AlertDialog(
            onDismissRequest = { shareOpen = false },
            title = { Text(galleryString(Res.string.gallery_link_share)) },
            text = {
                Column {
                    Text(url)
                    feedback()
                }
            },
            confirmButton = {
                FlowRow {
                    TextButton(onClick = {
                        copy()
                        if (!failed) shareOpen = false
                    }) { Text(galleryString(Res.string.gallery_link_copy)) }
                    TextButton(onClick = {
                        open()
                        if (!failed) shareOpen = false
                    }) { Text(galleryString(Res.string.gallery_link_open)) }
                }
            },
            dismissButton = { TextButton(onClick = { shareOpen = false }) { Text(galleryString(Res.string.gallery_cancel)) } },
        )
    }
}

/** Mobile opens the system share sheet; desktop exposes the same real copy/browser actions. */
@Composable
internal expect fun rememberGalleryLinkSharer(): ((String) -> Unit)?
