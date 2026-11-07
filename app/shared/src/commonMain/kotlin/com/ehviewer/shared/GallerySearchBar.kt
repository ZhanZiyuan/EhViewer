package com.ehviewer.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

private val SearchBarHorizontalPadding = 16.dp
private val M3SearchBarMaxWidth = 720.dp

/**
 * SearchBarScreen's collapsed bar: a 56dp Material 3 pill, at most 720dp wide with 16dp side margins.
 * The placeholder shows the page title until focused, then the search hint, as Android does; the hint
 * also labels the field for accessibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    maxWidth: Dp,
    title: String,
    hint: String,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
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
                    .semantics { contentDescription = hint },
                placeholder = { Text(if (focused) hint else title, overflow = TextOverflow.Ellipsis, maxLines = 1) },
                leadingIcon = leading,
                trailingIcon = {
                    Row {
                        if (focused && query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) { Icon(GalleryIcons.Close, contentDescription = galleryString(Res.string.gallery_clear_search)) }
                        }
                        trailing()
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
