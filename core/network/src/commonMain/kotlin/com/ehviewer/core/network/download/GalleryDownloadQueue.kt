package com.ehviewer.core.network.download

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadFailure
import com.ehviewer.core.domain.download.GalleryDownloadState
import com.ehviewer.core.domain.download.GalleryDownloadStorage
import com.ehviewer.core.domain.download.GalleryDownloadStorageException
import com.ehviewer.core.model.GalleryDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DownloadPageBytes(val token: String, val bytes: ByteArray)

fun interface GalleryDownloadSource {
    suspend fun load(index: Int): DownloadPageBytes
}

/** One active image bounds memory and network concurrency; committed pages survive process death. */
class GalleryDownloadQueue(
    private val storage: GalleryDownloadStorage,
    parent: CoroutineScope,
    private val openSource: suspend (GalleryDownload) -> GalleryDownloadSource,
) : GalleryDownloads {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val commands = Mutex()
    private val mutex = Mutex()
    private val mutableTasks = MutableStateFlow<List<GalleryDownload>>(emptyList())
    override val tasks = mutableTasks.asStateFlow()
    private var initialized = false
    private var runner: Job? = null
    private var active: Long? = null

    override suspend fun initialize() = commands.withLock {
        mutex.withLock {
            owner.ensureActive()
            if (initialized) return@withLock
            val recovered = try {
                val saved = storage.list()
                require(saved.size <= 500 && saved.map { it.gid }.toSet().size == saved.size)
                saved.sortedBy { it.position }.map { recover(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw GalleryDownloadStorageException()
            }
            mutableTasks.value = recovered
            initialized = true
            schedule()
        }
    }

    private suspend fun recover(task: GalleryDownload): GalleryDownload {
        task.validate()
        require(task.pages > 0)
        return try {
            val pages = storage.pages(task.gid)
            require(pages.size <= task.pages && pages.map { it.index } == pages.indices.toList())
            require(pages.all { Regex("[a-f0-9]{10}").matches(it.token) && it.bytes in 1..MAX_IMAGE_BYTES })
            val state = when {
                pages.size == task.pages -> GalleryDownloadState.COMPLETE
                task.state in setOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING, GalleryDownloadState.COMPLETE) -> GalleryDownloadState.PAUSED
                else -> task.state
            }
            task.copy(state = state, finished = pages.size, failure = task.failure.takeIf { state == GalleryDownloadState.FAILED }).also { if (it != task) storage.save(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val failed = task.copy(state = GalleryDownloadState.FAILED, failure = GalleryDownloadFailure.STORAGE)
            try {
                storage.save(failed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GalleryDownloadStorageException) {
                // Preserve the persisted task; the UI still exposes this failure and explicit deletion.
            }
            failed
        }
    }

    override suspend fun enqueue(gallery: GalleryDetail, restricted: Boolean) = commands.withLock {
        mutex.withLock {
            ready()
            val last = tasks.value.maxOfOrNull { it.position } ?: -1L
            check(last < Long.MAX_VALUE)
            val task = GalleryDownload(gallery.gid, gallery.token, gallery.title ?: gallery.titleJpn.orEmpty(), gallery.pages, restricted, position = last + 1)
            task.validate()
            require(task.pages > 0)
            val existing = tasks.value.firstOrNull { it.gid == task.gid }
            if (existing != null) {
                require(existing.token == task.token && existing.restricted == task.restricted && existing.pages == task.pages)
                return@withLock
            }
            check(tasks.value.size < 500) { "Download queue is full" }
            storage.save(task)
            mutableTasks.value += task
            schedule()
        }
    }

    override suspend fun pause(gid: Long) = commands.withLock { pauseLocked(setOf(gid)) }

    override suspend fun pauseAll() = commands.withLock { pauseLocked(tasks.value.map { it.gid }.toSet()) }

    private suspend fun pauseLocked(gids: Set<Long>) {
        var failure: GalleryDownloadStorageException? = null
        val stop = mutex.withLock {
            ready()
            val running = runner.takeIf { active in gids }
            running?.cancel()
            tasks.value.filter { it.gid in gids && it.state in setOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING) }.forEach { task ->
                try {
                    update(task.copy(state = GalleryDownloadState.PAUSED))
                } catch (storageFailure: GalleryDownloadStorageException) {
                    failure = storageFailure
                    mutableTasks.value = tasks.value.map { if (it.gid == task.gid) task.copy(state = GalleryDownloadState.FAILED, failure = GalleryDownloadFailure.STORAGE) else it }
                }
            }
            running
        }
        stop?.cancelAndJoin()
        failure?.let { throw it }
    }

    override suspend fun resume(gid: Long) = commands.withLock {
        mutex.withLock {
            ready()
            val task = tasks.value.single { it.gid == gid }
            if (task.state in setOf(GalleryDownloadState.PAUSED, GalleryDownloadState.FAILED)) {
                val pages = storage.pages(gid)
                check(pages.size <= task.pages && pages.map { it.index } == pages.indices.toList())
                update(task.copy(state = if (pages.size == task.pages) GalleryDownloadState.COMPLETE else GalleryDownloadState.QUEUED, finished = pages.size, failure = null))
            }
            schedule()
        }
    }

    override suspend fun cancel(gid: Long) = commands.withLock {
        pauseLocked(setOf(gid))
        mutex.withLock {
            ready()
            storage.remove(gid)
            mutableTasks.value = tasks.value.filterNot { it.gid == gid }
        }
    }

    override suspend fun open(gid: Long): GalleryDownloadOpen = mutex.withLock {
        ready()
        val task = tasks.value.single { it.gid == gid }
        check(task.state == GalleryDownloadState.COMPLETE)
        check(storage.pages(gid).size == task.pages)
        GalleryDownloadOpen.Cached(object : CachedGalleryPages {
            override val gallery = task
            override suspend fun read(index: Int): ByteArray {
                require(index in 0 until task.pages)
                return storage.readPage(gid, index)
            }
        })
    }

    private fun ready() {
        owner.ensureActive()
        check(initialized) { "Download storage has not been opened" }
    }

    private suspend fun update(task: GalleryDownload) {
        task.validate()
        storage.save(task)
        mutableTasks.value = tasks.value.map { if (it.gid == task.gid) task else it }
    }

    /** Caller holds mutex. A lazy job prevents its completion from racing runner assignment. */
    private fun schedule() {
        if (!owner.isActive || runner?.isActive == true || tasks.value.none { it.state == GalleryDownloadState.QUEUED }) return
        runner = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val task = mutex.withLock {
                        tasks.value.firstOrNull { it.state == GalleryDownloadState.QUEUED }
                    } ?: break
                    try {
                        val started = mutex.withLock {
                            val current = tasks.value.firstOrNull { it.gid == task.gid }
                            if (current?.state != GalleryDownloadState.QUEUED) return@withLock false
                            update(current.copy(state = GalleryDownloadState.DOWNLOADING))
                            active = task.gid
                            true
                        }
                        if (!started) continue
                        val source = openSource(task)
                        for (index in task.finished until task.pages) {
                            val page = source.load(index)
                            require(Regex("[a-f0-9]{10}").matches(page.token) && page.bytes.size.toLong() in 1..MAX_IMAGE_BYTES)
                            mutex.withLock {
                                owner.ensureActive()
                                val current = tasks.value.firstOrNull { it.gid == task.gid } ?: throw CancellationException("Download was removed")
                                if (current.state != GalleryDownloadState.DOWNLOADING) throw CancellationException("Download was paused")
                                storage.writePage(task.gid, index, page.token, page.bytes)
                                update(current.copy(finished = index + 1))
                            }
                        }
                        mutex.withLock { update(tasks.value.single { it.gid == task.gid }.copy(state = GalleryDownloadState.COMPLETE)) }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                val current = tasks.value.firstOrNull { it.gid == task.gid } ?: return@withLock
                                if (owner.isActive && current.state == GalleryDownloadState.DOWNLOADING) {
                                    try {
                                        update(current.copy(state = GalleryDownloadState.PAUSED))
                                    } catch (_: GalleryDownloadStorageException) {
                                        mutableTasks.value = tasks.value.map { if (it.gid == task.gid) current.copy(state = GalleryDownloadState.FAILED, failure = GalleryDownloadFailure.STORAGE) else it }
                                    }
                                }
                            }
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        mutex.withLock {
                            val current = tasks.value.firstOrNull { it.gid == task.gid } ?: return@withLock
                            val failed = current.copy(state = GalleryDownloadState.FAILED, failure = if (failure is GalleryDownloadStorageException) GalleryDownloadFailure.STORAGE else GalleryDownloadFailure.NETWORK_OR_IMAGE)
                            try {
                                update(failed)
                            } catch (_: GalleryDownloadStorageException) {
                                mutableTasks.value = tasks.value.map { if (it.gid == task.gid) failed.copy(failure = GalleryDownloadFailure.STORAGE) else it }
                            }
                        }
                    } finally {
                        mutex.withLock { active = null }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        runner = null
                        active = null
                        schedule()
                    }
                }
            }
        }
        runner!!.start()
    }

    override fun close() = owner.cancel()

    suspend fun closeAndJoin() = owner.cancelAndJoin()

    companion object {
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    }
}
