package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.GalleryFavoritePreferences
import com.ehviewer.core.domain.GalleryFavoritePreferencesException
import com.ehviewer.core.domain.GalleryFavoriteTarget
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryFavoritesRepository
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_default_favorite
import ehviewer.app.shared.generated.resources.gallery_default_favorite_disabled
import ehviewer.app.shared.generated.resources.gallery_default_favorite_unavailable
import ehviewer.app.shared.generated.resources.gallery_default_favorite_warning
import ehviewer.app.shared.generated.resources.gallery_local_favorites
import ehviewer.app.shared.generated.resources.gallery_retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference

@Composable
internal fun GalleryDefaultFavoriteSettings(store: GalleryFavoritePreferences, repository: GalleryFavoritesRepository) {
    var target by remember(store) { mutableStateOf<GalleryFavoriteTarget?>(null) }
    var categories by remember(repository) { mutableStateOf(emptyList<String>()) }
    var failed by remember(store) { mutableStateOf(false) }
    var categoriesFailure by remember(repository) { mutableStateOf<GalleryFailure?>(null) }
    var busy by remember(store) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, attempt) {
        failed = false
        try {
            target = store.target()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryFavoritePreferencesException) {
            failed = true
        }
    }
    LaunchedEffect(repository, attempt) {
        categories = emptyList()
        categoriesFailure = null
        try {
            categories = repository.availableCategories()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            categoriesFailure = GalleryFailure.AUTHENTICATION
        } catch (error: GalleryRequestException) {
            categoriesFailure = error.failure
        }
    }
    GalleryDefaultFavoritePreference(
        selected = target ?: GalleryFavoriteTarget.Ask,
        cloudNames = categories,
        enabled = target != null && !busy && !failed,
    ) { selected ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    store.setTarget(selected)
                    target = selected
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: GalleryFavoritePreferencesException) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
    val error = if (failed) galleryString(Res.string.gallery_default_favorite_unavailable) else categoriesFailure?.userMessage()
    if (error != null) {
        Text(error, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { attempt++ }, enabled = !busy) { Text(galleryString(Res.string.gallery_retry)) }
    }
}

/** Original EH row and selection dialog, with cloud names supplied by the current account only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryDefaultFavoritePreference(
    selected: GalleryFavoriteTarget,
    cloudNames: List<String>,
    enabled: Boolean,
    onChoose: (GalleryFavoriteTarget) -> Unit,
) {
    val title = galleryString(Res.string.gallery_default_favorite)
    val disabled = galleryString(Res.string.gallery_default_favorite_disabled)
    val warning = galleryString(Res.string.gallery_default_favorite_warning)
    val local = galleryString(Res.string.gallery_local_favorites)
    val choices = listOf(GalleryFavoriteTarget.Ask to disabled, GalleryFavoriteTarget.Local to local) +
        cloudNames.mapIndexed { slot, label -> GalleryFavoriteTarget(slot) to label }
    val summary = when (selected) {
        GalleryFavoriteTarget.Ask -> warning
        GalleryFavoriteTarget.Local -> local
        else -> cloudNames.getOrNull(selected.slot) ?: warning
    }
    var open by rememberSaveable { mutableStateOf(false) }
    Preference(
        title = { Text(title) },
        summary = { Text(summary) },
        enabled = enabled,
        onClick = { open = true },
    )
    if (open && enabled) {
        BasicAlertDialog(onDismissRequest = { open = false }) {
            Surface(
                modifier = Modifier.width(280.dp),
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
                    LazyColumn {
                        items(choices, key = { it.first.slot }) { (target, label) ->
                            val checked = target == selected
                            ListItem(
                                headlineContent = {
                                    val style = MaterialTheme.typography.titleMedium
                                    Text(label, style = if (checked) style.copy(color = MaterialTheme.colorScheme.primary) else style)
                                },
                                trailingContent = if (checked) {
                                    { Icon(GalleryIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                } else {
                                    null
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth().selectable(checked, role = Role.RadioButton) {
                                    open = false
                                    if (target != selected) onChoose(target)
                                }.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
