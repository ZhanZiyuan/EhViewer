/*
 * Copyright 2016 Hippo Seven
 * Rewrite with Kotlin coroutines, Tarsin Norbin 2023
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.spider

import androidx.annotation.IntDef
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.partially1
import com.ehviewer.core.files.find
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUrl.getGalleryDetailUrl
import com.hippo.ehviewer.client.EhUrl.referer
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.exception.FatalException
import com.hippo.ehviewer.client.exception.QuotaExceededException
import com.hippo.ehviewer.util.displayString
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.coroutines.runSuspendCatching
import okio.FileNotFoundException
import okio.Path
import splitties.init.appCtx

class SpiderQueen private constructor(val galleryInfo: GalleryInfo, private val previousStop: Job?) : CoroutineScope {
    override val coroutineContext = Dispatchers.IO + Job()

    @Volatile
    lateinit var pageStates: IntArray
    lateinit var spiderInfo: SpiderInfo
    private var pageCountConfirmed = false

    val spiderDen: SpiderDen = SpiderDen(galleryInfo)
    private val mPageStateLock = Any()
    private val mDownloadedPages = AtomicInt(0)
    private val mFinishedPages = AtomicInt(0)
    private val mSpiderListeners: MutableList<OnSpiderListener> = ArrayList()
    private var downloadListener: OnSpiderListener? = null

    @Volatile
    private var mReadReference = 0

    @Volatile
    private var mDownloadReference = 0

    @Volatile
    private var downloadGeneration = 0L

    fun addOnSpiderListener(listener: OnSpiderListener) {
        synchronized(mSpiderListeners) { mSpiderListeners.add(listener) }
    }

    fun removeOnSpiderListener(listener: OnSpiderListener) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.remove(listener)
            if (listener === downloadListener) downloadListener = null
        }
    }

    private fun notifyGetPages(pages: Int) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach { it.onGetPages(pages) }
        }
    }

    private inline fun notifyPageListeners(block: (OnSpiderListener) -> Unit) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach { if (it !== downloadListener || downloadMode) block(it) }
        }
    }

    private fun notifyFatal(error: FatalException) = notifyPageListeners { it.onFatal(error) }

    fun notifyPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
        notifyPageListeners { it.onPageDownload(index, contentLength, receivedSize, bytesRead) }
    }

    private fun notifyPageSuccess(index: Int) {
        notifyPageListeners {
            it.onPageSuccess(
                index,
                mFinishedPages.load(),
                mDownloadedPages.load(),
                pageStates.size,
            )
        }
    }

    private fun notifyPageFailure(index: Int, error: String?) {
        notifyPageListeners {
            it.onPageFailure(
                index,
                error,
                mFinishedPages.load(),
                mDownloadedPages.load(),
                pageStates.size,
            )
        }
    }

    private fun notifyDownloadFinished(generation: Long, finished: Int, downloaded: Int, total: Int, failed: Boolean) {
        synchronized(mSpiderListeners) {
            if (generation != downloadGeneration || mDownloadReference == 0) return
            mSpiderListeners.forEach { it.onFinish(finished, downloaded, total, failed) }
        }
    }

    private fun notifyPageReady(index: Int) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach {
                it.onPageReady(index)
            }
        }
    }

    @Volatile
    private var downloadMode = false
    private var archiveJob: Job? = null
    private var workerGeneration: Long? = null
    private var downloadCleanup: Job? = null

    private val isReady
        get() = this::spiderInfo.isInitialized && this::pageStates.isInitialized

    private val updateLock = Mutex()

    private suspend fun updateMode() = updateLock.withLock {
        val generation = downloadGeneration
        runSuspendCatching {
            awaitReady()
            val intoDownloadMode = mDownloadReference > 0
            if (workerGeneration != null && (!intoDownloadMode || workerGeneration != generation)) {
                drainDownload()
                workerGeneration = null
            }
            spiderDen.setMode(if (intoDownloadMode) MODE_DOWNLOAD else MODE_READ)
            if (intoDownloadMode && workerGeneration != generation && generation == downloadGeneration) {
                workerGeneration = generation
                if (pageCountConfirmed && spiderDen.recoverArchive(size)) {
                    notifyDownloadFinished(generation, size, size, size, failed = false)
                    return@withLock
                }
                // Clear download state
                synchronized(mPageStateLock) {
                    if (generation != downloadGeneration || mDownloadReference == 0) return@withLock
                    val temp: IntArray = pageStates
                    var i = 0
                    val n = temp.size
                    while (i < n) {
                        val oldState = temp[i]
                        if (STATE_DOWNLOADING != oldState) {
                            temp[i] = STATE_NONE
                        }
                        i++
                    }
                    mDownloadedPages.store(0)
                    mFinishedPages.store(0)
                    archiveJob = null
                    downloadMode = true
                }
                mWorkerScope.enterDownloadMode()
            }
        }.onFailure {
            logcat("SpiderQueen") { "Download preparation failed (${it.javaClass.simpleName})" }
            notifyDownloadFinished(generation, 0, 0, galleryInfo.pages, failed = true)
        }
    }

    private fun setMode(@Mode mode: Int) {
        when (mode) {
            MODE_READ -> mReadReference++
            MODE_DOWNLOAD -> mDownloadReference++
        }
        check(mDownloadReference <= 1) { "mDownloadReference can't more than 1" }
    }

    private fun clearMode(@Mode mode: Int) {
        when (mode) {
            MODE_READ -> mReadReference--
            MODE_DOWNLOAD -> synchronized(mPageStateLock) {
                mDownloadReference--
                downloadGeneration++
                downloadMode = false
                archiveJob?.cancel()
            }
        }
        check(!(mReadReference < 0 || mDownloadReference < 0)) { "Mode reference < 0" }
    }

    private val prepareScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
    private val prepareJob = prepareScope.async {
        previousStop?.join()
        doPrepare()
    }
    private suspend fun doPrepare() {
        spiderDen.initDownloadDirIfExist()
        val pages = Either.catch {
            spiderInfo = readSpiderInfoFromLocal() ?: readSpiderInfoFromInternet()
            pageCountConfirmed = true
            spiderInfo.pages
        }.getOrElse {
            logcat(it)
            galleryInfo.pages
        }
        check(pages > 0)
        pageStates = IntArray(pages)
        notifyGetPages(pages)
    }

    suspend fun awaitReady() = prepareJob.await()

    private suspend fun drainDownload() {
        synchronized(mPageStateLock) { archiveJob }?.join()
        mWorkerScope.cancelAndJoinPages()
    }

    private fun stop(): Job {
        val queenScope = this
        return launch {
            try {
                previousStop?.join()
                prepareJob.cancelAndJoin()
                updateLock.withLock {
                    drainDownload()
                    if (!spiderDen.postArchive()) {
                        if (mWorkerScope.startedDownload && !spiderDen.archivesDownload) {
                            runSuspendCatching { spiderDen.writeComicInfo() }.onFailure { logcat(it) }
                        }
                        runSuspendCatching { writeSpiderInfoToLocal() }.onFailure { logcat(it) }
                    }
                }
            } finally {
                queenScope.cancel()
            }
        }
    }

    val size
        get() = pageStates.size

    val error: String?
        get() = null

    private fun getPageState(index: Int): Int {
        synchronized(mPageStateLock) {
            return if (index >= 0 && index < pageStates.size) {
                pageStates[index]
            } else {
                STATE_NONE
            }
        }
    }

    fun preloadPages(pages: List<Int>, pair: IntRange) {
        mWorkerScope.updateRAList(pages, pair)
    }

    fun request(index: Int, force: Boolean, orgImg: Boolean = false) {
        // Get page state
        val state = getPageState(index)

        // Fix state for force
        if (force && state == STATE_FINISHED || state == STATE_FAILED) {
            // Update state to none at once
            updatePageState(index, STATE_NONE)
        }
        mWorkerScope.launch(index, force, orgImg)
    }

    fun save(index: Int, file: Path): Boolean {
        val state = getPageState(index)
        return if (STATE_FINISHED != state) {
            false
        } else {
            spiderDen.saveToPath(index, file)
        }
    }

    fun getExtension(index: Int): String? {
        val state = getPageState(index)
        return if (STATE_FINISHED != state) {
            null
        } else {
            spiderDen.getExtension(index)
        }
    }

    private fun readSpiderInfoFromLocal(): SpiderInfo? = spiderDen.downloadDir?.run {
        find(SPIDER_INFO_FILENAME)?.let { file ->
            readCompatFromPath(file)?.takeIf {
                it.gid == galleryInfo.gid && it.token == galleryInfo.token
            }
        }
    }
        ?: readFromCache(galleryInfo.gid)?.takeIf { it.gid == galleryInfo.gid && it.token == galleryInfo.token }

    private fun readPreviews(previews: List<GalleryPreview>, index: Int, spiderInfo: SpiderInfo) {
        if (index == 0) {
            spiderInfo.previewPerPage = previews.size
        } else if (previews.isNotEmpty()) {
            spiderInfo.previewPerPage = previews.first().position / index
        }
        previews.forEach {
            if (it.pToken.isNotEmpty()) {
                spiderInfo.pTokenMap[it.position] = it.pToken
            }
        }
    }

    private suspend fun readSpiderInfoFromInternet() = EhEngine.getPreviewList(
        getGalleryDetailUrl(galleryInfo.gid, galleryInfo.token),
    ).run {
        val spiderInfo = SpiderInfo(galleryInfo.gid, galleryInfo.token, total)
        readPreviews(previews, 0, spiderInfo)
        spiderInfo
    }

    private val isMpvAvailable = EhUtils.isMpvAvailable
    private val tokenState = Any()
    private val tokenRequests = TokenSingleFlight<Int>(this)

    private suspend fun getPTokenFromMultiPageViewer(index: Int): String? {
        if (!isMpvAvailable) return null
        return runSuspendCatching {
            tokenRequests.run(-1) {
                val tokens = EhEngine.getPTokenListFromMpv(galleryInfo.gid, galleryInfo.token)
                synchronized(tokenState) {
                    tokens.forEachIndexed { position, token -> spiderInfo.pTokenMap[position] = token }
                }
            }
            synchronized(tokenState) { spiderInfo.pTokenMap[index] }
        }.getOrElse {
            logcat(it)
            null
        }
    }

    private suspend fun getPTokenFromInternet(index: Int): String? {
        val previewIndex = synchronized(tokenState) {
            spiderInfo.pTokenMap[index]?.let { return it }
            val perPage = spiderInfo.previewPerPage
            if (perPage > 0) index / perPage else 0
        }
        val url = getGalleryDetailUrl(galleryInfo.gid, galleryInfo.token, previewIndex)
        return runSuspendCatching {
            tokenRequests.run(previewIndex) {
                val result = EhEngine.getPreviewList(url)
                synchronized(tokenState) { readPreviews(result.previews, previewIndex, spiderInfo) }
            }
            synchronized(tokenState) { spiderInfo.pTokenMap[index] }
        }.getOrElse {
            logcat(it)
            null
        }
    }

    @Synchronized
    private fun writeSpiderInfoToLocal() {
        if (!isReady) return
        val snapshot = synchronized(tokenState) {
            SpiderInfo(spiderInfo.gid, spiderInfo.token, spiderInfo.pages, spiderInfo.pTokenMap.toMutableMap(), spiderInfo.previewPerPage)
        }
        spiderDen.downloadDir?.run { snapshot.write(resolve(SPIDER_INFO_FILENAME)) }
        snapshot.saveToCache()
    }

    private fun isStateDone(state: Int): Boolean = state == STATE_FINISHED || state == STATE_FAILED

    fun updatePageState(index: Int, @State state: Int, error: String? = null) {
        synchronized<Unit>(mPageStateLock) {
            val oldState = pageStates[index]
            pageStates[index] = state
            if (!isStateDone(oldState) && isStateDone(state)) {
                mDownloadedPages.incrementAndFetch()
            } else if (isStateDone(oldState) && !isStateDone(state)) {
                mDownloadedPages.decrementAndFetch()
            }
            if (oldState != STATE_FINISHED && state == STATE_FINISHED) {
                mFinishedPages.incrementAndFetch()
            } else if (oldState == STATE_FINISHED && state != STATE_FINISHED) {
                mFinishedPages.decrementAndFetch()
            }
        }

        // Notify listeners
        if (state == STATE_FAILED) {
            notifyPageFailure(index, error)
        } else if (state == STATE_FINISHED) {
            notifyPageSuccess(index)
        }
        synchronized(mPageStateLock) {
            if (!downloadMode || mDownloadReference == 0 || mDownloadedPages.load() != size || archiveJob != null) return
            val generation = downloadGeneration
            val finished = mFinishedPages.load()
            val total = size
            val allPages = finished == total
            archiveJob = launch {
                if (allPages && spiderDen.archivesDownload) {
                    synchronized(mSpiderListeners) {
                        if (generation == downloadGeneration) mSpiderListeners.forEach { it.onArchiving() }
                    }
                }
                val success = allPages && (!spiderDen.archivesDownload || pageCountConfirmed && spiderDen.archive(total))
                notifyDownloadFinished(generation, finished, total, total, failed = !success)
            }
        }
    }

    @IntDef(MODE_READ, MODE_DOWNLOAD)
    @Retention
    annotation class Mode

    @IntDef(STATE_NONE, STATE_DOWNLOADING, STATE_FINISHED, STATE_FAILED)
    @Retention(AnnotationRetention.SOURCE)
    annotation class State
    interface OnSpiderListener {
        fun onGetPages(pages: Int) {}
        fun onFatal(error: FatalException) {}
        fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {}
        fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) {}
        fun onPageFailure(index: Int, error: String?, finished: Int, downloaded: Int, total: Int) {}
        fun onArchiving() {}
        fun onFinish(finished: Int, downloaded: Int, total: Int, failed: Boolean) {}
        fun onPageReady(index: Int) {}
    }

    companion object {
        const val MODE_READ = 0
        const val MODE_DOWNLOAD = 1
        const val STATE_NONE = 0
        const val STATE_DOWNLOADING = 1
        const val STATE_FINISHED = 2
        const val STATE_FAILED = 3
        const val SPIDER_INFO_FILENAME = ".ehviewer"
        private val sQueenMap = mutableMapOf<Long, SpiderQueen>()
        private val closingQueens = mutableMapOf<Long, Job>()

        internal suspend fun awaitDownloadCleanup(gid: Long) {
            synchronized(this) { closingQueens[gid] ?: sQueenMap[gid]?.downloadCleanup }?.join()
        }

        @Synchronized
        fun obtainSpiderQueen(galleryInfo: GalleryInfo, @Mode mode: Int, listener: OnSpiderListener? = null): SpiderQueen {
            val gid = galleryInfo.gid
            return (sQueenMap.getOrPut(gid) { SpiderQueen(galleryInfo, closingQueens[gid]) }).apply {
                setMode(mode)
                if (listener != null) {
                    synchronized(mSpiderListeners) {
                        if (mode == MODE_DOWNLOAD) downloadListener = listener
                        mSpiderListeners.add(listener)
                    }
                    if (isReady) listener.onGetPages(size)
                }
                launch { updateMode() }
            }
        }

        @Synchronized
        fun releaseSpiderQueen(queen: SpiderQueen, @Mode mode: Int) {
            queen.run {
                clearMode(mode)
                if (mReadReference == 0 && mDownloadReference == 0) {
                    val gid = galleryInfo.gid
                    sQueenMap.remove(gid)
                    val closing = stop()
                    closingQueens[gid] = closing
                    closing.invokeOnCompletion {
                        synchronized(this@Companion) { closingQueens.remove(gid, closing) }
                    }
                } else {
                    val update = launch { updateMode() }
                    if (mode == MODE_DOWNLOAD) downloadCleanup = update
                }
            }
        }
    }

    private val mWorkerScope = object {
        private val jobs = hashMapOf<Int, Job>()
        private val activeJobs = mutableSetOf<Job>()
        private val pageLocks = hashMapOf<Int, Mutex>()
        private val semaphore = Semaphore(Settings.multiThreadDownload.value)
        private val showKey = PageShowKey()
        private val requestOrder = PageRequestOrder(Settings.downloadDelay.value.milliseconds)
        private var isDownloadMode = false
        var startedDownload = false
            private set

        fun enterDownloadMode() = synchronized(jobs) {
            if (isDownloadMode) return@synchronized
            isDownloadMode = true
            startedDownload = true
            for (index in 0 until size) {
                if (pageStates[index] != STATE_FINISHED) doLaunchDownloadJob(index, false)
            }
        }

        suspend fun cancelAndJoinPages() {
            val outstanding = synchronized(jobs) {
                activeJobs.toList().onEach { it.cancel(CancellationException(FORCE_RETRY)) }
            }
            outstanding.joinAll()
            synchronized(jobs) { isDownloadMode = false }
        }

        fun updateRAList(list: List<Int>, aliveBound: IntRange = 0..Int.MAX_VALUE) {
            synchronized(jobs) {
                if (isDownloadMode || mReadReference == 0) return
                jobs.forEach { (i, job) ->
                    if (i !in aliveBound) {
                        job.cancel()
                    }
                }
                list.forEach {
                    if (pageStates[it] != STATE_FINISHED && jobs[it]?.isActive != true) {
                        doLaunchDownloadJob(it, false)
                    }
                }
            }
        }

        private fun doLaunchDownloadJob(index: Int, force: Boolean, orgImg: Boolean = false) {
            val currentJob = jobs[index]
            val skipHath = force && !orgImg && currentJob?.isActive == true
            if (force) currentJob?.cancel(CancellationException(FORCE_RETRY))
            if (currentJob?.isActive != true) {
                val pageLock = pageLocks.getOrPut(index) { Mutex() }
                val job = launch {
                    runCatching {
                        // Cancellation and repeated retries must not overlap writes for this page.
                        pageLock.withLock {
                            semaphore.withPermit {
                                doInJob(index, force, orgImg, skipHath)
                            }
                        }
                    }.onFailure {
                        if (it is CancellationException) {
                            if (mReadReference > 0) {
                                logcat(WORKER_DEBUG_TAG) { "Download image $index cancelled" }
                                if (it.message != FORCE_RETRY) {
                                    updatePageState(index, STATE_FAILED, "Cancelled")
                                }
                            }
                            throw it
                        }
                        updatePageState(index, STATE_FAILED, it.displayString())
                    }
                }
                jobs[index] = job
                activeJobs.add(job)
                job.invokeOnCompletion { synchronized(jobs) { activeJobs.remove(job) } }
            }
        }

        fun launch(index: Int, force: Boolean = false, orgImg: Boolean) {
            check(index in 0 until size)
            val state = pageStates[index]
            if (!force && state == STATE_FINISHED) return notifyPageReady(index)
            synchronized(jobs) {
                if (!isDownloadMode && mReadReference > 0) doLaunchDownloadJob(index, force, orgImg)
            }
            launch {
                val job = synchronized(jobs) { jobs[index] }
                job?.join()
                if (pageStates[index] == STATE_FINISHED) {
                    notifyPageReady(index)
                } else if (job?.isCancelled == true && !downloadMode) {
                    notifyPageFailure(index, "Cancelled")
                }
            }
        }

        private suspend fun doInJob(index: Int, force: Boolean, orgImg: Boolean, skipHath: Boolean) {
            suspend fun getPToken(index: Int): String? {
                if (!isReady || index !in 0 until size) return null
                return synchronized(tokenState) { spiderInfo.pTokenMap[index] }
                    ?: getPTokenFromMultiPageViewer(index)
                    ?: getPTokenFromInternet(index)
                    // Preview size may changed, so try to get pToken twice
                    ?: getPTokenFromInternet(index)
            }
            var alreadyDownloaded = false
            val tokens = requestOrder.prepare {
                if (!force && index in spiderDen) {
                    alreadyDownloaded = true
                    return@prepare null
                }
                val token = getPToken(index) ?: return@prepare null
                token to getPToken(index - 1)
            }
            if (alreadyDownloaded) return updatePageState(index, STATE_FINISHED)
            val (pToken, previousPToken) = tokens ?: return updatePageState(index, STATE_FAILED, pTokenFailedMessage)
            updatePageState(index, STATE_DOWNLOADING)

            var skipHathKey: String? = null
            var originImageUrl: String? = null
            var error: String? = null
            var forceHtml = false
            val original = Settings.downloadOriginImage.value || orgImg
            runSuspendCatching {
                repeat(3) { retries ->
                    var imageUrl: String? = null
                    val localShowKey = showKey.snapshot()

                    if (localShowKey == null || forceHtml) {
                        val generation = showKey.beginFetch()
                        // Skipping H@H costs 50 points, only use it as last resort
                        val pageUrl = EhUrl.getPageUrl(galleryInfo.gid, index, pToken, skipHathKey)
                        val result = EhEngine.getGalleryPage(pageUrl, galleryInfo.gid, galleryInfo.token)
                        check509(result.imageUrl)
                        imageUrl = result.imageUrl
                        skipHathKey = result.skipHathKey
                        originImageUrl = result.originImageUrl
                        showKey.publish(generation, result.showKey)
                    }

                    if (imageUrl == null) {
                        runSuspendCatching {
                            EhEngine.getGalleryPageApi(
                                galleryInfo.gid,
                                index,
                                pToken,
                                localShowKey,
                                previousPToken,
                            )
                        }.getOrElse {
                            if (it is FatalException) throw it
                            forceHtml = true
                            return@repeat
                        }.let {
                            check509(it.imageUrl)
                            imageUrl = it.imageUrl
                            skipHathKey = it.skipHathKey
                            originImageUrl = it.originImageUrl
                        }
                    }

                    if (retries == 0 && skipHath) {
                        forceHtml = true
                        return@repeat
                    }

                    val (targetImageUrl, referer) = if (original && originImageUrl != null) {
                        if (retries == 1 && skipHathKey != null) {
                            originImageUrl += "?nl=$skipHathKey"
                        }
                        val pageUrl = EhUrl.getPageUrl(galleryInfo.gid, index, pToken)
                        EhEngine.getOriginalImageUrl(originImageUrl!!, pageUrl) to referer
                    } else {
                        // Original image url won't change, so only set forceHtml in this case
                        forceHtml = true
                        imageUrl to null
                    }
                    checkNotNull(targetImageUrl)

                    runCatching {
                        logcat(WORKER_DEBUG_TAG) { "Start download image $index" }
                        spiderDen.makeHttpCallAndSaveImage(
                            index,
                            targetImageUrl,
                            referer,
                            this@SpiderQueen::notifyPageDownload.partially1(index),
                        )
                        logcat(WORKER_DEBUG_TAG) { "Download image $index succeed" }
                        updatePageState(index, STATE_FINISHED)
                        return
                    }.onFailure {
                        spiderDen.removeIntermediateFiles(index)
                        logcat(WORKER_DEBUG_TAG) { "Download image $index failed" }
                        when (it) {
                            is CancellationException, is FileNotFoundException -> throw it
                        }
                        error = it.displayString()
                    }
                }
            }.onFailure {
                if (it is FatalException) notifyFatal(it)
                error = it.displayString()
                if (error == "Invalid page.") {
                    synchronized(tokenState) {
                        if (spiderInfo.pTokenMap[index] == pToken) spiderInfo.pTokenMap.remove(index)
                    }
                }
            }
            updatePageState(index, STATE_FAILED, error)
        }
    }
    private val pTokenFailedMessage = appCtx.getString(R.string.error_get_ptoken_error)
}

private val Url509Regex = Regex("https://(?:ehgt\\.org/|exhentai\\.org/im)g/509s?\\.gif")
private const val FORCE_RETRY = "Force retry"
private const val WORKER_DEBUG_TAG = "SpiderQueenWorker"

private fun check509(url: String) {
    if (Url509Regex.matches(url)) throw QuotaExceededException()
}
