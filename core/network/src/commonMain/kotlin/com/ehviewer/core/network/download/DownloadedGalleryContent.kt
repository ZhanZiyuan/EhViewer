package com.ehviewer.core.network.download

import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.network.gallery.GalleryContent
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException

/** Real cached images behind the same reader API; local page keys are never service page tokens. */
class DownloadedGalleryContent(private val pages: CachedGalleryPages) : GalleryContent {
    private val gallery = pages.gallery.also {
        it.validate()
        require(it.pages > 0 && it.state == GalleryDownloadState.COMPLETE)
    }

    private fun key(index: Int) = index.toString(16).padStart(10, '0')

    fun localUrl(index: Int): Url {
        require(index in 0 until gallery.pages)
        return Url("ehviewer-cache://gallery/${gallery.gid}/$index")
    }

    override fun pageUrl(gid: Long, pageToken: String, index: Int): Url {
        require(gid == gallery.gid && pageToken == key(index))
        return localUrl(index)
    }

    override fun pageIndex(url: String, gid: Long): Int {
        if (gid != gallery.gid) invalid()
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            invalid()
        }
        val index = parsed.encodedPath.substringAfterLast('/').toIntOrNull() ?: invalid()
        if (index !in 0 until gallery.pages || parsed != localUrl(index)) invalid()
        return index
    }

    override suspend fun details(gid: Long, token: String, previewPage: Int): GalleryDetail {
        require(gid == gallery.gid && token == gallery.token && previewPage >= 0)
        val start = previewPage.toLong() * WINDOW
        require(start < gallery.pages)
        return GalleryDetail(
            galleryInfo = BaseGalleryInfo(gid = gid, token = token, title = gallery.title, pages = gallery.pages),
            tagGroups = emptyList(),
            comments = GalleryCommentList(emptyList(), false),
            previewList = (start.toInt() until minOf(start + WINDOW, gallery.pages.toLong()).toInt()).map { V1GalleryPreview(localUrl(it).toString(), it, key(it)) },
        )
    }

    override suspend fun page(url: String, gid: Long, reloadKey: String?): GalleryImagePage {
        val index = pageIndex(url, gid)
        require(reloadKey == null)
        return GalleryImagePage(
            localUrl(index).toString(),
            null,
            null,
            null,
            (index + 1).takeIf { it < gallery.pages }?.let { localUrl(it).toString() },
            (index - 1).takeIf { it >= 0 }?.let { localUrl(it).toString() },
        )
    }

    private suspend fun read(index: Int): ByteArray = try {
        pages.read(index)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryRequestException(GalleryFailure.LOCAL_STORAGE)
    }

    override suspend fun image(page: GalleryImagePage, pageUrl: String, gid: Long): ByteArray {
        val index = pageIndex(pageUrl, gid)
        if (page.imageUrl != localUrl(index).toString()) invalid()
        return read(index)
    }

    override suspend fun preview(preview: GalleryPreview, gid: Long, token: String): ByteArray {
        require(gid == gallery.gid && token == gallery.token && preview.pToken == key(preview.position))
        if (preview.url != localUrl(preview.position).toString()) invalid()
        return read(preview.position)
    }

    private fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)

    companion object {
        private const val WINDOW = 511
    }
}
