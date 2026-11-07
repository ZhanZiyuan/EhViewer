package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryFavoriteState
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_category
import ehviewer.app.shared.generated.resources.gallery_favorite
import ehviewer.app.shared.generated.resources.gallery_favorite_editor
import ehviewer.app.shared.generated.resources.gallery_favorite_note
import ehviewer.app.shared.generated.resources.gallery_favorite_reload
import ehviewer.app.shared.generated.resources.gallery_favorite_remove
import ehviewer.app.shared.generated.resources.gallery_favorite_save
import ehviewer.app.shared.generated.resources.gallery_favorite_uncertain
import ehviewer.app.shared.generated.resources.gallery_local_favorites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One editor for every platform. Only a fresh server-confirmed result closes it successfully. */
@Composable
internal fun GalleryFavoriteDialog(
    repository: GalleryFavoritesRepository,
    gid: Long,
    token: String,
    onUpdated: () -> Unit,
    onDismiss: () -> Unit,
    localFavorite: Boolean? = null,
    localBusy: Boolean = false,
    localFailure: String? = null,
    onToggleLocal: (() -> Unit)? = null,
) {
    var state by remember(repository, gid, token) { mutableStateOf<GalleryFavoriteState?>(null) }
    var selected by remember(repository, gid, token) { mutableIntStateOf(0) }
    var note by remember(repository, gid, token) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var requiresReload by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository, gid, token, attempt) {
        loading = true
        failure = null
        state = null
        try {
            val actual = repository.inspect(gid, token)
            state = actual
            selected = actual.category ?: 0
            note = actual.note.note
            requiresReload = false
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            failure = GalleryFailure.AUTHENTICATION
            requiresReload = true
        } catch (error: GalleryRequestException) {
            failure = error.failure
        } finally {
            loading = false
        }
    }
    val current = state
    val validNote = current != null && note.length <= current.note.maximumCharacters && '\u0000' !in note
    fun submit(category: Int?) {
        val loaded = state ?: return
        if (busy || localBusy || loading || requiresReload || !validNote) return
        busy = true
        failure = null
        scope.launch {
            try {
                repository.update(loaded, category, note)
                onUpdated()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                failure = GalleryFailure.AUTHENTICATION
                requiresReload = true
            } catch (error: GalleryRequestException) {
                failure = error.failure
                requiresReload = true
            } finally {
                busy = false
            }
        }
    }
    val editorDescription = galleryString(Res.string.gallery_favorite_editor)
    val noteDescription = galleryString(Res.string.gallery_favorite_note)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(galleryString(Res.string.gallery_favorite)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).semantics { contentDescription = editorDescription }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
                if (requiresReload) Text(galleryString(Res.string.gallery_favorite_uncertain))
                localFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onToggleLocal != null) {
                    TextButton(onClick = onToggleLocal, enabled = !busy && !localBusy) {
                        if (localFavorite == true) Icon(GalleryIcons.Check, contentDescription = null)
                        Text(galleryString(Res.string.gallery_local_favorites))
                    }
                }
                if (!loading) TextButton(onClick = { attempt++ }, enabled = !busy) { Text(galleryString(Res.string.gallery_favorite_reload)) }
                if (current != null) {
                    Text(galleryString(Res.string.gallery_category))
                    current.categories.forEachIndexed { index, category ->
                        Row(Modifier.fillMaxWidth().selectable(selected == index, enabled = !busy && !loading, role = Role.RadioButton) { selected = index }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == index, onClick = null, enabled = !busy && !loading)
                            Text("${index + 1}. $category")
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(galleryString(Res.string.gallery_favorite_note)) },
                        supportingText = { Text("${note.length} / ${current.note.maximumCharacters}") },
                        isError = !validNote,
                        enabled = !busy && !loading,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = noteDescription },
                    )
                    if (current.category != null) TextButton(onClick = { submit(null) }, enabled = !busy && !localBusy && !loading && !requiresReload && validNote) { Text(galleryString(Res.string.gallery_favorite_remove)) }
                }
            }
        },
        confirmButton = { Button(onClick = { submit(selected) }, enabled = current != null && !busy && !localBusy && !loading && !requiresReload && validNote) { Text(galleryString(Res.string.gallery_favorite_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(galleryString(Res.string.gallery_cancel)) } },
    )
}
