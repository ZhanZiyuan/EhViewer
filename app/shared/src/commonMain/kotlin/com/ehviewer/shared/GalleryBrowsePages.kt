package com.ehviewer.shared

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.gallery.GallerySearchResult

/** Retain real loaded galleries while the original cursor protocol extends either end of the list. */
internal fun mergeGalleryBrowsePages(current: GallerySearchResult, incoming: GallerySearchResult, cursor: String, previous: Boolean, consumed: Set<Pair<String, Boolean>>): GallerySearchResult {
    val expected = if (previous) current.prev else current.next
    val continuation = if (previous) incoming.prev else incoming.next
    if (cursor != expected || (cursor to previous) in consumed || continuation == cursor || continuation != null && (continuation to previous) in consumed) {
        throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    }
    incoming.queryError?.let { throw GalleryRequestException(it.failure) }
    val seen = HashSet<Long>()
    val galleries = (if (previous) incoming.galleryInfoList + current.galleryInfoList else current.galleryInfoList + incoming.galleryInfoList).filter { seen.add(it.gid) }
    return GallerySearchResult(
        prev = if (previous) incoming.prev else current.prev,
        next = if (previous) current.next else incoming.next,
        galleryInfoList = galleries,
    )
}
