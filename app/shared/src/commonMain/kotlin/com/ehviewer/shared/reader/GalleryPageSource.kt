package com.ehviewer.shared.reader

import androidx.compose.ui.graphics.ImageBitmap
import com.ehviewer.core.domain.reader.ReadingPosition
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.shared.decodeGalleryImage

/** Real network/decoder factory; lightweight navigation keys are bounded separately from image pixels. */
internal class GalleryPageSource(private val session: GalleryLibrary, private val detail: GalleryDetail, initial: String) {
    private val known = linkedMapOf<Int, String>()
    private val reload = mutableMapOf<Int, String?>()
    private val retryKeys = mutableMapOf<Int, String?>()

    init {
        detail.previewList.take(511).forEach { known[it.position] = session.content.pageUrl(detail.gid, it.pToken, it.position).toString() }
        known[session.content.pageIndex(initial, detail.gid)] = initial
    }

    suspend fun load(index: Int): ReaderPage<ImageBitmap> {
        val url = known[index] ?: readingResumeUrl(session.content, detail, ReadingPosition(detail.gid, index, null, 0)).also { known[index] = it }
        val page = session.content.page(url, detail.gid, reload.remove(index))
        retryKeys[index] = page.skipHathKey
        for (link in listOfNotNull(page.nextPageUrl, page.previousPageUrl)) known[session.content.pageIndex(link, detail.gid)] = link
        while (known.size > 512) {
            val key = known.keys.first { it != index }
            known.remove(key)
            retryKeys.remove(key)
            reload.remove(key)
        }
        val bitmap = decodeGalleryImage(session.content.image(page, url, detail.gid))
        return ReaderPage(url, bitmap, bitmap.width.toLong() * bitmap.height * 4)
    }

    fun retry(index: Int) {
        reload[index] = retryKeys[index]
    }
}
