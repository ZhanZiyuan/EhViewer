package com.hippo.ehviewer.spider

import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Share one lookup while callers still need it; cancellation of one waiter is independent. */
internal class TokenSingleFlight<K>(private val scope: CoroutineScope) {
    private class Flight(val result: Deferred<Result<Unit>>, var waiters: Int = 0)

    private val flights = mutableMapOf<K, Flight>()

    suspend fun run(key: K, fetch: suspend () -> Unit) {
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                Flight(scope.async(start = CoroutineStart.LAZY) { runCatching { fetch() } })
            }.also { it.waiters++ }
        }
        try {
            flight.result.await().getOrThrow()
        } finally {
            val cancel = synchronized(flights) {
                (--flight.waiters == 0).also { if (it) flights.remove(key) }
            }
            if (cancel) flight.result.cancel()
        }
    }
}

/** Reserve the request order before looking up tokens. Only the head can consume a delay slot. */
internal class PageRequestOrder(
    private val interval: Duration,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
) {
    private val queue = linkedSetOf<CompletableDeferred<Unit>>()
    private var lastRequest = clock.markNow()

    suspend fun <T : Any> prepare(resolve: suspend () -> T?): T? {
        val turn = CompletableDeferred<Unit>()
        synchronized(queue) {
            queue.add(turn)
            if (queue.size == 1) turn.complete(Unit)
        }
        try {
            val value = resolve() ?: return null
            turn.await()
            val remaining = interval - lastRequest.elapsedNow()
            if (remaining > Duration.ZERO) pause(remaining)
            coroutineContext.ensureActive()
            lastRequest = clock.markNow()
            return value
        } finally {
            val next = synchronized(queue) {
                val wasHead = queue.first() === turn
                queue.remove(turn)
                if (wasHead) queue.firstOrNull() else null
            }
            next?.complete(Unit)
        }
    }
}

internal class PageShowKey {
    private var key: String? = null
    private var nextGeneration = 0L
    private var publishedGeneration = 0L

    @Synchronized
    fun snapshot() = key

    @Synchronized
    fun beginFetch() = ++nextGeneration

    @Synchronized
    fun publish(generation: Long, value: String?) {
        if (generation > publishedGeneration) {
            publishedGeneration = generation
            key = value
        }
    }
}
