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
            .substringAfter("        run: |\n").substringBefore("\n      - name:").trimIndent()
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
        assertTrue(ci.contains("name: ci/gate\n    if: always()\n    needs: [android, ios, rust_host, desktop_intel, desktop_intel_native, desktop_linux_arm64, windows_native_core, source_policy]"))
        assertTrue(ci.contains("run: test \"${'$'}ANDROID_RESULT\" = success"))
    }

    @Test fun gateRejectsSkippedFailedAndIncompleteChecks() {
        val script = workflow("ci").substringAfter("  gate:\n").substringAfter("        run: ").trim()
        for (target in listOf("ANDROID_RESULT", "IOS_RESULT", "RUST_HOST_RESULT", "INTEL_DMG_RESULT", "INTEL_NATIVE_RESULT", "LINUX_ARM64_RESULT", "WINDOWS_CORE_RESULT", "SOURCE_POLICY_RESULT")) {
            for (result in listOf("success", "failure", "cancelled", "skipped", "neutral", "pending", "")) {
                val process = ProcessBuilder("bash", "-c", script).apply {
                    environment()["ANDROID_RESULT"] = "success"
                    environment()["IOS_RESULT"] = "success"
                    environment()["RUST_HOST_RESULT"] = "success"
                    environment()["INTEL_DMG_RESULT"] = "success"
                    environment()["INTEL_NATIVE_RESULT"] = "success"
                    environment()["LINUX_ARM64_RESULT"] = "success"
                    environment()["WINDOWS_CORE_RESULT"] = "success"
                    environment()["SOURCE_POLICY_RESULT"] = "success"
                    environment()[target] = result
                }.start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor()
                    error("Gate regression timed out: $result")
                }
                assertEquals(result, result == "success", process.exitValue() == 0)
            }
        }
    }

    @Test fun linuxBuildLogCapturePreservesGradleFailure() {
        val script = workflow("ci").substringAfter("      - name: Build real AArch64 JVM, Skiko and Rust application image\n")
            .substringAfter("        run: |\n").substringBefore("\n      - name:").trimIndent()
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
