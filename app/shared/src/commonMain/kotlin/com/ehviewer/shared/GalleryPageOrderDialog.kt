package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_cancel
import ehviewer.app.shared.generated.resources.gallery_sort_page
import ehviewer.app.shared.generated.resources.gallery_sort_pages
import ehviewer.app.shared.generated.resources.gallery_sort_rating
import ehviewer.app.shared.generated.resources.gallery_sort_service
import ehviewer.app.shared.generated.resources.gallery_sort_title

@Composable
internal fun GalleryPageOrder.label(): String = galleryString(
    when (this) {
        GalleryPageOrder.SERVICE -> Res.string.gallery_sort_service
        GalleryPageOrder.TITLE -> Res.string.gallery_sort_title
        GalleryPageOrder.RATING -> Res.string.gallery_sort_rating
        GalleryPageOrder.PAGES -> Res.string.gallery_sort_pages
    },
)

@Composable
internal fun GalleryPageOrderDialog(current: GalleryPageOrder, onApply: (GalleryPageOrder) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(galleryString(Res.string.gallery_sort_page)) },
        text = { Column { for (order in GalleryPageOrder.entries) SettingsChoice(order.label(), current == order, true) { onApply(order) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(galleryString(Res.string.gallery_cancel)) } },
    )
}
