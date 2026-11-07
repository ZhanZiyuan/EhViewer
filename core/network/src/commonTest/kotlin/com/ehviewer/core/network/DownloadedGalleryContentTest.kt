package com.ehviewer.core.network

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.network.download.CachedGalleryPages
import com.ehviewer.core.network.download.DownloadedGalleryContent
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class DownloadedGalleryContentTest {
    private fun content(read: suspend (Int) -> ByteArray = { byteArrayOf(it.toByte(), 42) }) = DownloadedGalleryContent(object : CachedGalleryPages {
        override val gallery = GalleryDownload(1, "0123456789", "Owned 日本語", 600, false, GalleryDownloadState.COMPLETE, 600)
        override suspend fun read(index: Int) = read(index)
    })

    @Test fun offlinePagesAndPreviewWindowsUseOnlyTheActualCachedReader() = runTest {
        val read = mutableListOf<Int>()
        val source = content {
            read += it
            byteArrayOf(1, 2, 3)
        }
        val window = source.details(1, "0123456789", 1)
        assertEquals(511, window.previewList.first().position)
        assertEquals(599, window.previewList.last().position)
        val preview = window.previewList.last()
        val url = source.pageUrl(1, preview.pToken, 599).toString()
        val page = source.page(url, 1)
        assertEquals(null, page.nextPageUrl)
        assertEquals("ehviewer-cache://gallery/1/598", page.previousPageUrl)
        assertContentEquals(byteArrayOf(1, 2, 3), source.image(page, url, 1))
        assertContentEquals(byteArrayOf(1, 2, 3), source.preview(preview, 1, "0123456789"))
        assertEquals(listOf(599, 599), read)
    }

    @Test fun foreignPathsTraversalQueriesAndNetworkLinksCannotReadCachedFiles() = runTest {
        val source = content { error("Invalid locations must never reach file access") }
        for (url in listOf("https://e-hentai.org/s/0123456789/1-1", "ehviewer-cache://gallery/2/0", "ehviewer-cache://other/1/0", "ehviewer-cache://gallery/1/600", "ehviewer-cache://gallery/1/0?file=private", "ehviewer-cache://gallery/1/../private", "ehviewer-cache://user@gallery/1/0")) {
            assertFailsWith<GalleryRequestException> { source.page(url, 1) }
        }
    }

    @Test fun missingOrCorruptImagesReturnARedactedStorageFailure() = runTest {
        val source = content { error("test-only-private-path") }
        val url = source.localUrl(0).toString()
        assertEquals(GalleryFailure.LOCAL_STORAGE, assertFailsWith<GalleryRequestException> { source.image(source.page(url, 1), url, 1) }.failure)
    }

    @Test fun cancellationOfARealCacheReadIsNotTurnedIntoAnOfflineError() = runTest {
        val source = content { throw CancellationException("Owned cancellation") }
        val url = source.localUrl(0).toString()
        assertFailsWith<CancellationException> { source.image(source.page(url, 1), url, 1) }
    }
}
