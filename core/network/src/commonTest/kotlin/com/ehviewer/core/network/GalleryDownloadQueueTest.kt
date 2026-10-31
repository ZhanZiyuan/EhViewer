package com.ehviewer.core.network

import com.ehviewer.core.domain.download.DownloadedGalleryPage
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadFailure
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.download.GalleryDownloadStorage
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.download.DownloadPageBytes
import com.ehviewer.core.network.download.GalleryDownloadQueue
import com.ehviewer.core.network.download.GalleryDownloadSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class GalleryDownloadQueueTest {
    private class Storage : GalleryDownloadStorage {
        val saved = linkedMapOf<Long, GalleryDownload>()
        val images = mutableMapOf<Pair<Long, Int>, DownloadPageBytes>()
        var failNextCountWrite = false
        var failStart: Long? = null
        var failRead: Long? = null
        var failPause = false
        override suspend fun list() = saved.values.toList()
        override suspend fun save(download: GalleryDownload) {
            if (failPause && download.state == GalleryDownloadState.PAUSED) throw GalleryDownloadStorageException()
            if (failStart == download.gid && download.state == GalleryDownloadState.DOWNLOADING) throw GalleryDownloadStorageException()
            if (failNextCountWrite && download.finished > (saved[download.gid]?.finished ?: 0)) {
                failNextCountWrite = false
                throw GalleryDownloadStorageException()
            }
            saved[download.gid] = download
        }
        override suspend fun pages(gid: Long): List<DownloadedGalleryPage> {
            if (failRead == gid) throw GalleryDownloadStorageException()
            return images.filterKeys { it.first == gid }.entries.sortedBy { it.key.second }.map { (key, value) -> DownloadedGalleryPage(key.second, value.token, value.bytes.size.toLong()) }
        }
        override suspend fun writePage(gid: Long, index: Int, token: String, bytes: ByteArray) {
            images[gid to index] = DownloadPageBytes(token, bytes.copyOf())
        }
        override suspend fun readPage(gid: Long, index: Int) = images.getValue(gid to index).bytes.copyOf()
        override suspend fun remove(gid: Long) {
            saved.remove(gid)
            images.keys.removeAll { it.first == gid }
        }
    }

    private fun gallery(gid: Long = 1, pages: Int = 3) = GalleryDetail(
        galleryInfo = BaseGalleryInfo(gid = gid, token = "0123456789", title = "Owned 日本語 $gid", pages = pages),
        tagGroups = emptyList(),
        comments = GalleryCommentList(emptyList(), false),
        previewList = emptyList(),
    )
    private fun image(index: Int) = DownloadPageBytes("0123456789", byteArrayOf(index.toByte(), 42))

    @Test fun oneDamagedDownloadRemainsVisibleAndDeletableWithoutBlockingHealthyTasks() = runTest {
        val storage = Storage().apply {
            save(GalleryDownload(1, "0123456789", "Damaged", 3, false, GalleryDownloadState.PAUSED))
            save(GalleryDownload(2, "0123456789", "Healthy", 3, false, position = 1))
            failRead = 1
        }
        val queue = GalleryDownloadQueue(storage, backgroundScope) { GalleryDownloadSource(::image) }
        try {
            queue.initialize()
            assertEquals(GalleryDownloadState.PAUSED, queue.tasks.value.last().state)
            queue.resume(2)
            runCurrent()
            assertEquals(GalleryDownloadFailure.STORAGE, queue.tasks.value.first().failure)
            assertEquals(GalleryDownloadState.COMPLETE, queue.tasks.value.last().state)
            queue.cancel(1)
            assertEquals(listOf(2L), queue.tasks.value.map { it.gid })
        } finally {
            queue.close()
        }
    }

    @Test fun storageFailureDuringPauseStillStopsNetworkAndNeverStartsTheNextTask() = runTest {
        val storage = Storage()
        val opened = mutableListOf<Long>()
        var stopped = false
        val queue = GalleryDownloadQueue(storage, backgroundScope) { task ->
            opened += task.gid
            GalleryDownloadSource {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        try {
            queue.initialize()
            queue.enqueue(gallery(), false)
            queue.enqueue(gallery(2), false)
            runCurrent()
            storage.failPause = true
            assertFailsWith<GalleryDownloadStorageException> { queue.pauseAll() }
            runCurrent()
            assertTrue(stopped)
            assertEquals(listOf(1L), opened)
            assertTrue(queue.tasks.value.all { it.failure == GalleryDownloadFailure.STORAGE })
        } finally {
            queue.close()
        }
    }

    @Test fun queueRunsInSubmissionOrderWithOnlyOneActiveImageAndPersistsEveryPage() = runTest {
        val storage = Storage()
        val started = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val queue = GalleryDownloadQueue(storage, backgroundScope) { task ->
            started += task.gid
            GalleryDownloadSource { index ->
                active++
                peak = maxOf(peak, active)
                try {
                    if (task.gid == 3L && index == 0) gate.await()
                    image(index)
                } finally {
                    active--
                }
            }
        }
        try {
            queue.initialize()
            listOf(3L, 1L, 2L).forEach { queue.enqueue(gallery(it), false) }
            runCurrent()
            assertEquals(listOf(3L), started)
            assertEquals(listOf(GalleryDownloadState.DOWNLOADING, GalleryDownloadState.QUEUED, GalleryDownloadState.QUEUED), queue.tasks.value.map { it.state })
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(3L, 1L, 2L), started)
            assertEquals(1, peak)
            assertEquals(9, storage.images.size)
            assertTrue(queue.tasks.value.all { it.state == GalleryDownloadState.COMPLETE && it.finished == 3 })
        } finally {
            queue.close()
        }
    }

    @Test fun pauseWaitsForCancellationAndResumeReopensTheSource() = runTest {
        val storage = Storage()
        var first = true
        var stopped = false
        val queue = GalleryDownloadQueue(storage, backgroundScope) {
            GalleryDownloadSource { index ->
                if (first) {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
                image(index)
            }
        }
        try {
            queue.initialize()
            queue.enqueue(gallery(), false)
            runCurrent()
            queue.pause(1)
            assertTrue(stopped)
            assertEquals(GalleryDownloadState.PAUSED, queue.tasks.value.single().state)
            assertTrue(storage.images.isEmpty())
            first = false
            queue.resume(1)
            runCurrent()
            assertEquals(GalleryDownloadState.COMPLETE, queue.tasks.value.single().state)
        } finally {
            queue.close()
        }
    }

    @Test fun cancelledTaskCannotPublishALateUncancellableTransportResult() = runTest {
        val storage = Storage()
        val response = CompletableDeferred<Unit>()
        val queue = GalleryDownloadQueue(storage, backgroundScope) {
            GalleryDownloadSource { index ->
                withContext(NonCancellable) {
                    response.await()
                    image(index)
                }
            }
        }
        try {
            queue.initialize()
            queue.enqueue(gallery(), false)
            runCurrent()
            val cancelled = backgroundScope.async { queue.cancel(1) }
            runCurrent()
            assertFalse(cancelled.isCompleted)
            response.complete(Unit)
            runCurrent()
            cancelled.await()
            assertTrue(queue.tasks.value.isEmpty())
            assertTrue(storage.images.isEmpty())
            assertTrue(storage.saved.isEmpty())
        } finally {
            queue.close()
        }
    }

    @Test fun restartReconcilesCommittedPagesAndResumesWithoutFetchingThemAgain() = runTest {
        val storage = Storage()
        storage.save(GalleryDownload(1, "0123456789", "Owned", 3, false, GalleryDownloadState.DOWNLOADING))
        storage.writePage(1, 0, "0123456789", image(0).bytes)
        val fetched = mutableListOf<Int>()
        val queue = GalleryDownloadQueue(storage, backgroundScope) {
            GalleryDownloadSource { index ->
                fetched += index
                image(index)
            }
        }
        try {
            queue.initialize()
            assertEquals(GalleryDownloadState.PAUSED, queue.tasks.value.single().state)
            assertEquals(1, queue.tasks.value.single().finished)
            queue.resume(1)
            runCurrent()
            assertEquals(listOf(1, 2), fetched)
            assertEquals(GalleryDownloadState.COMPLETE, storage.saved.getValue(1).state)
        } finally {
            queue.close()
        }
    }

    @Test fun failureAfterImageCommitIsRecoverableWithoutDownloadingThatImageAgain() = runTest {
        val storage = Storage().apply { failNextCountWrite = true }
        val fetched = mutableListOf<Int>()
        val queue = GalleryDownloadQueue(storage, backgroundScope) {
            GalleryDownloadSource { index ->
                fetched += index
                image(index)
            }
        }
        try {
            queue.initialize()
            queue.enqueue(gallery(), false)
            runCurrent()
            assertEquals(GalleryDownloadFailure.STORAGE, queue.tasks.value.single().failure)
            assertEquals(1, storage.images.size)
            queue.resume(1)
            runCurrent()
            assertEquals(listOf(0, 1, 2), fetched)
            assertEquals(GalleryDownloadState.COMPLETE, queue.tasks.value.single().state)
        } finally {
            queue.close()
        }
    }

    @Test fun accountGenerationCancellationPausesInsteadOfContinuingWithAnotherIdentity() = runTest {
        val storage = Storage()
        val queue = GalleryDownloadQueue(storage, backgroundScope) { GalleryDownloadSource { throw CancellationException("Session changed") } }
        try {
            queue.initialize()
            queue.enqueue(gallery(), true)
            runCurrent()
            assertEquals(GalleryDownloadState.PAUSED, queue.tasks.value.single().state)
            assertTrue(storage.images.isEmpty())
        } finally {
            queue.close()
        }
    }

    @Test fun failureToPersistTaskStartDoesNotLoopOrBlockTheNextQueuedTask() = runTest {
        val storage = Storage().apply { failStart = 1L }
        val opened = mutableListOf<Long>()
        val queue = GalleryDownloadQueue(storage, backgroundScope) { task ->
            opened += task.gid
            GalleryDownloadSource(::image)
        }
        try {
            queue.initialize()
            queue.enqueue(gallery(), false)
            queue.enqueue(gallery(2), false)
            runCurrent()
            assertEquals(listOf(2L), opened)
            assertEquals(GalleryDownloadFailure.STORAGE, queue.tasks.value.first().failure)
            assertEquals(GalleryDownloadState.COMPLETE, queue.tasks.value.last().state)
        } finally {
            queue.close()
        }
    }

    @Test fun emptyOrInvalidImageResultsNeverBecomeCommittedPages() = runTest {
        val storage = Storage()
        val queue = GalleryDownloadQueue(storage, backgroundScope) { GalleryDownloadSource { DownloadPageBytes("not-a-token", byteArrayOf()) } }
        try {
            queue.initialize()
            queue.enqueue(gallery(), false)
            runCurrent()
            assertEquals(GalleryDownloadState.FAILED, queue.tasks.value.single().state)
            assertTrue(storage.images.isEmpty())
        } finally {
            queue.close()
        }
    }
}
