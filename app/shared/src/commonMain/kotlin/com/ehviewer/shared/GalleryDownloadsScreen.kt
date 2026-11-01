package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.ehviewer.core.domain.download.GalleryDownloadFailure
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.network.download.GalleryDownloadOpen
import com.ehviewer.core.network.download.GalleryDownloads
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_download_complete
import ehviewer.app.shared.generated.resources.gallery_download_delete
import ehviewer.app.shared.generated.resources.gallery_download_downloading
import ehviewer.app.shared.generated.resources.gallery_download_failed
import ehviewer.app.shared.generated.resources.gallery_download_paused
import ehviewer.app.shared.generated.resources.gallery_download_queued
import ehviewer.app.shared.generated.resources.gallery_download_storage_failed
import ehviewer.app.shared.generated.resources.gallery_download_unknown
import ehviewer.app.shared.generated.resources.gallery_download_update_failed
import ehviewer.app.shared.generated.resources.gallery_downloads
import ehviewer.app.shared.generated.resources.gallery_downloads_empty
import ehviewer.app.shared.generated.resources.gallery_pause
import ehviewer.app.shared.generated.resources.gallery_read_offline
import ehviewer.app.shared.generated.resources.gallery_resume
import ehviewer.app.shared.generated.resources.gallery_retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GalleryDownloadsScreen(downloads: GalleryDownloads, ready: Boolean, failed: Boolean, retry: () -> Unit, backgroundNote: String?, onOpen: suspend (GalleryDownloadOpen) -> Unit, onBack: () -> Unit) {
    val tasks by downloads.tasks.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var operationFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        operationFailed = false
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationFailed = true
            } finally {
                busy = false
            }
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text(galleryString(Res.string.gallery_back)) }
        Text(galleryString(Res.string.gallery_downloads))
        backgroundNote?.let { Text(it) }
        if (failed || operationFailed) {
            Row {
                Text(galleryString(Res.string.gallery_download_update_failed), Modifier.weight(1f))
                TextButton(onClick = {
                    operationFailed = false
                    retry()
                }, enabled = !busy) { Text(galleryString(Res.string.gallery_retry)) }
            }
        }
        if (!ready && !failed || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ready && tasks.isEmpty()) Text(galleryString(Res.string.gallery_downloads_empty))
        LazyColumn(Modifier.weight(1f)) {
            items(tasks, key = { it.gid }) { task ->
                ListItem(
                    headlineContent = { Text(task.title) },
                    supportingContent = {
                        Column {
                            val state = when (task.state) {
                                GalleryDownloadState.QUEUED -> galleryString(Res.string.gallery_download_queued)
                                GalleryDownloadState.DOWNLOADING -> galleryString(Res.string.gallery_download_downloading)
                                GalleryDownloadState.PAUSED -> galleryString(Res.string.gallery_download_paused)
                                GalleryDownloadState.FAILED -> if (task.failure == GalleryDownloadFailure.STORAGE) galleryString(Res.string.gallery_download_storage_failed) else galleryString(Res.string.gallery_download_failed)
                                GalleryDownloadState.COMPLETE -> galleryString(Res.string.gallery_download_complete)
                            }
                            Text(if (task.pages > 0) "$state · ${task.finished} / ${task.pages}" else galleryString(Res.string.gallery_download_unknown, state))
                            Row {
                                when (task.state) {
                                    GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING -> TextButton(onClick = { perform { downloads.pause(task.gid) } }, enabled = ready && !busy) { Text(galleryString(Res.string.gallery_pause)) }
                                    GalleryDownloadState.PAUSED, GalleryDownloadState.FAILED -> TextButton(onClick = { perform { downloads.resume(task.gid) } }, enabled = ready && !busy) { Text(galleryString(Res.string.gallery_resume)) }
                                    GalleryDownloadState.COMPLETE -> TextButton(onClick = { perform { onOpen(downloads.open(task.gid)) } }, enabled = ready && !busy) { Text(galleryString(Res.string.gallery_read_offline)) }
                                }
                                TextButton(onClick = { perform { downloads.cancel(task.gid) } }, enabled = ready && !busy) { Text(galleryString(Res.string.gallery_download_delete)) }
                            }
                        }
                    },
                )
            }
        }
    }
}
