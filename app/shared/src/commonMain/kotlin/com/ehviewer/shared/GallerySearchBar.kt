package com.ehviewer.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_clear_search
import ehviewer.app.shared.generated.resources.gallery_filters
import ehviewer.app.shared.generated.resources.gallery_filters_applied
import ehviewer.app.shared.generated.resources.gallery_search
import ehviewer.app.shared.generated.resources.gallery_search_galleries

private val SearchBarHorizontalPadding = 16.dp
private val M3SearchBarMaxWidth = 720.dp

/**
 * SearchBarScreen's collapsed bar: a 56dp Material 3 pill, at most 720dp wide with 16dp side margins.
 * The placeholder shows the app title until focused, then the search hint, as Android does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    maxWidth: Dp,
    filtered: Boolean,
    onFilters: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val label = galleryString(Res.string.gallery_search_galleries)
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onSearch() },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.widthIn(max = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth)).fillMaxWidth()
                    .onFocusChanged { focused = it.hasFocus }
                    .semantics { contentDescription = label },
                placeholder = { Text(if (focused) label else "EhViewer", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                leadingIcon = leading,
                trailingIcon = {
                    Row {
                        if (focused && query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) { Icon(GalleryIcons.Close, contentDescription = galleryString(Res.string.gallery_clear_search)) }
                        }
                        IconButton(onClick = onSearch) { Icon(GalleryIcons.Search, contentDescription = galleryString(Res.string.gallery_search)) }
                        IconButton(onClick = onFilters) {
                            val description = galleryString(if (filtered) Res.string.gallery_filters_applied else Res.string.gallery_filters)
                            Icon(GalleryIcons.FilterList, contentDescription = description, tint = if (filtered) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                    }
                },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier,
        windowInsets = WindowInsets(0),
    ) {}
}
