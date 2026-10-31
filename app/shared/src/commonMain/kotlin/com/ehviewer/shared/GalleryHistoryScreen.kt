package com.ehviewer.shared

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.ehviewer.core.domain.GalleryHistoryException
import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.model.BaseGalleryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GalleryHistoryScreen(store: GalleryHistoryStore, onOpen: (BaseGalleryInfo) -> Unit, onBack: () -> Unit) {
    var entries by remember(store) { mutableStateOf<List<BaseGalleryInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, attempt) {
        loading = true
        failed = false
        try {
            entries = store.recent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryHistoryException) {
            failed = true
        } finally {
            loading = false
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("Back to search") }
        Text("Reading history")
        if (loading || deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) {
            Row {
                Text("Reading history is unavailable.", Modifier.weight(1f))
                TextButton(onClick = { attempt++ }, enabled = !loading && !deleting) { Text("Retry") }
            }
        } else if (!loading && entries.isEmpty()) {
            Text("No reading history yet.")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.gid }) { gallery ->
                ListItem(
                    headlineContent = { Text(gallery.title ?: gallery.titleJpn.orEmpty()) },
                    modifier = Modifier.clickable { onOpen(gallery) },
                    trailingContent = {
                        TextButton(onClick = {
                            if (!deleting) {
                                deleting = true
                                scope.launch {
                                    try {
                                        store.forget(gallery.gid)
                                        entries = entries.filterNot { it.gid == gallery.gid }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: GalleryHistoryException) {
                                        failed = true
                                    } finally {
                                        deleting = false
                                    }
                                }
                            }
                        }, enabled = !deleting && !loading) { Text("Remove from history") }
                    },
                )
            }
        }
    }
}
