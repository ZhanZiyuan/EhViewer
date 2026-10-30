package com.ehviewer.shared.reader

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class ReaderPage<T>(val url: String, val value: T, val bytes: Long)
internal sealed interface ReaderPageState<out T> {
    data object Loading : ReaderPageState<Nothing>
    data class Ready<T>(val page: ReaderPage<T>) : ReaderPageState<T>
    data class Failed(val failure: GalleryFailure) : ReaderPageState<Nothing>
}

/** UI-scope owner: at most the visible page and one prefetched page, with bounded decoded bytes. */
internal class ReaderPageWindow<T>(
    private val count: Int,
    private val scope: CoroutineScope,
    private val budget: Long = 64L * 1024 * 1024,
    private val load: suspend (Int) -> ReaderPage<T>,
) : AutoCloseable {
    private val mutable = MutableStateFlow<Map<Int, ReaderPageState<T>>>(emptyMap())
    val pages = mutable.asStateFlow()
    private val jobs = mutableMapOf<Int, Job>()
    private val tickets = mutableMapOf<Int, Long>()
    private val permits = Semaphore(2)
    private var serial = 0L
    private var center = -1
    private var wanted = emptySet<Int>()
    private var closed = false

    fun focus(index: Int) {
        if (closed) return
        require(index in 0 until count)
        if (center == index) return
        center = index
        wanted = listOf(index, index + 1).filter { it in 0 until count }.toSet()
        for (old in jobs.keys.toList().filter { it !in wanted }) {
            jobs.remove(old)?.cancel()
            tickets.remove(old)
        }
        mutable.value = mutable.value.filterKeys { it in wanted }
        for (page in wanted) if (page !in mutable.value && page !in jobs) start(page)
    }

    fun retry(index: Int) {
        if (closed || index !in wanted) return
        jobs.remove(index)?.cancel()
        tickets.remove(index)
        start(index)
    }

    private fun start(index: Int) {
        val ticket = ++serial
        tickets[index] = ticket
        mutable.value = mutable.value + (index to ReaderPageState.Loading)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun current() = !closed && index in wanted && tickets[index] == ticket
            try {
                val page = permits.withPermit { load(index) }
                if (page.bytes !in 1..budget) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
                if (current()) {
                    var next = mutable.value + (index to ReaderPageState.Ready(page))
                    while (next.values.sumOf { (it as? ReaderPageState.Ready)?.page?.bytes ?: 0L } > budget) {
                        val victim = next.entries.firstOrNull { it.key != center && it.value is ReaderPageState.Ready }?.key ?: break
                        next = next - victim
                    }
                    mutable.value = next
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GalleryRequestException) {
                if (current()) mutable.value = mutable.value + (index to ReaderPageState.Failed(failure.failure))
            } catch (_: Exception) {
                if (current()) mutable.value = mutable.value + (index to ReaderPageState.Failed(GalleryFailure.INVALID_RESPONSE))
            } finally {
                if (tickets[index] == ticket) {
                    tickets.remove(index)
                    jobs.remove(index)
                }
            }
        }
        jobs[index] = job
        job.start()
    }

    override fun close() {
        closed = true
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        tickets.clear()
        wanted = emptySet()
        mutable.value = emptyMap()
    }
}
