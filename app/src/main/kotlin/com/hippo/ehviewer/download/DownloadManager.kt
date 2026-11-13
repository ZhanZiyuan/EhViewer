/*
 * Copyright 2016 Hippo Seven
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
package com.hippo.ehviewer.download

import android.net.Uri
import android.util.SparseLongArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.size
import arrow.fx.coroutines.parMapNotNull
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.data.model.toEntitySnapshot
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.DownloadLabel
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.find
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.preferences.edit
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.mapNotNull
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.exception.FatalException
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.spider.SpeedTracker
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.SpiderQueen.OnSpiderListener
import com.hippo.ehviewer.spider.downloadDirname
import com.hippo.ehviewer.spider.readComicInfo
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.toSimpleTags
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.insertWith
import com.hippo.ehviewer.util.runAssertingNotMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

object DownloadManager : CoroutineScope {
    override val coroutineContext = Dispatchers.IO + Job()

    // All download info list
    private val allInfoList = runAssertingNotMainThread { EhDB.getAllDownloadInfo() as MutableList }

    val downloadInfoList: List<DownloadInfo>
        get() = allInfoList

    // All download info map
    private val mAllInfoMap = java.util.concurrent.ConcurrentHashMap(allInfoList.associateBy { it.gid })

    // All labels without default label
    // Create the SnapshotStateList first in case the database query is time-consuming
    val labelList = mutableStateListOf<DownloadLabel>().apply {
        addAll(runAssertingNotMainThread { EhDB.getAllDownloadLabelList() })
    }

    // Store download info wait to start
    private val mWaitList = ArrayDeque<DownloadInfo>()
    private val listener = java.util.concurrent.atomic.AtomicReference<DownloadListener?>()
    private val mDownloadListener get() = listener.get()
    private var mCurrentTask: DownloadInfo? = null
    private var mCurrentSpider: SpiderQueen? = null
    private var spiderListener: TaskListener? = null

    private val mutableNotifyFlow = MutableSharedFlow<DownloadInfo>(extraBufferCapacity = 1)
    val notifyFlow = mutableNotifyFlow.asSharedFlow()

    private val commands = DownloadCommands(this) { logcat(it) }
    private val persistence = DownloadCommands(this) { logcat(it) }
    private var currentWrites: MutableList<Deferred<Result<Unit>>>? = null

    private suspend fun command(block: suspend () -> Unit) {
        val writes = commands.execute {
            val pending = mutableListOf<Deferred<Result<Unit>>>()
            currentWrites = pending
            try {
                block()
            } finally {
                currentWrites = null
            }
            pending
        }
        writes.forEach { it.await().getOrThrow() }
    }

    private fun persist(write: suspend () -> Unit) {
        val pending = currentWrites
        if (pending == null) persistence.post(write) else pending += persistence.enqueue(write)
    }

    private fun saveStates(infos: Collection<DownloadInfo>) {
        if (infos.isEmpty()) return
        val states = infos.map { it.downloadInfo.copy() }
        persist { EhDB.updateDownloadStates(states) }
    }

    private fun saveNew(info: DownloadInfo, history: Boolean = false) {
        val snapshot = info.copy(galleryInfo = info.galleryInfo.toEntitySnapshot(), downloadInfo = info.downloadInfo.copy())
        persist {
            EhDB.putDownloadInfo(snapshot)
            if (history) EhDB.putHistoryInfo(snapshot)
        }
    }

    private val sortMutex = Mutex()
    private val sortMode
        get() = SortMode.from(Settings.downloadSortMode.value)

    var isInitialized by mutableStateOf(false)
        private set

    init {
        launch {
            val mode = sortMode
            if (mode != SortMode.Default) sortDownloads(mode)
            isInitialized = true
        }
    }

    fun containLabel(label: String?): Boolean {
        if (label == null) {
            return false
        }
        return labelList.any { it.label == label }
    }

    fun containDownloadInfo(gid: Long) = mAllInfoMap.containsKey(gid)

    fun getDownloadInfo(gid: Long) = mAllInfoMap[gid]

    fun getDownloadState(gid: Long): Int {
        val info = mAllInfoMap[gid]
        return info?.state ?: DownloadInfo.STATE_INVALID
    }

    fun setDownloadListener(listener: DownloadListener?) {
        this.listener.set(listener)
    }

    suspend fun detachDownloadListener(owner: DownloadListener, timedOut: Boolean) = command {
        if (listener.compareAndSet(owner, null) && timedOut) stopAllDownloadOwned()
    }

    suspend fun stopServiceIfIdle(owner: DownloadListener, stop: () -> Unit) = commands.execute {
        if (listener.get() === owner && mCurrentTask == null && mWaitList.isEmpty()) stop()
    }

    private fun notifyIfIdle() {
        if (mCurrentTask == null && mWaitList.isEmpty()) mDownloadListener?.onIdle()
    }

    private suspend fun ensureDownload() {
        try {
            while (mCurrentTask == null && mWaitList.isNotEmpty()) {
                val info = mWaitList.removeFirst()
                mCurrentTask = info
                if (info.archiveFile?.name?.endsWith(".zip") == true) {
                    finishCurrent(info.pages, info.pages, info.pages, failed = false)
                    continue
                }
                with(info) {
                    state = DownloadInfo.STATE_DOWNLOAD
                    speed = -1
                    remaining = -1
                    total = -1
                    finished = 0
                    downloaded = 0
                    archiving = false
                    legacy = -1
                }
                val callback = TaskListener()
                spiderListener = callback
                mCurrentSpider = SpiderQueen.obtainSpiderQueen(info, SpiderQueen.MODE_DOWNLOAD, callback)
                // Update in DB
                saveStates(listOf(info))
                // Start speed count
                mSpeedReminder.start(callback)
                // Notify start downloading
                mDownloadListener?.onStart(info)
                // Notify state update
                mutableNotifyFlow.emit(info)
            }
        } finally {
            notifyIfIdle()
        }
    }

    suspend fun startDownload(galleryInfo: BaseGalleryInfo, label: String?) = command {
        if (mCurrentTask?.gid == galleryInfo.gid) {
            // It is current task
            return@command
        }

        // Check in download list
        var info = mAllInfoMap[galleryInfo.gid]
        if (info != null) { // Get it in download list
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                mWaitList.add(info)
                // Update in DB
                saveStates(listOf(info))
                // Notify state update
                mutableNotifyFlow.emit(info)
                // Make sure download is running
                ensureDownload()
            }
        } else {
            // It is new download info
            info = DownloadInfo(galleryInfo.asEntity(), galleryInfo.downloadDirname())
            info.label = label
            info.state = DownloadInfo.STATE_WAIT
            // Add to all download list and map
            sortMutex.withLock {
                allInfoList.insertWith(info, sortMode.comparator())
            }
            mAllInfoMap[galleryInfo.gid] = info

            // Add to wait list
            mWaitList.add(info)

            // Save to
            saveNew(info, history = true)

            // Notify
            mutableNotifyFlow.emit(info)
            // Make sure download is running
            ensureDownload()
        }
    }

    suspend fun startRangeDownload(gidList: LongArray) = command {
        val updateList = gidList.distinct().mapNotNull { mAllInfoMap[it] }
            .filter {
                when (it.state) {
                    DownloadInfo.STATE_NONE, DownloadInfo.STATE_FAILED, DownloadInfo.STATE_FINISH -> true
                    else -> false
                }
            }
        if (updateList.isNotEmpty()) {
            updateList.onEach {
                it.state = DownloadInfo.STATE_WAIT
                mutableNotifyFlow.emit(it)
            }
            saveStates(updateList)
            mWaitList.addAll(updateList)
            ensureDownload()
        }
    }

    @Stable
    @Composable
    fun collectDownloadState(gid: Long): State<Int> = remember {
        notifyFlow.transform { if (it.gid == gid) emit(getDownloadState(gid)) }
    }.collectAsState(getDownloadState(gid))

    @Stable
    @Composable
    fun collectContainDownloadInfo(gid: Long): State<Boolean> = remember {
        notifyFlow.transform { if (it.gid == gid) emit(containDownloadInfo(gid)) }
    }.collectAsState(containDownloadInfo(gid))

    @Stable
    @Composable
    inline fun <T> updatedDownloadInfo(info: DownloadInfo, crossinline transform: @DisallowComposableCalls DownloadInfo.() -> T): T = remember {
        notifyFlow.transform { if (it.gid == info.gid) emit(transform(it)) }
    }.collectAsState(transform(info)).value

    suspend fun startAllDownload() = command {
        val updateList = sortMutex.withLock {
            allInfoList.filter {
                when (it.state) {
                    DownloadInfo.STATE_NONE, DownloadInfo.STATE_FAILED -> true
                    else -> false
                }
            }
        }
        if (updateList.isNotEmpty()) {
            updateList.onEach {
                it.state = DownloadInfo.STATE_WAIT
                mutableNotifyFlow.emit(it)
            }
            saveStates(updateList)
            mWaitList.addAll(updateList)
            ensureDownload()
        }
    }

    suspend fun addDownload(downloadInfoList: List<DownloadInfo>) = command {
        val imported = downloadInfoList.distinctBy { it.gid }.filterNot { containDownloadInfo(it.gid) }.map { info ->
            val state = when (info.state) {
                DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> DownloadInfo.STATE_NONE
                else -> info.state
            }
            info.copy(
                galleryInfo = info.galleryInfo.toEntitySnapshot(),
                artistInfoList = info.artistInfoList.toList(),
                downloadInfo = info.downloadInfo.copy(state = state),
            )
        }
        val comparator = sortMode.comparator()
        // Commit each parent and its artists together. Only expose rows that were persisted.
        for (info in imported) {
            persistence.execute { EhDB.importDownloadWithArtists(info) }
            sortMutex.withLock { allInfoList.insertWith(info, comparator) }
            mAllInfoMap[info.gid] = info
        }
    }

    suspend fun addDownloadLabel(downloadLabelList: List<DownloadLabel>) = command {
        val offset = downloadLabelList.size
        downloadLabelList.forEachIndexed { index, label ->
            if (!containLabel(label.label)) {
                label.position = index + offset
                labelList.add(persistence.execute { EhDB.addDownloadLabel(label) })
            }
        }
    }

    suspend fun restoreDownload(galleryInfo: BaseGalleryInfo, dirname: String) = command {
        if (containDownloadInfo(galleryInfo.gid)) return@command
        val info = DownloadInfo(galleryInfo.asEntity(), dirname)
        info.state = DownloadInfo.STATE_NONE
        info.archiving = false

        // Add to all download list and map
        sortMutex.withLock {
            allInfoList.insertWith(info, sortMode.comparator())
        }
        mAllInfoMap[galleryInfo.gid] = info

        // Save to
        saveNew(info)

        // Notify
        mutableNotifyFlow.emit(info)
    }

    suspend fun stopDownload(gid: Long) = command {
        val info = stopDownloadInternal(gid)
        if (info != null) {
            // Update listener
            mutableNotifyFlow.emit(info)
            // Ensure download
            ensureDownload()
        }
    }

    suspend fun stopRangeDownload(gidList: LongArray) = command {
        stopRangeDownloadInternal(gidList)

        // Update listener
        gidList.mapNotNull { mAllInfoMap[it] }.forEach {
            mutableNotifyFlow.emit(it)
        }

        // Ensure download
        ensureDownload()
    }

    suspend fun stopAllDownload() = command { stopAllDownloadOwned() }

    private suspend fun stopAllDownloadOwned() {
        try {
            // Stop all in wait list
            for (info in mWaitList) {
                info.state = DownloadInfo.STATE_NONE
                // Update in DB
                // Notify
                mutableNotifyFlow.emit(info)
            }
            saveStates(mWaitList)
            mWaitList.clear()

            // Stop current
            val info = stopCurrentDownloadInternal()
            info?.let { mutableNotifyFlow.emit(it) }
        } finally {
            notifyIfIdle()
        }
    }

    suspend fun deleteDownload(gid: Long, deleteFiles: Boolean = false) = command {
        stopDownloadInternal(gid)
        val info = mAllInfoMap[gid]
        if (info != null) {
            // Remove from DB
            persist { EhDB.removeDownloadInfo(info) }

            // Remove all list and map
            sortMutex.withLock {
                allInfoList.remove(info)
            }
            mAllInfoMap.remove(info.gid)

            // Update listener
            mutableNotifyFlow.emit(info)

            // Ensure download
            ensureDownload()

            if (deleteFiles) {
                info.downloadDir?.delete()
                info.tempDownloadDir?.delete()
                persist { EhDB.removeDownloadDirname(info.gid) }
            }
        }
    }

    suspend fun deleteRangeDownload(gidList: LongArray) = command {
        stopRangeDownloadInternal(gidList)
        val list = gidList.mapNotNull { gid ->
            mAllInfoMap.remove(gid)
        }
        persist { EhDB.removeDownloadInfo(list) }
        sortMutex.withLock {
            allInfoList.removeAll(list.toSet())
        }

        // Update listener
        list.forEach { mutableNotifyFlow.emit(it) }

        // Ensure download
        ensureDownload()
    }

    suspend fun sortDownloads(mode: SortMode) = sortMutex.withLock {
        allInfoList.sortWith(mode.comparator())
    }

    suspend fun resetAllReadingProgress() = runCatching {
        EhDB.clearProgressInfo()
    }.onFailure { logcat(it) }

    // Update in DB
    // Update listener
    // No ensureDownload
    private suspend fun stopDownloadInternal(gid: Long): DownloadInfo? {
        // Check current task
        if (mCurrentTask?.gid == gid) {
            // Stop current
            return stopCurrentDownloadInternal()
        }
        val iterator = mWaitList.iterator()
        while (iterator.hasNext()) {
            val info = iterator.next()
            if (info.gid == gid) {
                // Remove from wait list
                iterator.remove()
                // Update state
                info.state = DownloadInfo.STATE_NONE
                // Update in DB
                saveStates(listOf(info))
                return info
            }
        }
        return null
    }

    // Update in DB
    // Update mDownloadListener
    private suspend fun stopCurrentDownloadInternal(): DownloadInfo? {
        val info = mCurrentTask
        val spider = mCurrentSpider
        // Release spider
        if (spider != null) {
            spiderListener?.let(spider::removeOnSpiderListener)
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD)
        }
        mCurrentTask = null
        mCurrentSpider = null
        spiderListener = null
        // Stop speed reminder
        mSpeedReminder.stop()
        if (info == null) {
            return null
        }

        // Update state
        info.state = DownloadInfo.STATE_NONE
        info.archiving = false
        // Update in DB
        saveStates(listOf(info))
        // Listener
        mDownloadListener?.onCancel(info)
        return info
    }

    // Update in DB
    // Update mDownloadListener
    private suspend fun stopRangeDownloadInternal(gidList: LongArray) {
        // Two way
        if (gidList.size < mWaitList.size) {
            for (element in gidList) {
                stopDownloadInternal(element)
            }
        } else {
            // Check current task
            if (mCurrentTask != null && gidList.contains(mCurrentTask!!.gid)) {
                // Stop current
                stopCurrentDownloadInternal()
            }

            // Check all in wait list
            val stopped = mutableListOf<DownloadInfo>()
            val iterator = mWaitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove()
                    // Update state
                    info.state = DownloadInfo.STATE_NONE
                    // Update in DB
                    stopped += info
                }
            }
            saveStates(stopped)
        }
    }

    /**
     * @param label Not allow new label
     */
    suspend fun changeLabel(list: Collection<DownloadInfo>, label: String?) = command {
        if (null != label && !containLabel(label)) {
            logcat(TAG, LogPriority.ERROR) { "Not exits label: $label" }
            return@command
        }
        list.forEach {
            it.label = label
        }
        saveStates(list)
    }

    suspend fun addLabel(label: String?) = command {
        if (label == null || containLabel(label)) {
            return@command
        }
        val row = DownloadLabel(label, labelList.size)
        labelList.add(persistence.execute { EhDB.addDownloadLabel(row) })
    }

    suspend fun renameLabel(from: String, to: String) = command {
        if (from == to || containLabel(to)) return@command
        val index = labelList.indexOfFirst { it.label == from }
        if (index != -1) {
            val new = labelList[index].copy(label = to)
            persistence.execute { EhDB.updateDownloadLabel(new) }
            labelList[index] = new
            sortMutex.withLock {
                allInfoList.forEach {
                    if (it.label == from) {
                        it.label = to
                    }
                }
            }
        }
    }

    suspend fun deleteLabel(label: String) = command {
        with(labelList) {
            val index = indexOfFirst { it.label == label }
            if (index < 0) return@command
            val item = get(index)
            persistence.execute { EhDB.removeDownloadLabel(item) }
            subList(index + 1, size).forEach {
                it.position--
            }
            removeAt(index)
        }
        sortMutex.withLock {
            allInfoList.forEach {
                if (it.label == label) {
                    it.label = null
                }
            }
        }
    }

    suspend fun readMetadataFromLocal() {
        val list = sortMutex.withLock {
            allInfoList.mapNotNull {
                val updateGallery = it.pages == 0 || it.simpleTags == null
                val updateArtist = it.artistInfoList.isEmpty()
                if (updateGallery || updateArtist) {
                    Triple(it, updateGallery, updateArtist)
                } else {
                    null
                }
            }
        }.parMapNotNull(concurrency = 5) { (info, updateGallery, updateArtist) ->
            info.downloadDir?.run {
                val comicInfo = find(COMIC_INFO_FILE)?.let { readComicInfo(it) }
                if (comicInfo != null) {
                    val galleryInfo = if (updateGallery) {
                        info.pages = comicInfo.pageCount
                        info.simpleTags = comicInfo.toSimpleTags()
                        info.galleryInfo
                    } else {
                        null
                    }

                    val artistList = if (updateArtist && comicInfo.penciller != null) {
                        info.artistInfoList = DownloadArtist.from(info.gid, comicInfo.penciller)
                        info.gid to info.artistInfoList
                    } else {
                        null
                    }

                    if (galleryInfo != null || artistList != null) {
                        galleryInfo to artistList
                    } else {
                        null
                    }
                } else if (info.pages == 0) {
                    val galleryInfo = find(SPIDER_INFO_FILENAME)?.let {
                        readCompatFromPath(it)?.run {
                            info.pages = pages
                            info.galleryInfo
                        }
                    }

                    galleryInfo?.let { it to null }
                } else {
                    null
                }
            }
        }

        val galleryInfoList = buildList {
            list.forEach { (info, artists) ->
                info?.let {
                    add(it)
                }
                artists?.let { (gid, updateList) ->
                    EhDB.putDownloadArtist(gid, updateList)
                }
            }
        }
        if (galleryInfoList.isNotEmpty()) EhDB.updateGalleryInfo(galleryInfoList)
    }

    private class TaskListener : OnSpiderListener {
        private fun active(event: suspend () -> Unit) = commands.post {
            if (spiderListener === this) event()
        }

        override fun onGetPages(pages: Int) = active {
            mCurrentTask?.let { info ->
                info.total = pages
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }

        override fun onFatal(error: FatalException) = active {
            mDownloadListener?.onFatal(error)
            stopAllDownloadOwned()
        }

        override fun onPageDownload(
            index: Int,
            contentLength: Long,
            receivedSize: Long,
            bytesRead: Int,
        ) = active {
            mSpeedReminder.onDownload(index, contentLength, receivedSize, bytesRead)
        }

        override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) = active {
            mSpeedReminder.onDone(index)
            mCurrentTask?.let { info ->
                info.finished = maxOf(info.finished, finished)
                info.downloaded = maxOf(info.downloaded, downloaded)
                info.total = total
                mDownloadListener?.onDownload(info)
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }

        override fun onPageFailure(
            index: Int,
            error: String?,
            finished: Int,
            downloaded: Int,
            total: Int,
        ) = active {
            mSpeedReminder.onDone(index)
            mCurrentTask?.let { info ->
                info.finished = maxOf(info.finished, finished)
                info.downloaded = maxOf(info.downloaded, downloaded)
                info.total = total
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }

        override fun onFinish(finished: Int, downloaded: Int, total: Int, failed: Boolean) = active {
            finishCurrent(finished, downloaded, total, failed)
            ensureDownload()
        }

        override fun onArchiving() = active {
            mCurrentTask?.let { info ->
                info.archiving = true
                mDownloadListener?.onDownload(info)
                mutableNotifyFlow.emit(info)
            }
        }
    }

    private suspend fun finishCurrent(finished: Int, downloaded: Int, total: Int, failed: Boolean) {
        mSpeedReminder.onFinish()
        mCurrentSpider?.let { spider ->
            mCurrentSpider = null
            spiderListener?.let(spider::removeOnSpiderListener)
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD)
        }
        spiderListener = null
        mCurrentTask?.let { info ->
            mCurrentTask = null
            mSpeedReminder.stop()
            info.finished = finished
            info.downloaded = downloaded
            info.total = total
            info.archiving = false
            if (!failed && total > 0 && info.pages != total) {
                info.pages = total
                val metadata = info.galleryInfo.toEntitySnapshot()
                persist { EhDB.updateGalleryInfo(listOf(metadata)) }
            }
            info.legacy = total - finished
            info.state = if (failed) DownloadInfo.STATE_FAILED else DownloadInfo.STATE_FINISH
            saveStates(listOf(info))
            mDownloadListener?.onFinish(info)
            mutableNotifyFlow.emit(info)
        } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
    }

    interface DownloadListener {
        /**
         * Fatal error
         */
        fun onFatal(error: FatalException)

        /**
         * Start download
         */
        fun onStart(info: DownloadInfo)

        /**
         * Update download speed
         */
        fun onDownload(info: DownloadInfo)

        /**
         * Download done
         */
        fun onFinish(info: DownloadInfo)

        /**
         * Download done
         */
        fun onCancel(info: DownloadInfo)

        fun onIdle()
    }

    private val mSpeedReminder = object {
        private val contentLengthMap = SparseLongArray()
        private val receivedSizeMap = SparseLongArray()
        private val tracker = SpeedTracker()
        private val mutex = Mutex()
        private var currentJob: Job? = null
        fun start(owner: TaskListener) {
            if (currentJob == null) {
                currentJob = launch {
                    tracker.speedFlow().collect { speed ->
                        commands.post { if (spiderListener === owner) updateSpeed(speed) }
                    }
                }
            }
        }

        suspend fun stop() {
            tracker.reset()
            mutex.withLock {
                contentLengthMap.clear()
                receivedSizeMap.clear()
            }
            currentJob?.cancel()
            currentJob = null
        }

        suspend fun onDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            mutex.withLock {
                contentLengthMap.put(index, contentLength)
                receivedSizeMap.put(index, receivedSize)
            }
            tracker.track(bytesRead)
        }

        suspend fun onDone(index: Int) {
            mutex.withLock {
                contentLengthMap.delete(index)
                receivedSizeMap.delete(index)
            }
        }

        suspend fun onFinish() {
            mutex.withLock {
                contentLengthMap.clear()
                receivedSizeMap.clear()
            }
        }

        private suspend fun updateSpeed(speed: Long) {
            mCurrentTask?.let { info ->
                info.speed = speed

                // Calculate remaining
                if (info.total <= 0) {
                    info.remaining = -1
                } else if (speed == 0L) {
                    info.remaining = 300L * 24L * 60L * 60L * 1000L // 300 days
                } else {
                    var downloadingCount = 0
                    var downloadingContentLengthSum = 0L
                    var totalSize = 0L
                    mutex.withLock {
                        for (i in 0..<contentLengthMap.size) {
                            val contentLength = contentLengthMap.valueAt(i)
                            val receivedSize = receivedSizeMap.valueAt(i)
                            if (contentLength <= 0) continue
                            downloadingCount++
                            downloadingContentLengthSum += contentLength
                            totalSize += contentLength - receivedSize
                        }
                    }
                    if (downloadingCount != 0) {
                        totalSize += downloadingContentLengthSum * (info.total - info.downloaded - downloadingCount) / downloadingCount
                        info.remaining = totalSize / speed * 1000
                    } else {
                        info.remaining = -1
                    }
                }
                mDownloadListener?.onDownload(info)
                mutableNotifyFlow.emit(info)
            }
        }
    }

    private const val TAG = "DownloadManager"
}

var downloadLocation: Path
    get() = with(Settings) {
        if (downloadScheme != null) {
            Uri.Builder().apply {
                scheme(downloadScheme)
                encodedAuthority(downloadAuthority)
                encodedPath(downloadPath)
                encodedQuery(downloadQuery)
                encodedFragment(downloadFragment)
            }.build().toOkioPath()
        } else {
            AppConfig.defaultDownloadDir?.toOkioPath() ?: "".toPath()
        }
    }
    set(value) = with(value.toUri()) {
        Settings.edit {
            downloadScheme = scheme
            downloadAuthority = encodedAuthority
            downloadPath = encodedPath
            downloadQuery = encodedQuery
            downloadFragment = encodedFragment
        }
    }

val DownloadInfo.downloadDir get() = dirname?.let { downloadLocation / it }
val DownloadInfo.archiveFile get() = downloadDir?.run {
    find("$gid.cbz")?.takeIf { find("$gid.cbz.pending") == null } ?: find("$gid.zip")
}
val GalleryInfo.tempDownloadDir get() = AppConfig.externalTempPersistDir?.let { it / "$gid" }
