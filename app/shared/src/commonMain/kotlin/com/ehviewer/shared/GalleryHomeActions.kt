package com.ehviewer.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_home_actions
import ehviewer.app.shared.generated.resources.gallery_home_actions_close
import ehviewer.app.shared.generated.resources.gallery_home_go_to
import ehviewer.app.shared.generated.resources.gallery_home_go_to_hint
import ehviewer.app.shared.generated.resources.gallery_home_invalid_number
import ehviewer.app.shared.generated.resources.gallery_home_last_page
import ehviewer.app.shared.generated.resources.gallery_home_ok
import ehviewer.app.shared.generated.resources.gallery_home_out_of_range
import ehviewer.app.shared.generated.resources.gallery_home_refresh
import ehviewer.app.shared.generated.resources.gallery_home_shuffle
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource

private const val FAB_ANIMATION_MILLIS = 300

/** The v1.15.10 gallery FAB has one collapsed button and a dismissible stack of actions. */
@Composable
internal fun GalleryHomeActions(
    visible: Boolean,
    canShuffle: Boolean,
    canJump: Boolean,
    toplistPage: Int?,
    onRefresh: () -> Unit,
    onShuffle: () -> Unit,
    onSeekDate: (String) -> Unit,
    onJumpToPage: (Int) -> Unit,
    onLast: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var jumpOpen by rememberSaveable { mutableStateOf(false) }
    var shown by remember { mutableStateOf(visible) }
    fun activate(action: () -> Unit) {
        if (!expanded) return
        expanded = false
        action()
    }
    LaunchedEffect(visible) {
        if (!visible) {
            expanded = false
            shown = false
        } else if (!shown) {
            delay(FAB_ANIMATION_MILLIS.toLong())
            shown = true
        }
    }
    if (visible && expanded) BackHandler { expanded = false }
    if (jumpOpen) {
        if (toplistPage != null) {
            GalleryToplistJumpDialog(toplistPage, onDismiss = { jumpOpen = false }, onApply = {
                jumpOpen = false
                onJumpToPage(it)
            })
        } else {
            GalleryBrowseDateDialog(onDismiss = { jumpOpen = false }, onApply = {
                jumpOpen = false
                onSeekDate(it)
            })
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        if (visible && expanded) {
            Spacer(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { expanded = false } })
        }
        val appearance by animateFloatAsState(if (shown) 1f else 0f, tween(FAB_ANIMATION_MILLIS))
        val expansion by animateFloatAsState(if (visible && expanded) 1f else 0f, tween(FAB_ANIMATION_MILLIS))
        if (expanded || expansion > 0f) {
            val refreshSlot = if (canJump) 3 else 1
            if (canShuffle) {
                GalleryHomeAction(Res.string.gallery_home_shuffle, GalleryIcons.Shuffle, refreshSlot + 1, expansion) { activate(onShuffle) }
            }
            GalleryHomeAction(Res.string.gallery_home_refresh, GalleryIcons.Refresh, refreshSlot, expansion) { activate(onRefresh) }
            if (canJump) {
                GalleryHomeAction(Res.string.gallery_home_go_to, GalleryIcons.GoTo, 2, expansion) { activate { jumpOpen = true } }
                GalleryHomeAction(Res.string.gallery_home_last_page, GalleryIcons.LastPage, 1, expansion) { activate(onLast) }
            }
        }
        if (shown || appearance > 0f) {
            FloatingActionButton(
                onClick = { if (visible && shown) expanded = !expanded },
                modifier = Modifier.padding(16.dp).graphicsLayer {
                    rotationZ = -90f * (1f - appearance)
                    scaleX = appearance
                    scaleY = appearance
                },
            ) {
                Icon(
                    GalleryIcons.Close,
                    galleryString(if (expanded) Res.string.gallery_home_actions_close else Res.string.gallery_home_actions),
                    Modifier.graphicsLayer { rotationZ = -135f * (1f - expansion) },
                )
            }
        }
    }
}

@Composable
private fun GalleryHomeAction(label: StringResource, icon: ImageVector, slot: Int, progress: Float, onClick: () -> Unit) {
    // Match the original 20dp inset and 56dp interval, including its 8dp FAB height correction.
    val settled = progress == 1f
    SmallFloatingActionButton(
        onClick = { if (settled) onClick() },
        modifier = Modifier.padding(20.dp).offset(y = (-(56 * slot + 8) * progress).dp).graphicsLayer { alpha = progress },
    ) { Icon(icon, if (settled) galleryString(label) else null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryToplistJumpDialog(current: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val page = input.trim().toIntOrNull()
    val error = when {
        !attempted -> null
        page == null -> Res.string.gallery_home_invalid_number
        page !in 1..200 -> Res.string.gallery_home_out_of_range
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(galleryString(Res.string.gallery_home_go_to)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it.take(32)
                    attempted = false
                },
                label = { Text(galleryString(Res.string.gallery_home_go_to_hint, current, 200)) },
                isError = error != null,
                trailingIcon = error?.let { { Icon(GalleryIcons.Info, contentDescription = null) } },
                supportingText = error?.let { resource -> { Text(galleryString(resource)) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    attempted = true
                    if (page != null && page in 1..200) onApply(page)
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(galleryString(Res.string.gallery_home_ok)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryBrowseDateDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val start = LocalDate(2007, 3, 21)
    val millisPerDay = 86_400_000L
    val latest = Clock.System.now().toLocalDateTime(TimeZone.UTC).date.toEpochDays() - 1
    val range = (start.toEpochDays() * millisPerDay)..(latest * millisPerDay)
    val end = Instant.fromEpochMilliseconds(range.last).toLocalDateTime(TimeZone.UTC).date
    val dates = remember(range) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in range
        }
    }
    val state = rememberDatePickerState(yearRange = start.year..end.year, selectableDates = dates)
    val selected = state.selectedDateMillis?.takeIf { it in range }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (selected == null) onDismiss() else onApply(Instant.fromEpochMilliseconds(selected).toLocalDateTime(TimeZone.UTC).date.toString())
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(galleryString(Res.string.gallery_home_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(galleryString(Res.string.gallery_cancel)) } },
    ) {
        DatePicker(state, title = { Text(galleryString(Res.string.gallery_home_go_to), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) })
    }
}
