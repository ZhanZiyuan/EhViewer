package com.ehviewer.shared.reader

import com.ehviewer.core.domain.reader.ReadingPosition
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.gallery.GalleryContentRepository
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException

/** Resolve existing Android index-only progress through actual gallery previews, without a fake token. */
internal suspend fun readingResumeUrl(content: GalleryContentRepository, detail: GalleryDetail, position: ReadingPosition): String {
    fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    if (position.gid != detail.gid || position.index !in 0 until detail.pages) invalid()
    var token = position.pageToken ?: detail.previewList.firstOrNull { it.position == position.index }?.pToken
    if (token == null) {
        val positions = detail.previewList.map { it.position }.sorted()
        if (positions.isEmpty() || positions != (0 until positions.size).toList()) invalid()
        val window = content.details(detail.gid, detail.token, position.index / positions.size)
        token = window.previewList.firstOrNull { it.position == position.index }?.pToken ?: invalid()
    }
    return content.pageUrl(detail.gid, token, position.index).toString()
}
