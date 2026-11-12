package com.hippo.ehviewer.spider

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageRequestCoordinationTest {
    @Test
    fun samePreviewSharesFetchWhenFirstWaiterCancels() = runBlocking {
        val singleFlight = TokenSingleFlight<Int>(this)
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run(3) {
                requested.complete(Unit)
                response.await()
            }
        }
        requested.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run(3) { error("Duplicate fetch") }
        }
        first.cancelAndJoin()
        response.complete(Unit)
        second.join()
        assertFalse(second.isCancelled)
    }

    @Test
    fun tokenPreparationRunsConcurrentlyButStartsStayFifoAndSpaced() = runBlocking {
        val clock = TestTimeSource()
        val starts = mutableListOf<Int>()
        val pauses = mutableListOf<Long>()
        val order = PageRequestOrder(50.milliseconds, clock) {
            pauses += it.inWholeMilliseconds
            clock += it
        }
        val firstReady = CompletableDeferred<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { order.prepare { firstReady.await() }?.let(starts::add) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { order.prepare { 2 }?.let(starts::add) }
        assertFalse(second.isCompleted)
        firstReady.complete(1)
        first.await()
        second.await()
        assertEquals(listOf(1, 2), starts)
        assertEquals(listOf(50L, 50L), pauses)
    }

    @Test
    fun cancelledHeadLetsNextRequestProceed() = runBlocking {
        val order = PageRequestOrder(0.milliseconds)
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            order.prepare { CompletableDeferred<Int>().await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) { order.prepare { 2 } }
        first.cancelAndJoin()
        assertEquals(2, second.await())
    }

    @Test
    fun olderResponseCannotReplacePublishedShowKey() {
        val key = PageShowKey()
        val older = key.beginFetch()
        val newer = key.beginFetch()
        key.publish(newer, "new")
        key.publish(older, "old")
        assertEquals("new", key.snapshot())
    }
}
