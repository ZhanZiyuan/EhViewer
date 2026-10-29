package verification

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedDownloadsTest {
    @Test fun atMostFourConcurrentWorkersRetainEveryActualCompilerPayload() = temporary { root ->
        val bytes = javaClass.getResourceAsStream("StagedDownloadsTest.class")!!.use { it.readAllBytes() }
        val firstWave = CountDownLatch(4)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        stagedDownloads((0..7).toList()) { index ->
            val current = active.incrementAndGet()
            maximum.accumulateAndGet(current, ::maxOf)
            try {
                firstWave.countDown()
                check(firstWave.await(5, TimeUnit.SECONDS)) { "Four independent workers did not start" }
                Files.write(root.resolve("$index.class"), bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
            } finally {
                active.decrementAndGet()
            }
        }
        assertEquals(4, maximum.get())
        assertEquals(0, active.get())
        for (index in 0..7) assertTrue(root.resolve("$index.class").readBytes().contentEquals(bytes))
    }

    @Test fun firstCompletedFailureCancelsAndJoinsEveryOtherOwnedWorker() {
        val started = CountDownLatch(4)
        val active = AtomicInteger()
        val stopped = AtomicInteger()
        val failure = runCatching {
            stagedDownloads((0..7).toList()) { index ->
                active.incrementAndGet()
                try {
                    started.countDown()
                    check(started.await(5, TimeUnit.SECONDS)) { "Workers did not start" }
                    if (index == 3) error("Expected owned download failure")
                    CountDownLatch(1).await(30, TimeUnit.SECONDS)
                    error("Failure did not cancel the other downloads")
                } finally {
                    active.decrementAndGet()
                    stopped.incrementAndGet()
                }
            }
        }.exceptionOrNull()
        assertEquals("Expected owned download failure", failure?.message)
        assertEquals(0, active.get())
        assertTrue(stopped.get() >= 4)
    }
}
