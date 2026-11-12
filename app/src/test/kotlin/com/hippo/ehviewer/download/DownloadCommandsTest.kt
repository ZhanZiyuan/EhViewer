package com.hippo.ehviewer.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadCommandsTest {
    @Test
    fun cancelledCallerDoesNotInterruptTransitionOrLetNextCommandPassIt() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val owner = DownloadCommands(scope) { throw AssertionError(it) }
            val order = mutableListOf<Int>()
            val started = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                owner.execute {
                    order += 1
                    started.complete(Unit)
                    resume.await()
                    order += 2
                }
            }
            started.await()
            val next = async(start = CoroutineStart.UNDISPATCHED) { owner.execute { order += 3 } }
            first.cancelAndJoin()
            assertFalse(next.isCompleted)
            resume.complete(Unit)
            next.await()
            assertEquals(listOf(1, 2, 3), order)
        } finally {
            scope.cancel()
        }
    }
}
