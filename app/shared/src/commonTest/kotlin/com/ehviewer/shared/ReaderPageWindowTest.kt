package com.ehviewer.shared

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.shared.reader.ReaderPage
import com.ehviewer.shared.reader.ReaderPageState
import com.ehviewer.shared.reader.ReaderPageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ReaderPageWindowTest {
    @Test fun onlyTheVisibleAndNextPageLoadAndOldPagesAreReleased() = runTest {
        val calls = mutableListOf<Int>()
        val window = ReaderPageWindow(10, this) { index ->
            calls += index
            ReaderPage("page-$index", index, 1)
        }
        window.focus(0)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 1), calls)
        window.focus(5)
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(5, 6), window.pages.value.keys)
        assertEquals(listOf(0, 1, 5, 6), calls)
        window.close()
    }

    @Test fun cancelledResultsCannotReplaceANewerRequestForTheSamePage() = runTest {
        val calls = mutableMapOf<Int, Int>()
        val window = ReaderPageWindow(5, this) { index ->
            val version = (calls[index] ?: 0) + 1
            calls[index] = version
            withContext(NonCancellable) { delay(100) }
            ReaderPage("page-$index", "$index:$version", 1)
        }
        window.focus(0)
        testScheduler.runCurrent()
        window.focus(3)
        window.focus(0)
        testScheduler.advanceUntilIdle()
        val page = window.pages.value.getValue(0) as ReaderPageState.Ready<String>
        assertEquals("0:2", page.page.value)
        assertEquals(setOf(0, 1), window.pages.value.keys)
        window.close()
    }

    @Test fun failuresRemainExplicitUntilManualRetryAndNeverExposeBackendText() = runTest {
        var attempts = 0
        val window = ReaderPageWindow(1, this) { index ->
            attempts++
            if (attempts == 1) throw GalleryRequestException(GalleryFailure.OFFLINE)
            if (attempts == 2) error("test-only-private-image-url")
            ReaderPage("page-$index", index, 1)
        }
        window.focus(0)
        testScheduler.advanceUntilIdle()
        assertEquals(ReaderPageState.Failed(GalleryFailure.OFFLINE), window.pages.value[0])
        window.focus(0)
        testScheduler.advanceUntilIdle()
        assertEquals(1, attempts)
        window.retry(0)
        testScheduler.advanceUntilIdle()
        assertEquals(ReaderPageState.Failed(GalleryFailure.INVALID_RESPONSE), window.pages.value[0])
        window.retry(0)
        testScheduler.advanceUntilIdle()
        assertTrue(window.pages.value[0] is ReaderPageState.Ready)
        window.close()
    }

    @Test fun theByteBudgetKeepsTheVisiblePageAndRejectsAnOversizedAsset() = runTest {
        val window = ReaderPageWindow(3, this, budget = 5) { index -> ReaderPage("page-$index", index, if (index == 2) 6 else 3) }
        window.focus(0)
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(0), window.pages.value.keys)
        window.focus(2)
        testScheduler.advanceUntilIdle()
        assertEquals(ReaderPageState.Failed(GalleryFailure.RESPONSE_TOO_LARGE), window.pages.value[2])
        window.close()
    }

    @Test fun closeCancelsPendingWorkAndDropsEveryResidentImage() = runTest {
        var cancelled = 0
        val window = ReaderPageWindow<Int>(4, this) {
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        window.focus(1)
        testScheduler.runCurrent()
        window.close()
        testScheduler.advanceUntilIdle()
        assertEquals(2, cancelled)
        assertTrue(window.pages.value.isEmpty())
        window.focus(0)
        assertTrue(window.pages.value.isEmpty())
    }

    @Test fun theLastPageDoesNotFetchPastTheGalleryOrReviveAnEvictedRetry() = runTest {
        val calls = mutableListOf<Int>()
        val window = ReaderPageWindow(3, this) { index ->
            calls += index
            ReaderPage("page-$index", index, 1)
        }
        assertFailsWith<IllegalArgumentException> { window.focus(3) }
        window.focus(2)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2), calls)
        window.focus(0)
        testScheduler.advanceUntilIdle()
        window.retry(2)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2, 0, 1), calls)
        window.close()
    }
}
