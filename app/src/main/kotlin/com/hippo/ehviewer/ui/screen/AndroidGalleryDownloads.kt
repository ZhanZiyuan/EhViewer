package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadFailure
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.download.GalleryDownloadOpen
import com.ehviewer.core.network.download.GalleryDownloads
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.download.DownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Borrow original Android downloads, workers, files, labels and recovery; own only the UI observer. */
internal class AndroidGalleryDownloads(parent: CoroutineScope) : GalleryDownloads {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val mutex = Mutex()
    private var observing = false
    private val mutableTasks = MutableStateFlow<List<GalleryDownload>>(emptyList())
    override val tasks = mutableTasks.asStateFlow()

    private suspend fun refresh() {
        mutableTasks.value = DownloadManager.mapDownloadInfo { info ->
            val state = when (info.state) {
                DownloadInfo.STATE_WAIT -> GalleryDownloadState.QUEUED
                DownloadInfo.STATE_DOWNLOAD -> GalleryDownloadState.DOWNLOADING
                DownloadInfo.STATE_FINISH -> GalleryDownloadState.COMPLETE
                DownloadInfo.STATE_FAILED, DownloadInfo.STATE_INVALID -> GalleryDownloadState.FAILED
                else -> GalleryDownloadState.PAUSED
            }
            val pages = info.pages.coerceAtLeast(0)
            GalleryDownload(
                info.gid,
                info.token,
                info.title ?: info.titleJpn.orEmpty(),
                pages,
                Settings.gallerySite.value == EhUrl.SITE_EX,
                state,
                if (state == GalleryDownloadState.COMPLETE) pages else info.finished.coerceIn(0, pages),
                GalleryDownloadFailure.NETWORK_OR_IMAGE.takeIf { state == GalleryDownloadState.FAILED },
            )
        }
    }

    private suspend fun <T> original(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (_: TimeoutCancellationException) {
        throw GalleryDownloadStorageException()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryDownloadStorageException()
    }

    override suspend fun initialize() = original {
        mutex.withLock {
            withTimeout(10_000) { while (!DownloadManager.isInitialized) delay(25) }
            refresh()
            if (!observing) {
                observing = true
                scope.launch(Dispatchers.IO) { DownloadManager.notifyFlow.collect { refresh() } }
            }
        }
    }

    override suspend fun enqueue(gallery: GalleryDetail, restricted: Boolean) = original {
        DownloadManager.startDownload(gallery.galleryInfo, null)
        refresh()
    }

    override suspend fun pause(gid: Long) = original {
        DownloadManager.stopDownload(gid)
        refresh()
    }
    override suspend fun resume(gid: Long) = original {
        DownloadManager.startRangeDownload(longArrayOf(gid))
        refresh()
    }
    override suspend fun cancel(gid: Long) = original {
        DownloadManager.deleteDownload(gid, deleteFiles = true)
        refresh()
    }
    override suspend fun pauseAll() = original {
        DownloadManager.stopAllDownload()
        refresh()
    }
    override suspend fun open(gid: Long): GalleryDownloadOpen = original {
        check(DownloadManager.getDownloadState(gid) == DownloadInfo.STATE_FINISH)
        GalleryDownloadOpen.PlatformReader(gid)
    }
    override fun close() = owner.cancel()
}
