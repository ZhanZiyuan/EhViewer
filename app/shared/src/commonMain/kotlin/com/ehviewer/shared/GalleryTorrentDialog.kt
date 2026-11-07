package com.ehviewer.shared

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ehviewer.core.network.gallery.GalleryContent
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GalleryTorrent
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_link_copy
import ehviewer.app.shared.generated.resources.gallery_link_copy_failed
import ehviewer.app.shared.generated.resources.gallery_torrent_open_failed
import ehviewer.app.shared.generated.resources.gallery_torrents
import kotlinx.coroutines.CancellationException

/** Original compact rows and copy-on-missing-handler behavior, backed by the shared Rust decoder. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryTorrentDialog(content: GalleryContent, gid: Long, token: String, onEmpty: () -> Unit, onCopied: () -> Unit, onDismiss: () -> Unit) {
    var entries by remember(content, gid, token) { mutableStateOf<List<GalleryTorrent>?>(null) }
    var failure by remember(content, gid, token) { mutableStateOf<GalleryFailure?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var copyFallback by remember(content, gid, token) { mutableStateOf<GalleryTorrent?>(null) }
    val clipboard = LocalClipboardManager.current
    val handler = LocalUriHandler.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    fun copy(torrent: GalleryTorrent) {
        try {
            clipboard.setText(AnnotatedString(torrent.magnet(gid)))
            onCopied()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the list available and offer an explicit retry only after both system actions fail.
            copyFallback = torrent
        }
    }
    LaunchedEffect(content, gid, token, attempt) {
        entries = null
        failure = null
        try {
            val loaded = content.torrents(gid, token)
            if (loaded.isEmpty()) onEmpty() else entries = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        }
    }
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(min = 280.dp, max = 640.dp).focusRequester(focus).onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }.focusable(),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(vertical = 8.dp).heightIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(galleryString(Res.string.gallery_torrents), style = MaterialTheme.typography.headlineSmall)
                failure?.let { GalleryError(it) { attempt++ } }
                if (entries == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                copyFallback?.let { pending ->
                    Text(galleryString(Res.string.gallery_torrent_open_failed), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                    Text(galleryString(Res.string.gallery_link_copy_failed), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { copy(pending) }) { Text(galleryString(Res.string.gallery_link_copy)) }
                }
                if (entries != null) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entries.orEmpty(), key = { it.url }) { torrent ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    try {
                                        handler.openUri(torrent.magnet(gid))
                                        onDismiss()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        copy(torrent)
                                    }
                                }.minimumInteractiveComponentSize().padding(horizontal = 8.dp),
                            ) {
                                Text(torrent.name, Modifier.basicMarquee(spacing = MarqueeSpacing(16.dp), velocity = 60.dp), color = MaterialTheme.colorScheme.primary)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    val label = MaterialTheme.typography.labelLarge
                                    Text(torrent.posted, Modifier.weight(2.5f), color = if (torrent.outdated) MaterialTheme.colorScheme.error else Color.Unspecified, style = label)
                                    Text(torrent.uploader, Modifier.weight(2.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = label)
                                    Text(torrent.size, Modifier.weight(2f), textAlign = TextAlign.End, style = label)
                                    Text("↑${torrent.seeds} ↓${torrent.peers} ✓${torrent.downloads}", Modifier.weight(3f), textAlign = TextAlign.End, style = label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
