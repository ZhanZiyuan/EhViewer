package com.ehviewer.core.network.download

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.gallery.GalleryContent
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException

/** Borrow an existing platform client while keeping account-generation ownership explicit. */
fun networkGalleryDownloadSource(task: GalleryDownload, content: GalleryContent, requireSession: suspend () -> Unit): GalleryDownloadSource = NetworkGalleryDownloadSource(task, content, requireSession)

/** Actual documents and image transport, with fixed site and account generation for one active task. */
internal class NetworkGalleryDownloadSource(
    private val task: GalleryDownload,
    private val content: GalleryContent,
    private val requireSession: suspend () -> Unit,
) : GalleryDownloadSource {
    private var detail: GalleryDetail? = null
    private val known = linkedMapOf<Int, String>()

    override suspend fun load(index: Int): DownloadPageBytes {
        require(index in 0 until task.pages)
        requireSession()
        val first = detail ?: pinned { content.details(task.gid, task.token) }.also {
            if (it.pages != task.pages) invalid()
            detail = it
            it.previewList.forEach { preview -> known[preview.position] = content.pageUrl(task.gid, preview.pToken, preview.position).toString() }
        }
        val url = known[index] ?: run {
            val positions = first.previewList.map { it.position }
            if (positions.isEmpty() || positions.sorted() != positions.indices.toList()) invalid()
            val page = pinned { content.details(task.gid, task.token, index / positions.size) }
            if (page.pages != task.pages) invalid()
            page.previewList.firstOrNull { it.position == index }?.let { content.pageUrl(task.gid, it.pToken, index).toString() } ?: invalid()
        }
        val page = pinned { content.page(url, task.gid) }
        rememberLinks(page)
        val bytes = try {
            pinned { content.image(page, url, task.gid) }
        } catch (failure: GalleryRequestException) {
            val key = page.skipHathKey
            if (key == null || failure.failure !in setOf(GalleryFailure.OFFLINE, GalleryFailure.SERVER)) throw failure
            // The same official replacement used by the reader: one retry, with all normal checks.
            val replacement = pinned { content.page(url, task.gid, key) }
            rememberLinks(replacement)
            pinned { content.image(replacement, url, task.gid) }
        }
        requireSession()
        return DownloadPageBytes(url.substringAfter("/s/").substringBefore('/'), bytes)
    }

    private fun rememberLinks(page: GalleryImagePage) {
        for (link in listOfNotNull(page.nextPageUrl, page.previousPageUrl)) known[content.pageIndex(link, task.gid)] = link
        while (known.size > 512) known.remove(known.keys.first())
    }

    private suspend fun <T> pinned(request: suspend () -> T): T {
        requireSession()
        val result = request()
        requireSession()
        return result
    }

    private fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
}
