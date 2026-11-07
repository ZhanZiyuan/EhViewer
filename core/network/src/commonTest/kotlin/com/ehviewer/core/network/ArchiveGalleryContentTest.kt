package com.ehviewer.core.network

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.network.archive.ArchiveGalleryContent
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/** Adapter isolation only; packaged regressions separately use real Rust archives and actual pixels. */
class ArchiveGalleryContentTest {
    private class Document(override val pageCount: Int = 3) : ArchiveDocument {
        override val needsPassword = false
        var closed = false
        var failure: Exception? = null
        var bytes = byteArrayOf(1, 2)
        val reads = mutableListOf<Int>()
        override fun extension(index: Int) = "png"
        override fun providePassword(password: String) = true
        override fun read(index: Int): ByteArray {
            if (closed) throw ArchiveException(ArchiveFailure.CLOSED)
            failure?.let { throw it }
            reads += index
            return bytes
        }
        override fun close() {
            closed = true
        }
    }

    private fun book(pages: Int = 3) = ArchiveBook("a".repeat(64), "Local owned comic", 100, pages, false)

    @Test fun realLocalIdentitiesDriveTheExistingPageAndPreviewContractWithoutHttp() = runTest {
        val document = Document()
        val content = ArchiveGalleryContent(book(), document)
        val detail = content.details(1, content.token)
        assertEquals("Local owned comic", detail.title)
        assertEquals(3, detail.pages)
        val url = content.localUrl(1).toString()
        val page = content.page(url, 1)
        assertEquals(content.localUrl(0).toString(), page.previousPageUrl)
        assertEquals(content.localUrl(2).toString(), page.nextPageUrl)
        assertContentEquals(byteArrayOf(1, 2), content.image(page, url, 1))
        assertContentEquals(byteArrayOf(1, 2), content.preview(detail.previewList[2], 1, content.token))
        assertEquals(listOf(1, 2), document.reads)
        assertNull(content.page(content.localUrl(0).toString(), 1).previousPageUrl)
        assertNull(content.page(content.localUrl(2).toString(), 1).nextPageUrl)
    }

    @Test fun foreignIdsSchemesPathsQueriesAndOutOfRangeIndexesNeverReadTheBackingFile() = runTest {
        val document = Document()
        val content = ArchiveGalleryContent(book(), document)
        val valid = content.localUrl(0).toString()
        for (bad in listOf("file:///private/secret", "https://e-hentai.org/s/0000000000/1-1", valid.replace("a".repeat(64), "b".repeat(64)), "$valid?x=1", "$valid#fragment", valid.dropLast(1) + "-1", valid.dropLast(1) + "2147483648")) {
            assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { content.page(bad, 1) }.failure)
        }
        assertFailsWith<GalleryRequestException> { content.page(valid, 2) }
        assertFailsWith<IllegalArgumentException> { content.page(valid, 1, "replacement") }
        assertTrue(document.reads.isEmpty())
    }

    @Test fun tokensAndPreviewWindowsAreBoundToThisExactDocument() = runTest {
        val content = ArchiveGalleryContent(book(), Document())
        assertFailsWith<IllegalArgumentException> { content.details(1, "b".repeat(10)) }
        assertFailsWith<IllegalArgumentException> { content.pageUrl(2, "0".repeat(10), 0) }
        assertFailsWith<IllegalArgumentException> { content.pageUrl(1, "1".repeat(10), 0) }
        assertFailsWith<IllegalArgumentException> { content.details(1, content.token, Int.MAX_VALUE) }
        val preview = content.details(1, content.token).previewList[0]
        assertFailsWith<IllegalArgumentException> { content.preview(preview, 1, "b".repeat(10)) }
    }

    @Test fun closedOrFailedNativeReadersExposeOnlyRecoverableLocalStorageFailure() = runTest {
        val document = Document()
        val content = ArchiveGalleryContent(book(), document)
        val url = content.localUrl(0).toString()
        val page = content.page(url, 1)
        document.failure = IllegalStateException("test-only-private-path")
        val error = assertFailsWith<GalleryRequestException> { content.image(page, url, 1) }
        assertEquals(GalleryFailure.LOCAL_STORAGE, error.failure)
        assertNull(error.cause)
        assertTrue(!error.toString().contains("private-path"))
        document.failure = null
        document.close()
        assertEquals(GalleryFailure.LOCAL_STORAGE, assertFailsWith<GalleryRequestException> { content.image(page, url, 1) }.failure)
    }

    @Test fun cancellationRemainsCancellationAndEmptyImagesAreNeverPublished() = runTest {
        val document = Document()
        val content = ArchiveGalleryContent(book(), document)
        val url = content.localUrl(0).toString()
        val page = content.page(url, 1)
        document.failure = CancellationException("owned cancellation")
        assertFailsWith<CancellationException> { content.image(page, url, 1) }
        document.failure = null
        document.bytes = byteArrayOf()
        assertEquals(GalleryFailure.LOCAL_STORAGE, assertFailsWith<GalleryRequestException> { content.image(page, url, 1) }.failure)
    }

    @Test fun longBooksUseBoundedPreviewWindowsAndRejectMetadataMismatch() = runTest {
        val content = ArchiveGalleryContent(book(1200), Document(1200))
        val first = content.details(1, content.token)
        val last = content.details(1, content.token, 2)
        assertEquals(511, first.previewList.size)
        assertEquals((1022 until 1200).toList(), last.previewList.map { it.position })
        assertEquals(1199, content.pageIndex(last.previewList.last().url, 1))
        assertFailsWith<IllegalArgumentException> { ArchiveGalleryContent(book(2), Document(3)) }
    }
}
