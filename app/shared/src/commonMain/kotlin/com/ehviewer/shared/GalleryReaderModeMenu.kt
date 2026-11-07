package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.reader.ReaderMode
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.model.GalleryDetail
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_reader_mode
import ehviewer.app.shared.generated.resources.gallery_reader_mode_continuous_vertical
import ehviewer.app.shared.generated.resources.gallery_reader_mode_default
import ehviewer.app.shared.generated.resources.gallery_reader_mode_ltr
import ehviewer.app.shared.generated.resources.gallery_reader_mode_rtl
import ehviewer.app.shared.generated.resources.gallery_reader_mode_vertical
import ehviewer.app.shared.generated.resources.gallery_reader_mode_webtoon

internal fun GalleryDetail.hasReaderWebtoonTag(): Boolean = simpleTags?.any { it.endsWith("webtoon") } == true || tagGroups.any { group -> group.tags.any { it.text == "webtoon" } }

@Composable
internal fun galleryReaderModeName(mode: ReaderMode): String = galleryString(
    when (mode) {
        ReaderMode.DEFAULT -> Res.string.gallery_reader_mode_default
        ReaderMode.LEFT_TO_RIGHT -> Res.string.gallery_reader_mode_ltr
        ReaderMode.RIGHT_TO_LEFT -> Res.string.gallery_reader_mode_rtl
        ReaderMode.VERTICAL -> Res.string.gallery_reader_mode_vertical
        ReaderMode.WEBTOON -> Res.string.gallery_reader_mode_webtoon
        ReaderMode.CONTINUOUS_VERTICAL -> Res.string.gallery_reader_mode_continuous_vertical
    },
)

private fun readerModeIcon(mode: ReaderMode): ImageVector = when (mode) {
    ReaderMode.DEFAULT -> GalleryReaderModeIcons.Default
    ReaderMode.LEFT_TO_RIGHT -> GalleryReaderModeIcons.LeftToRight
    ReaderMode.RIGHT_TO_LEFT -> GalleryReaderModeIcons.RightToLeft
    ReaderMode.VERTICAL -> GalleryReaderModeIcons.Vertical
    ReaderMode.WEBTOON -> GalleryReaderModeIcons.Webtoon
    ReaderMode.CONTINUOUS_VERTICAL -> GalleryReaderModeIcons.ContinuousVertical
}

/** Original BottomReaderBar's single mode icon/menu, shared by every reader source. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun GalleryReaderModeButton(preferences: ReaderPreferences, enabled: Boolean, onSelect: (ReaderMode) -> Unit) {
    val mode = preferences.selectedMode
    val name = galleryReaderModeName(mode)
    GalleryReaderModeDropdown(mode, enabled, onSelect) { anchor ->
        IconButton(
            onClick = {},
            enabled = enabled,
            modifier = anchor.size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide)).semantics { stateDescription = name },
            shapes = IconButtonDefaults.shapes(IconButtonDefaults.mediumRoundShape, IconButtonDefaults.mediumPressedShape),
        ) {
            Icon(readerModeIcon(mode), galleryString(Res.string.gallery_reader_mode), modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
        }
    }
}

@Composable
internal fun GalleryReaderModePreference(preferences: ReaderPreferences, enabled: Boolean, onSelect: (ReaderMode) -> Unit) {
    val mode = preferences.selectedMode
    val name = galleryReaderModeName(mode)
    GalleryReaderModeDropdown(mode, enabled, onSelect) { anchor ->
        Row(anchor.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(galleryString(Res.string.gallery_reader_mode), style = MaterialTheme.typography.titleMedium)
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(GalleryIcons.KeyboardArrowDown, null)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GalleryReaderModeDropdown(mode: ReaderMode, enabled: Boolean, onSelect: (ReaderMode) -> Unit, trigger: @Composable (anchor: Modifier) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        trigger(Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled))
        ExposedDropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 192.dp), matchAnchorWidth = false) {
            for (item in ReaderMode.entries) {
                DropdownMenuItem(
                    text = { Text(galleryReaderModeName(item)) },
                    leadingIcon = { if (item == mode) Icon(GalleryIcons.Check, null) },
                    modifier = Modifier.semantics { selected = item == mode },
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        if (item != mode) onSelect(item)
                    },
                )
            }
        }
    }
}
