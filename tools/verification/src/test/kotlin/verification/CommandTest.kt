package verification

import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTest {
    @Test fun timeoutRetainsPartialOutputAndTerminatesOwnedDescendants() = temporary { directory ->
        val marker = directory.resolve("child.pid")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val classpath = listOf(CommandTimeoutProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
        try {
            val failure = runCatching {
                command(java, "-cp", classpath, CommandTimeoutProbe::class.java.name, marker, timeout = Duration.ofSeconds(10))
            }.exceptionOrNull()
            assertTrue("Expected a diagnostic command failure, got $failure", failure is CommandFailure)
            failure as CommandFailure
            assertTrue(failure.timedOut)
            assertTrue(failure.output.contains("Timeout after PT10S"))
            assertTrue(failure.output.contains("timeout-parent-ready"))
            assertTrue(failure.output.contains("timeout-leaf-ready"))
            val child = ProcessHandle.of(marker.readText().trim().toLong())
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (child.map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(25)
            assertFalse("Timed-out command left its descendant alive", child.map { it.isAlive }.orElse(false))
        } finally {
            // Also clean the fixture if a future regression makes an assertion fail.
            if (marker.exists()) ProcessHandle.of(marker.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
        }
    }
}
