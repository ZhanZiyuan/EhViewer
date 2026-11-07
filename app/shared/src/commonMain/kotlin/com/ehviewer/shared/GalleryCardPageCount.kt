package com.ehviewer.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.ehviewer.core.domain.GalleryListPresentation
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.BaseGalleryInfo
import kotlinx.coroutines.CancellationException

/** Match v1.15.10: the first page is represented by the total alone. */
@Composable
internal fun galleryCardPageCount(gallery: BaseGalleryInfo, presentation: GalleryListPresentation, reader: ReaderStateStore?): String? {
    val pages = gallery.pages
    if (!presentation.showPages || pages <= 0) return null
    if (!presentation.showReadingProgress || reader == null) return pages.toString()
    val index = produceState<Int?>(null, reader, gallery.gid) {
        value = try {
            reader.position(gallery.gid)?.index
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ReaderStateException) {
            null
        }
    }.value
    return if (index != null && index in 1 until pages) "${index + 1}/$pages" else pages.toString()
}
