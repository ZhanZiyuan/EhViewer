package com.ehviewer

import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBuildCommandTest {
    private fun owned(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("ehviewer-native-command-").toFile()
        try {
            test(directory)
        } finally {
            check(directory.deleteRecursively())
        }
    }

    private fun command(root: File, mode: String): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}")
        val classpath = listOf(NativeBuildCommandProbe::class.java, Unit::class.java).map {
            File(it.protectionDomain.codeSource.location.toURI()).absolutePath
        }.distinct().joinToString(File.pathSeparator)
        return listOf(java.absolutePath, "-cp", classpath, NativeBuildCommandProbe::class.java.name, mode, root.resolve("calls").absolutePath)
    }

    @Test fun aSuccessfulExternalCommandRunsOnceAndHasARealReceipt() = owned { root ->
        assertEquals(1, NativeBuildCommand(root).run("rust", root, command(root, "success"), true).attempts)
        assertEquals("1", root.resolve("calls").readText())
        assertTrue(root.resolve("rust.log").readText().contains("owned command completed"))
        assertTrue(root.resolve("rust.attempts.json").readText().contains("\"exit_code\":0"))
        assertFalse(root.resolve("rust.retry-1.log").exists())
    }

    @Test fun anAppleLinkerCrashRetriesTheSameCommandAndRetainsTheFailedAttempt() = owned { root ->
        assertEquals(2, NativeBuildCommand(root).run("rust", root, command(root, "once"), true).attempts)
        assertEquals("2", root.resolve("calls").readText())
        assertTrue(root.resolve("rust.log").readText().contains("Segmentation fault: 11"))
        assertTrue(root.resolve("rust.retry-1.log").readText().contains("owned command completed"))
        val receipt = root.resolve("rust.attempts.json").readText()
        assertTrue(receipt.contains("\"exit_code\":1") && receipt.contains("\"exit_code\":0"))
        assertTrue(receipt.contains("\"timeout_millis\":900000"))
    }

    @Test fun ordinaryCompilerFailuresAndNonAppleCrashesDoNotRetry() {
        for (mode in listOf("ordinary", "foreign")) {
            owned { root ->
                assertThrows(IllegalStateException::class.java) { NativeBuildCommand(root).run("rust", root, command(root, mode), true) }
                assertEquals("1", root.resolve("calls").readText())
                assertFalse(root.resolve("rust.retry-1.log").exists())
            }
        }
    }

    @Test fun anAppleCrashWithoutCallerAuthorizationFailsOnItsFirstAttempt() = owned { root ->
        assertThrows(IllegalStateException::class.java) { NativeBuildCommand(root).run("configure", root, command(root, "always")) }
        assertEquals("1", root.resolve("calls").readText())
        assertFalse(root.resolve("configure.retry-1.log").exists())
    }

    @Test fun aPersistentAppleCrashFailsAfterOneRetry() = owned { root ->
        assertThrows(IllegalStateException::class.java) { NativeBuildCommand(root).run("rust", root, command(root, "always"), true) }
        assertEquals("2", root.resolve("calls").readText())
        assertTrue(root.resolve("rust.log").readText().contains("Segmentation fault: 11"))
        assertTrue(root.resolve("rust.retry-1.log").readText().contains("Segmentation fault: 11"))
        assertFalse(root.resolve("rust.retry-2.log").exists())
    }

    @Test fun retrySharesTheFirstAttemptsDeadlineInsteadOfReceivingAnotherFullBudget() = owned { root ->
        assertThrows(IllegalStateException::class.java) {
            NativeBuildCommand(root, Duration.ofSeconds(3)).run("rust", root, command(root, "budget"), true)
        }
        assertEquals("2", root.resolve("calls").readText())
        assertTrue(root.resolve("rust.log").readText().contains("Segmentation fault: 11"))
        assertTrue(root.resolve("rust.attempts.json").readText().contains("\"timed_out\":true"))
        assertFalse(root.resolve("rust.retry-1.log").readText().contains("owned command completed"))
    }

    @Test fun timeoutTerminatesTheOwnedProcessAndCannotBecomeARetrySuccess() = owned { root ->
        assertThrows(IllegalStateException::class.java) {
            NativeBuildCommand(root, Duration.ofSeconds(2)).run("rust", root, command(root, "sleep"), true)
        }
        val pid = root.resolve("rust.log").readLines().first { it.startsWith("owned_pid=") }.substringAfter('=').toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals("1", root.resolve("calls").readText())
        assertTrue(root.resolve("rust.attempts.json").readText().contains("\"timed_out\":true"))
        assertFalse(root.resolve("rust.retry-1.log").exists())
    }
}
