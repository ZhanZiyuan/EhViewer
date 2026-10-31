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
        TextButton(onClick = onBack) { Text("Back") }
        Text("Downloads")
        backgroundNote?.let { Text(it) }
        if (failed || operationFailed) {
            Row {
                Text("Downloads could not be updated. Try again.", Modifier.weight(1f))
                TextButton(onClick = {
                    operationFailed = false
                    retry()
                }, enabled = !busy) { Text("Retry") }
            }
        }
        if (!ready && !failed || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ready && tasks.isEmpty()) Text("No downloads yet.")
        LazyColumn(Modifier.weight(1f)) {
            items(tasks, key = { it.gid }) { task ->
                ListItem(
                    headlineContent = { Text(task.title) },
                    supportingContent = {
                        Column {
                            val state = when (task.state) {
                                GalleryDownloadState.QUEUED -> "Queued"
                                GalleryDownloadState.DOWNLOADING -> "Downloading"
                                GalleryDownloadState.PAUSED -> "Paused"
                                GalleryDownloadState.FAILED -> if (task.failure == GalleryDownloadFailure.STORAGE) "Download storage unavailable — retry or delete" else "Failed — resume to retry"
                                GalleryDownloadState.COMPLETE -> "Complete"
                            }
                            Text(if (task.pages > 0) "$state · ${task.finished} / ${task.pages}" else "$state · total unknown")
                            Row {
                                when (task.state) {
                                    GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING -> TextButton(onClick = { perform { downloads.pause(task.gid) } }, enabled = ready && !busy) { Text("Pause") }
                                    GalleryDownloadState.PAUSED, GalleryDownloadState.FAILED -> TextButton(onClick = { perform { downloads.resume(task.gid) } }, enabled = ready && !busy) { Text("Resume") }
                                    GalleryDownloadState.COMPLETE -> TextButton(onClick = { perform { onOpen(downloads.open(task.gid)) } }, enabled = ready && !busy) { Text("Read offline") }
                                }
                                TextButton(onClick = { perform { downloads.cancel(task.gid) } }, enabled = ready && !busy) { Text("Delete download and files") }
                            }
                        }
                    },
                )
            }
        }
    }
}
