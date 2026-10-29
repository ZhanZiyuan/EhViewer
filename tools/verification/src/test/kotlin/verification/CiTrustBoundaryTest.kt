package verification

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CiTrustBoundaryTest {
    private fun workflow(name: String): String = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(".github/workflows/$name.yml") }.first(Files::isRegularFile).readText()

    @Test fun featureAndPrSnapshotsNeedNoProductionKey() {
        val script = workflow("ci").substringAfter("      - name: Prepare snapshot APKs\n")
            .substringAfter("        run: |\n").substringBefore("\n      - ").trimIndent()
        val events = listOf(
            "refs/heads/feature/kmp" to "push",
            "refs/heads/feature/kmp" to "workflow_dispatch",
            "refs/pull/15/merge" to "pull_request",
            "refs/heads/main" to "pull_request",
            "refs/heads/main" to "push",
            "refs/heads/main" to "workflow_dispatch",
        )
        for ((ref, event) in events) {
            temporary { temp ->
                // The generated executable is a test fixture that records verifier arguments.
                // It fails if the workflow tries to encrypt or retain production diagnostics.
                val verifier = temp.resolve("tools/verification/build/install/verification/bin/verification")
                Files.createDirectories(verifier.parent)
                verifier.writeText("#!/bin/sh\n[ \"${'$'}1\" = prepare-snapshot ] || exit 93\nprintf '%s\\n' \"${'$'}@\"\n")
                check(verifier.toFile().setExecutable(true, true))
                Files.createDirectories(temp.resolve("reports"))
                val capture = temp.resolve("log")
                val process = ProcessBuilder("bash", "-e", "-c", script).directory(temp.toFile())
                    .redirectErrorStream(true).redirectOutput(capture.toFile()).apply {
                        environment().apply {
                            this["GITHUB_REF"] = ref
                            this["GITHUB_EVENT_NAME"] = event
                            this["PUBLIC_KEY"] = ""
                            this["ANDROID_HOME"] = temp.resolve("sdk").toString()
                            this["RUNNER_TEMP"] = temp.toString()
                        }
                    }.start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor()
                    error("Snapshot trust regression timed out: $ref / $event")
                }
                val trusted = ref == "refs/heads/main" && event in setOf("push", "workflow_dispatch")
                if (trusted) {
                    assertTrue(capture.readText(), process.exitValue() != 0)
                    assertTrue(capture.readText().contains("encryption key is required"))
                } else {
                    assertEquals(capture.readText(), 0, process.exitValue())
                    val args = temp.resolve("reports/snapshot.json").readText().lines()
                    assertTrue("$ref / $event", "--test-signing" in args)
                    assertFalse("--private-dir" in args)
                }
            }
        }
    }

    @Test fun productionStepsAndReleaseEntryAreMainOnly() {
        val ci = workflow("ci")
        for (step in listOf("Restore original signing for trusted builds", "Retain encrypted snapshot diagnostics")) {
            val condition = ci.substringAfter("      - name: $step\n").substringBefore("        env:")
                .substringBefore("        uses:")
            assertTrue(step, condition.contains("github.ref == 'refs/heads/main'"))
            assertTrue(step, condition.contains("github.event_name == 'push' || github.event_name == 'workflow_dispatch'"))
        }
        assertTrue(workflow("releases").contains("(github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main') ||"))
        assertTrue(ci.contains("cancel-in-progress: ${'$'}{{ github.ref != 'refs/heads/feature/kmp' && github.head_ref != 'feature/kmp' }}"))
        val gate = ci.substringAfter("  gate:\n")
        assertTrue(gate.contains("name: ci/gate"))
        assertTrue(gate.contains("if: always()"))
        assertTrue(ci.contains("verification/bin/verification ci-gate"))
    }

    @Test fun gateRejectsSkippedFailedAndIncompleteChecks() {
        val policy = readJson(generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve(".github/ci-plan.json") }.first(Files::isRegularFile))
        val sha = "1".repeat(40)
        val plan = ciPlan(policy, listOf(".github/workflows/ci.yml"), sha, sha, sha, true)
        val actual = plan["jobs"].obj().mapValues { mapOf("result" to "success") }
        assertEquals("PASS", ciGate(plan, actual, sha)["result"])
        for (target in actual.keys) {
            for (result in listOf("failure", "cancelled", "skipped", "neutral", "pending", "")) {
                assertTrue("$target/$result", runCatching { ciGate(plan, actual + (target to mapOf("result" to result)), sha) }.isFailure)
            }
            assertTrue(runCatching { ciGate(plan, actual - target, sha) }.isFailure)
        }
    }

    @Test fun linuxBuildLogCapturePreservesGradleFailure() {
        val script = workflow("ci").substringAfter("      - name: Build real AArch64 JVM, Skiko and Rust application image\n")
            .substringAfter("        run: |\n").substringBefore("\n      - ").trimIndent()
        for (result in listOf(0, 93)) {
            temporary { temp ->
                // Generated failure fixture, never used by the actual CI build.
                val gradle = temp.resolve("gradlew")
                gradle.writeText("#!/bin/sh\nprintf 'measured-build-output\\n' >&2\nexit $result\n")
                check(gradle.toFile().setExecutable(true, true))
                val process = ProcessBuilder("bash", "-c", script).directory(temp.toFile())
                    .redirectErrorStream(true).redirectOutput(temp.resolve("driver.log").toFile()).start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor()
                    error("Linux build capture regression timed out")
                }
                assertEquals(temp.resolve("driver.log").readText(), result, process.exitValue())
                assertTrue(temp.resolve("reports/desktop-linux-build.log").readText().contains("measured-build-output"))
            }
        }
    }
}
