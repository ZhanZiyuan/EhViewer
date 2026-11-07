package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_go_to_page
import ehviewer.app.shared.generated.resources.gallery_jump_page_invalid
import ehviewer.app.shared.generated.resources.gallery_jump_page_label
import ehviewer.app.shared.generated.resources.gallery_open_page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The same ASCII page syntax on every platform; no overflow, clamping or invented page token. */
internal fun readerPageIndex(text: String, count: Int): Int? {
    val value = text.trim()
    if (value.isEmpty() || value.length > 10 || value.any { it !in '0'..'9' }) return null
    return value.toIntOrNull()?.takeIf { it in 1..count }?.minus(1)
}

/** Cancel restores the old offset when it still fits after keyboard/rotation/image-size changes. */
internal fun readerRestoreOffset(requested: Int, itemSize: Int?, viewportHeight: Int, lastPage: Boolean): Int {
    val available = itemSize?.coerceAtLeast(0) ?: 0
    val reserved = if (lastPage) viewportHeight.coerceAtLeast(0) else 1
    val maximum = (available.toLong() - reserved).coerceAtLeast(0).toInt()
    return requested.coerceIn(0, maximum)
}

@Composable
internal fun GalleryReaderPageDialog(current: Int, count: Int, onJump: suspend (Int) -> Unit, onDismiss: () -> Unit) {
    val initial = remember { (current + 1).toString() }
    var input by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var invalid by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun submit() {
        if (busy) return
        val target = readerPageIndex(input.text, count)
        if (target == null) {
            invalid = true
            return
        }
        busy = true
        invalid = false
        failure = null
        scope.launch {
            try {
                onJump(target)
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GalleryRequestException) {
                failure = error.failure
            } catch (_: Exception) {
                failure = GalleryFailure.INVALID_RESPONSE
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(galleryString(Res.string.gallery_go_to_page)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                        failure = null
                    },
                    modifier = Modifier.focusRequester(focus),
                    label = { Text(galleryString(Res.string.gallery_jump_page_label, count)) },
                    singleLine = true,
                    isError = invalid,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
                if (invalid) Text(galleryString(Res.string.gallery_jump_page_invalid, count), color = MaterialTheme.colorScheme.error)
                failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator()
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !busy) { Text(galleryString(Res.string.gallery_open_page)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(galleryString(Res.string.gallery_cancel)) } },
    )
}
