package com.ehviewer.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

class GalleryAnimationLeaseTest {
    @Test fun pageDisposalAndLateDecoderCompletionReleaseEveryOwnerOnce() {
        var failures = 0
        val lease = GalleryAnimationLease { failures++ }
        var early = 0
        var late = 0
        lease.own(AutoCloseable { early++ })
        lease.notifyFailure()
        assertEquals(1, failures)
        lease.close()
        lease.close()
        assertEquals(1, early)
        assertFailsWith<CancellationException> { lease.own(AutoCloseable { late++ }) }
        assertEquals(1, late)
        lease.notifyFailure()
        assertEquals(1, failures)
    }

    @Test fun decodingAndPageDisposalMayRaceWithoutLeakingOrDoubleClosing() {
        repeat(100) {
            val lease = GalleryAnimationLease()
            val closed = AtomicInteger()
            val start = CountDownLatch(1)
            val decoder = thread {
                start.await()
                try {
                    lease.own(AutoCloseable { closed.incrementAndGet() })
                } catch (_: CancellationException) {
                    // A disposed request rejects this result after closing it.
                }
            }
            start.countDown()
            lease.close()
            decoder.join()
            lease.close()
            assertEquals(1, closed.get())
        }
    }
}
