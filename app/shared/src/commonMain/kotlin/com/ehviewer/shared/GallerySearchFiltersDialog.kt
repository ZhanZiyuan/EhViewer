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
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_category_artist_cg
import ehviewer.app.shared.generated.resources.gallery_category_asian
import ehviewer.app.shared.generated.resources.gallery_category_cosplay
import ehviewer.app.shared.generated.resources.gallery_category_doujinshi
import ehviewer.app.shared.generated.resources.gallery_category_game_cg
import ehviewer.app.shared.generated.resources.gallery_category_images
import ehviewer.app.shared.generated.resources.gallery_category_manga
import ehviewer.app.shared.generated.resources.gallery_category_misc
import ehviewer.app.shared.generated.resources.gallery_category_non_h
import ehviewer.app.shared.generated.resources.gallery_category_western
import ehviewer.app.shared.generated.resources.gallery_filters_apply
import ehviewer.app.shared.generated.resources.gallery_filters_category_required
import ehviewer.app.shared.generated.resources.gallery_filters_disable_language
import ehviewer.app.shared.generated.resources.gallery_filters_disable_tag
import ehviewer.app.shared.generated.resources.gallery_filters_disable_uploader
import ehviewer.app.shared.generated.resources.gallery_filters_expunged
import ehviewer.app.shared.generated.resources.gallery_filters_invalid
import ehviewer.app.shared.generated.resources.gallery_filters_max_pages
import ehviewer.app.shared.generated.resources.gallery_filters_min_pages
import ehviewer.app.shared.generated.resources.gallery_filters_min_rating
import ehviewer.app.shared.generated.resources.gallery_filters_reset
import ehviewer.app.shared.generated.resources.gallery_filters_site_categories
import ehviewer.app.shared.generated.resources.gallery_filters_title
import ehviewer.app.shared.generated.resources.gallery_filters_torrents
import org.jetbrains.compose.resources.StringResource

/** One common form; editing/cancelling never performs a request or mutates applied filters. */
@Composable
internal fun GallerySearchFiltersDialog(initial: GallerySearchFilters, onApply: (GallerySearchFilters) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var rating by remember(initial) { mutableStateOf(initial.minimumRating?.toString().orEmpty()) }
    var minimum by remember(initial) { mutableStateOf(initial.minimumPages?.toString().orEmpty()) }
    var maximum by remember(initial) { mutableStateOf(initial.maximumPages?.toString().orEmpty()) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    fun apply() {
        fun number(text: String): Int? = if (text.isBlank()) null else text.toIntOrNull() ?: throw IllegalArgumentException()
        val filters = try {
            draft.copy(minimumRating = number(rating), minimumPages = number(minimum), maximumPages = number(maximum))
        } catch (_: IllegalArgumentException) {
            error = Res.string.gallery_filters_invalid
            return
        }
        onApply(filters)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(galleryString(Res.string.gallery_filters_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterToggle(galleryString(Res.string.gallery_filters_site_categories), draft.includedCategories == null) { useSite ->
                    draft = draft.copy(includedCategories = if (useSite) null else GallerySearchFilters.ALL_CATEGORIES)
                }
                if (draft.includedCategories != null) {
                    GalleryCategory.entries.forEach { category ->
                        val mask = requireNotNull(draft.includedCategories)
                        FilterToggle(category.label(), mask and category.mask != 0) { checked ->
                            val selected = if (checked) mask or category.mask else mask and category.mask.inv()
                            if (selected == 0) {
                                error = Res.string.gallery_filters_category_required
                            } else {
                                draft = draft.copy(includedCategories = selected)
                                error = null
                            }
                        }
                    }
                }
                OutlinedTextField(rating, { rating = it.take(32) }, Modifier.fillMaxWidth(), label = { Text(galleryString(Res.string.gallery_filters_min_rating)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minimum, { minimum = it.take(32) }, Modifier.weight(1f), label = { Text(galleryString(Res.string.gallery_filters_min_pages)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(maximum, { maximum = it.take(32) }, Modifier.weight(1f), label = { Text(galleryString(Res.string.gallery_filters_max_pages)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                FilterToggle(galleryString(Res.string.gallery_filters_expunged), draft.showExpunged) { draft = draft.copy(showExpunged = it) }
                FilterToggle(galleryString(Res.string.gallery_filters_torrents), draft.onlyWithTorrents) { draft = draft.copy(onlyWithTorrents = it) }
                FilterToggle(galleryString(Res.string.gallery_filters_disable_language), draft.disableLanguageFilter) { draft = draft.copy(disableLanguageFilter = it) }
                FilterToggle(galleryString(Res.string.gallery_filters_disable_uploader), draft.disableUploaderFilter) { draft = draft.copy(disableUploaderFilter = it) }
                FilterToggle(galleryString(Res.string.gallery_filters_disable_tag), draft.disableTagFilter) { draft = draft.copy(disableTagFilter = it) }
                error?.let { Text(galleryString(it), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = {
                    draft = GallerySearchFilters()
                    rating = ""
                    minimum = ""
                    maximum = ""
                    error = null
                }) { Text(galleryString(Res.string.gallery_filters_reset)) }
            }
        },
        confirmButton = { TextButton(onClick = ::apply) { Text(galleryString(Res.string.gallery_filters_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(galleryString(Res.string.gallery_cancel)) } },
    )
}

@Composable
private fun FilterToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun GalleryCategory.label(): String = when (this) {
    GalleryCategory.MISC -> galleryString(Res.string.gallery_category_misc)
    GalleryCategory.DOUJINSHI -> galleryString(Res.string.gallery_category_doujinshi)
    GalleryCategory.MANGA -> galleryString(Res.string.gallery_category_manga)
    GalleryCategory.ARTIST_CG -> galleryString(Res.string.gallery_category_artist_cg)
    GalleryCategory.GAME_CG -> galleryString(Res.string.gallery_category_game_cg)
    GalleryCategory.IMAGE_SET -> galleryString(Res.string.gallery_category_images)
    GalleryCategory.COSPLAY -> galleryString(Res.string.gallery_category_cosplay)
    GalleryCategory.ASIAN_PORN -> galleryString(Res.string.gallery_category_asian)
    GalleryCategory.NON_H -> galleryString(Res.string.gallery_category_non_h)
    GalleryCategory.WESTERN -> galleryString(Res.string.gallery_category_western)
}
