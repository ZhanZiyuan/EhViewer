package com.ehviewer.core.network.archive

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveDocument
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Local document adapter only: identity/progress are separately scoped from all service galleries. */
class ArchiveGalleryContent(val book: ArchiveBook, private val archive: ArchiveDocument) : GalleryContent {
    init {
        book.validate()
        require(archive.pageCount == book.pages)
    }

    val token = book.id.take(10)
    private fun key(index: Int) = index.toString(16).padStart(10, '0')

    fun localUrl(index: Int): Url {
        require(index in 0 until book.pages)
        return Url("ehviewer-archive://book/${book.id}/$index")
    }

    override fun pageUrl(gid: Long, pageToken: String, index: Int): Url {
        require(gid == LOCAL_DOCUMENT && pageToken == key(index))
        return localUrl(index)
    }

    override fun pageIndex(url: String, gid: Long): Int {
        if (gid != LOCAL_DOCUMENT) invalid()
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            invalid()
        }
        val index = parsed.encodedPath.substringAfterLast('/').toIntOrNull() ?: invalid()
        if (index !in 0 until book.pages || parsed != localUrl(index)) invalid()
        return index
    }

    override suspend fun details(gid: Long, token: String, previewPage: Int): GalleryDetail {
        require(gid == LOCAL_DOCUMENT && token == this.token && previewPage >= 0)
        val start = previewPage.toLong() * WINDOW
        require(start < book.pages)
        return GalleryDetail(
            galleryInfo = BaseGalleryInfo(gid = LOCAL_DOCUMENT, token = token, title = book.title, pages = book.pages),
            tagGroups = emptyList(),
            comments = GalleryCommentList(emptyList(), false),
            previewList = (start.toInt() until minOf(start + WINDOW, book.pages.toLong()).toInt()).map { V1GalleryPreview(localUrl(it).toString(), it, key(it)) },
        )
    }

    override suspend fun page(url: String, gid: Long, reloadKey: String?): GalleryImagePage {
        require(reloadKey == null)
        val index = pageIndex(url, gid)
        return GalleryImagePage(localUrl(index).toString(), null, null, null, (index + 1).takeIf { it < book.pages }?.let { localUrl(it).toString() }, (index - 1).takeIf { it >= 0 }?.let { localUrl(it).toString() })
    }

    private suspend fun read(index: Int): ByteArray = try {
        withContext(Dispatchers.IO) {
            archive.read(index).also { check(it.size.toLong() in 1..32L * 1024 * 1024) }
        }
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
        require(gid == LOCAL_DOCUMENT && token == this.token && preview.pToken == key(preview.position))
        if (preview.url != localUrl(preview.position).toString()) invalid()
        return read(preview.position)
    }

    private fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)

    companion object {
        // This number is used only inside a book-specific reader-state store, never EhDB history.
        const val LOCAL_DOCUMENT = 1L
        private const val WINDOW = 511
    }
}
