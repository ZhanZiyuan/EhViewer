package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ehviewer.core.network.gallery.GalleryCategory
import com.ehviewer.core.network.gallery.GallerySearchFilters

/** One common form; editing/cancelling never performs a request or mutates applied filters. */
@Composable
internal fun GallerySearchFiltersDialog(initial: GallerySearchFilters, onApply: (GallerySearchFilters) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var rating by remember(initial) { mutableStateOf(initial.minimumRating?.toString().orEmpty()) }
    var minimum by remember(initial) { mutableStateOf(initial.minimumPages?.toString().orEmpty()) }
    var maximum by remember(initial) { mutableStateOf(initial.maximumPages?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun apply() {
        fun number(text: String): Int? = if (text.isBlank()) null else text.toIntOrNull() ?: throw IllegalArgumentException()
        val filters = try {
            draft.copy(minimumRating = number(rating), minimumPages = number(minimum), maximumPages = number(maximum))
        } catch (_: IllegalArgumentException) {
            error = "Choose at least one category, a rating from 2 to 5, and positive page bounds in ascending order. Leave optional numbers empty to ignore them."
            return
        }
        onApply(filters)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search filters") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterToggle("Use site category preferences", draft.includedCategories == null) { useSite ->
                    draft = draft.copy(includedCategories = if (useSite) null else GallerySearchFilters.ALL_CATEGORIES)
                }
                if (draft.includedCategories != null) {
                    GalleryCategory.entries.forEach { category ->
                        val mask = requireNotNull(draft.includedCategories)
                        FilterToggle(category.label(), mask and category.mask != 0) { checked ->
                            val selected = if (checked) mask or category.mask else mask and category.mask.inv()
                            if (selected == 0) {
                                error = "Select at least one category."
                            } else {
                                draft = draft.copy(includedCategories = selected)
                                error = null
                            }
                        }
                    }
                }
                OutlinedTextField(rating, { rating = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("Minimum rating (2–5)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minimum, { minimum = it.take(32) }, Modifier.weight(1f), label = { Text("Minimum pages") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(maximum, { maximum = it.take(32) }, Modifier.weight(1f), label = { Text("Maximum pages") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                FilterToggle("Show expunged galleries", draft.showExpunged) { draft = draft.copy(showExpunged = it) }
                FilterToggle("Only galleries with torrents", draft.onlyWithTorrents) { draft = draft.copy(onlyWithTorrents = it) }
                FilterToggle("Disable saved language filter", draft.disableLanguageFilter) { draft = draft.copy(disableLanguageFilter = it) }
                FilterToggle("Disable saved uploader filter", draft.disableUploaderFilter) { draft = draft.copy(disableUploaderFilter = it) }
                FilterToggle("Disable saved tag filter", draft.disableTagFilter) { draft = draft.copy(disableTagFilter = it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = {
                    draft = GallerySearchFilters()
                    rating = ""
                    minimum = ""
                    maximum = ""
                    error = null
                }) { Text("Reset filters") }
            }
        },
        confirmButton = { TextButton(onClick = ::apply) { Text("Apply and search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.weight(1f))
    }
}

private fun GalleryCategory.label(): String = when (this) {
    GalleryCategory.MISC -> "Miscellaneous"
    GalleryCategory.DOUJINSHI -> "Doujinshi"
    GalleryCategory.MANGA -> "Manga"
    GalleryCategory.ARTIST_CG -> "Artist CG"
    GalleryCategory.GAME_CG -> "Game CG"
    GalleryCategory.IMAGE_SET -> "Image sets"
    GalleryCategory.COSPLAY -> "Cosplay"
    GalleryCategory.ASIAN_PORN -> "Asian porn"
    GalleryCategory.NON_H -> "Non-H"
    GalleryCategory.WESTERN -> "Western"
}
