package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.reader.ReaderBackground
import com.ehviewer.core.domain.reader.ReaderPreferences
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_go_to_page
import ehviewer.app.shared.generated.resources.gallery_reader_automatic_background
import ehviewer.app.shared.generated.resources.gallery_reader_black_background
import ehviewer.app.shared.generated.resources.gallery_reader_gray_background
import ehviewer.app.shared.generated.resources.gallery_reader_pref_category_general
import ehviewer.app.shared.generated.resources.gallery_reader_pref_double_tap_to_zoom
import ehviewer.app.shared.generated.resources.gallery_reader_pref_reader_theme
import ehviewer.app.shared.generated.resources.gallery_reader_pref_show_page_number
import ehviewer.app.shared.generated.resources.gallery_reader_pref_show_reader_seekbar
import ehviewer.app.shared.generated.resources.gallery_reader_white_background
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_save_failed
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_unavailable
import ehviewer.app.shared.generated.resources.gallery_retry

/** Original ReaderGeneralSetting/Widget rows, shared by the real reader and application settings. */
@Composable
internal fun GalleryReaderGeneralSettings(preferences: ReaderPreferences, enabled: Boolean, onChange: (ReaderPreferences) -> Unit) {
    ProvideTextStyle(MaterialTheme.typography.labelLarge) {
        Column {
            var expanded by remember { mutableStateOf(false) }
            val names = ReaderBackground.entries.associateWith { value ->
                galleryString(
                    when (value) {
                        ReaderBackground.BLACK -> Res.string.gallery_reader_black_background
                        ReaderBackground.GRAY -> Res.string.gallery_reader_gray_background
                        ReaderBackground.WHITE -> Res.string.gallery_reader_white_background
                        ReaderBackground.AUTOMATIC -> Res.string.gallery_reader_automatic_background
                    },
                )
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { expanded = true }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(galleryString(Res.string.gallery_reader_pref_reader_theme), Modifier.weight(0.5f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(names.getValue(preferences.background), Modifier.weight(1f))
                        Icon(GalleryIcons.KeyboardArrowDown, null, Modifier.size(24.dp))
                    }
                    DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
                        for ((value, name) in names) {
                            DropdownMenuItem(text = { Text(name) }, modifier = Modifier.widthIn(min = 192.dp), enabled = enabled, leadingIcon = { if (value == preferences.background) Icon(GalleryIcons.Check, null) }, onClick = {
                                expanded = false
                                onChange(preferences.copy(background = value))
                            })
                        }
                    }
                }
            }
            ReaderSwitch(galleryString(Res.string.gallery_reader_pref_show_page_number), preferences.showPageNumber, enabled) { onChange(preferences.copy(showPageNumber = it)) }
            ReaderSwitch(galleryString(Res.string.gallery_reader_pref_show_reader_seekbar), preferences.showSeekBar, enabled) { onChange(preferences.copy(showSeekBar = it)) }
            ReaderSwitch(galleryString(Res.string.gallery_reader_pref_double_tap_to_zoom), preferences.doubleTapToZoom, enabled) { onChange(preferences.copy(doubleTapToZoom = it)) }
        }
    }
}

@Composable
private fun ReaderSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryReaderOptionsSheet(preferences: ReaderPreferences, saving: Boolean, failed: Boolean, onChange: (ReaderPreferences) -> Unit, retry: () -> Unit, onJump: () -> Unit, onDismiss: () -> Unit, available: Boolean = true) {
    // Reader settings have one dismiss step on Back/Escape on every host. A partial
    // sheet detent must not consume the first Back while leaving the reader obscured.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, dragHandle = null) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text(galleryString(Res.string.gallery_reader_pref_category_general), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) {
                Text(galleryString(if (available) Res.string.gallery_reading_preferences_save_failed else Res.string.gallery_reading_preferences_unavailable), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = retry, enabled = !saving) { Text(galleryString(Res.string.gallery_retry)) }
            }
            GalleryReaderGeneralSettings(preferences, !saving && available, onChange)
            TextButton(onClick = onJump, enabled = !saving && available) { Text(galleryString(Res.string.gallery_go_to_page)) }
        }
    }
}

@Composable
internal fun readerBackgroundColor(value: ReaderBackground): Color = when (value) {
    ReaderBackground.BLACK -> Color.Black
    ReaderBackground.GRAY -> Color(0xff202125)
    ReaderBackground.WHITE -> Color.White
    ReaderBackground.AUTOMATIC -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xff202125) else Color.White
}
