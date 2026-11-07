package com.ehviewer.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ehviewer.core.domain.GalleryListPreferencesException
import com.ehviewer.core.domain.GalleryQuickSearch
import com.ehviewer.core.domain.GalleryQuickSearchError
import com.ehviewer.core.domain.GalleryQuickSearchException
import com.ehviewer.core.domain.GalleryQuickSearchPreferences
import com.ehviewer.core.domain.GalleryQuickSearchStore
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_home_ok
import ehviewer.app.shared.generated.resources.gallery_quick_search
import ehviewer.app.shared.generated.resources.gallery_quick_search_add
import ehviewer.app.shared.generated.resources.gallery_quick_search_delete
import ehviewer.app.shared.generated.resources.gallery_quick_search_delete_named
import ehviewer.app.shared.generated.resources.gallery_quick_search_duplicate
import ehviewer.app.shared.generated.resources.gallery_quick_search_empty
import ehviewer.app.shared.generated.resources.gallery_quick_search_help
import ehviewer.app.shared.generated.resources.gallery_quick_search_help_body
import ehviewer.app.shared.generated.resources.gallery_quick_search_move_down
import ehviewer.app.shared.generated.resources.gallery_quick_search_move_up
import ehviewer.app.shared.generated.resources.gallery_quick_search_name_empty
import ehviewer.app.shared.generated.resources.gallery_quick_search_name_in_use
import ehviewer.app.shared.generated.resources.gallery_quick_search_reorder
import ehviewer.app.shared.generated.resources.gallery_quick_search_save_progress
import ehviewer.app.shared.generated.resources.gallery_quick_search_unavailable
import ehviewer.app.shared.generated.resources.gallery_quick_search_unsupported
import ehviewer.app.shared.generated.resources.gallery_retry
import kotlinx.coroutines.launch

class GalleryQuickSearchControls(val store: GalleryQuickSearchStore, val preferences: GalleryQuickSearchPreferences)

/** Trailing v1.15.10 Quick search sheet, backed by the same Room records on every host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryQuickSearchSheet(
    controls: GalleryQuickSearchControls,
    snapshot: GalleryQuickSearch?,
    resumeCursor: String?,
    onOpen: (GalleryQuickSearch) -> Boolean,
    onClose: () -> Unit,
) {
    val store = controls.store
    val scope = rememberCoroutineScope()
    var rows by remember(store) { mutableStateOf<List<GalleryQuickSearch>?>(null) }
    var saveProgress by remember(controls.preferences) { mutableStateOf<Boolean?>(null) }
    var busy by remember(store) { mutableStateOf(false) }
    var loadAttempt by remember(store) { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var unsupported by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var withProgress by rememberSaveable { mutableStateOf(false) }
    var emptyName by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<GalleryQuickSearchError?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GalleryQuickSearch?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(controls, loadAttempt) {
        failed = false
        try {
            rows = store.list()
            saveProgress = controls.preferences.saveQuickSearchProgress()
        } catch (_: GalleryQuickSearchException) {
            failed = true
        } catch (_: GalleryListPreferencesException) {
            failed = true
        }
    }

    fun change(action: suspend () -> Unit, done: suspend () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                rows = store.list()
                done()
            } catch (error: GalleryQuickSearchException) {
                if (adding) saveError = error.reason else failed = true
            } finally {
                busy = false
            }
        }
    }

    fun move(id: Long, before: Long?) = change({ store.move(id, before) })

    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(DrawerDefaults.scrimColor).clickable(enabled = !busy, onClick = onClose))
            val width = (maxWidth - 112.dp).coerceIn(160.dp, 360.dp)
            Surface(
                Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = ShapeDefaults.Large.copy(topEnd = CornerSize(0), bottomEnd = CornerSize(0)),
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TopAppBar(
                        title = { Text(galleryString(Res.string.gallery_quick_search)) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        windowInsets = WindowInsets(0),
                        actions = {
                            IconButton(onClick = { help = true }) {
                                Icon(GalleryIcons.Help, galleryString(Res.string.gallery_quick_search_help))
                            }
                            IconButton(onClick = {
                                name = snapshot?.name.orEmpty()
                                withProgress = saveProgress == true && resumeCursor != null
                                emptyName = false
                                saveError = null
                                adding = true
                            }, enabled = snapshot != null && !busy && rows != null && saveProgress != null) {
                                Icon(GalleryIcons.Add, galleryString(Res.string.gallery_quick_search_add))
                            }
                        },
                    )
                    if (failed) {
                        Text(galleryString(Res.string.gallery_quick_search_unavailable), Modifier.padding(horizontal = 16.dp))
                        TextButton(onClick = { loadAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
                    }
                    if (unsupported) Text(galleryString(Res.string.gallery_quick_search_unsupported), Modifier.padding(horizontal = 16.dp))
                    val saved = rows
                    when {
                        saved == null && !failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        saved != null && saved.isEmpty() -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(galleryString(Res.string.gallery_quick_search_empty))
                        }
                        saved != null -> LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                            items(saved, key = { requireNotNull(it.id) }) { item ->
                                val id = requireNotNull(item.id)
                                val index = saved.indexOf(item)
                                val dismiss = rememberSwipeToDismissBoxState()
                                LaunchedEffect(dismiss.currentValue) {
                                    if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) {
                                        deleting = item
                                        dismiss.reset()
                                    }
                                }
                                val up = galleryString(Res.string.gallery_quick_search_move_up)
                                val down = galleryString(Res.string.gallery_quick_search_move_down)
                                val delete = galleryString(Res.string.gallery_quick_search_delete)
                                SwipeToDismissBox(state = dismiss, backgroundContent = {}, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = !busy) {
                                    ListItem(
                                        headlineContent = { Text(item.name) },
                                        modifier = Modifier.clickable(enabled = !busy) {
                                            if (onOpen(item)) onClose() else unsupported = true
                                        }.semantics {
                                            customActions = buildList {
                                                add(
                                                    CustomAccessibilityAction(delete) {
                                                        deleting = item
                                                        true
                                                    },
                                                )
                                                if (index > 0) {
                                                    add(
                                                        CustomAccessibilityAction(up) {
                                                            move(id, saved[index - 1].id)
                                                            true
                                                        },
                                                    )
                                                }
                                                if (index < saved.lastIndex) {
                                                    add(
                                                        CustomAccessibilityAction(down) {
                                                            move(id, saved.getOrNull(index + 2)?.id)
                                                            true
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                        trailingContent = {
                                            var distance by remember(id) { mutableFloatStateOf(0f) }
                                            var start by remember(id) { mutableIntStateOf(0) }
                                            IconButton(
                                                onClick = {},
                                                enabled = !busy,
                                                modifier = Modifier.pointerInput(saved, busy) {
                                                    if (!busy) {
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = {
                                                                val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                                                                start = visible?.let { it.offset + it.size / 2 } ?: 0
                                                                distance = 0f
                                                            },
                                                            onDragEnd = {
                                                                val end = start + distance
                                                                val destination = list.layoutInfo.visibleItemsInfo.lastOrNull { it.offset + it.size / 2 <= end }?.index
                                                                if (destination != null && destination != index) {
                                                                    val before = if (destination < index) saved[destination].id else saved.getOrNull(destination + 1)?.id
                                                                    move(id, before)
                                                                }
                                                            },
                                                        ) { change, amount ->
                                                            change.consume()
                                                            distance += amount.y
                                                        }
                                                    }
                                                },
                                            ) { Icon(GalleryIcons.Reorder, galleryString(Res.string.gallery_quick_search_reorder)) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(galleryString(Res.string.gallery_quick_search)) },
            text = { Text(galleryString(Res.string.gallery_quick_search_help_body)) },
            confirmButton = { TextButton(onClick = { help = false }) { Text(galleryString(Res.string.gallery_home_ok)) } },
        )
    }
    if (adding && snapshot != null) {
        val error = when {
            emptyName -> Res.string.gallery_quick_search_name_empty
            saveError == GalleryQuickSearchError.NAME_IN_USE -> Res.string.gallery_quick_search_name_in_use
            saveError == GalleryQuickSearchError.SAME_POSITION -> Res.string.gallery_quick_search_duplicate
            saveError != null -> Res.string.gallery_quick_search_unavailable
            else -> null
        }
        AlertDialog(
            onDismissRequest = { if (!busy) adding = false },
            title = { Text(galleryString(Res.string.gallery_quick_search_add)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it.take(480)
                            emptyName = false
                            saveError = null
                        },
                        label = { Text(galleryString(Res.string.gallery_quick_search)) },
                        isError = error != null,
                        supportingText = error?.let { resource -> { Text(galleryString(resource)) } },
                        singleLine = true,
                    )
                    if (resumeCursor != null) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = withProgress, enabled = !busy, role = Role.Checkbox, onValueChange = { withProgress = it }).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(withProgress, null)
                            Text(galleryString(Res.string.gallery_quick_search_save_progress), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim()
                    if (clean.isEmpty()) {
                        emptyName = true
                    } else {
                        val anchor = resumeCursor.takeIf { withProgress }
                        val chosenName = if (anchor == null) clean else "$clean@$anchor"
                        change({ store.save(snapshot.copy(name = chosenName)) }) {
                            adding = false
                            if (resumeCursor != null && withProgress != saveProgress) {
                                try {
                                    controls.preferences.setSaveQuickSearchProgress(withProgress)
                                    saveProgress = withProgress
                                } catch (_: GalleryListPreferencesException) {
                                    // The shortcut already exists; retry only the preference read.
                                    saveProgress = null
                                    failed = true
                                }
                            }
                        }
                    }
                }, enabled = !busy) { Text(galleryString(Res.string.gallery_home_ok)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }, enabled = !busy) { Text(galleryString(Res.string.gallery_cancel)) } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            text = { Text(galleryString(Res.string.gallery_quick_search_delete_named, item.name)) },
            confirmButton = {
                TextButton(onClick = { change({ store.remove(requireNotNull(item.id)) }) { deleting = null } }, enabled = !busy) {
                    Text(galleryString(Res.string.gallery_quick_search_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text(galleryString(Res.string.gallery_cancel)) } },
        )
    }
}
