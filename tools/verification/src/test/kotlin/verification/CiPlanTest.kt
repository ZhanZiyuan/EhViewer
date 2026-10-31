package verification

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CiPlanTest {
    private val policy = readJson(generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve(".github/ci-plan.json") }.first(Files::isRegularFile))
    private val sha = "1".repeat(40)
    private fun plan(vararg paths: String, full: Boolean = false) = ciPlan(policy, paths.toList(), sha, "2".repeat(40), "3".repeat(40), full)
    private fun required(plan: Map<String, Any?>) = plan["jobs"].obj().filterValues { it.obj()["required"] == true }.keys
    private fun results(plan: Map<String, Any?>) = plan["jobs"].obj().mapValues { (_, value) -> mapOf("result" to if (value.obj()["required"] == true) "success" else "skipped") }
    private fun rejects(block: () -> Unit) = assertNotNull(runCatching(block).exceptionOrNull())

    @Test fun androidAndSharedRegressionsAreAlwaysRequiredIncludingDocumentationChanges() {
        for (paths in listOf(arrayOf("README.md"), arrayOf("docs/kmp/notes.md"), arrayOf("app/src/main/java/Reader.kt"), emptyArray())) {
            val plan = plan(*paths)
            assertEquals("fast", plan["tier"])
            assertEquals(setOf("source_policy", "shared_kmp", "android"), required(plan))
            assertEquals("PASS", ciGate(plan, results(plan), sha)["result"])
        }
    }

    @Test fun portablePublicApiExpandsToEveryIntegratedPlatform() {
        for (name in listOf("core/common/src/commonMain/Files.kt", "core/domain/src/commonMain/Gallery.kt", "core/network/src/commonMain/Client.kt")) {
            val plan = plan(name)
            assertEquals("platform", plan["tier"])
            assertEquals(9, required(plan).size)
            assertFalse(required(plan).contains("release_dry_run"))
            assertEquals("PASS", ciGate(plan, results(plan), sha)["result"])
        }
    }

    @Test fun iosAndDesktopChangesIncludeAllRealProducerDependencies() {
        val iosPlan = plan("app/ios/src/iosMain/Main.kt")
        val ios = required(iosPlan)
        assertEquals(setOf("source_policy", "shared_kmp", "android", "ios"), ios)
        assertEquals("PASS", ciGate(iosPlan, results(iosPlan), sha)["result"])
        val desktop = required(plan("app/desktop/src/main/Main.kt"))
        assertTrue(desktop.containsAll(listOf("rust_host", "desktop_intel", "desktop_intel_native", "windows_native_core", "desktop_linux_arm64")))
    }

    @Test fun buildNativeReleaseAndPhaseCompletionAlwaysRequireFullMatrixAndDryRun() {
        for (name in listOf(".github/workflows/ci.yml", ".github/workflows/releases.yml", ".github/ci-plan.json", "native/rust/core/src/lib.rs", "core/common/build.gradle.kts", "gradle/libs.versions.toml", "build-logic/convention/Test.kt", "docs/kmp/milestones/M3.json", "CHANGELOG.md", "tools/verification/src/main/kotlin/verification/CiPlan.kt")) {
            val plan = plan(name)
            assertEquals(name, "full", plan["tier"])
            assertEquals(10, required(plan).size)
            assertEquals("PASS", ciGate(plan, results(plan), sha)["result"])
        }
    }

    @Test fun unknownPathsAndManualFullRequestsCannotNarrowChecks() {
        assertEquals("full", plan("unclassified/file")["tier"])
        assertEquals("full", plan("README.md", full = true)["tier"])
    }

    @Test fun missingFailedCancelledSkippedNeutralOrPendingRequiredResultsNeverPass() {
        for (plan in listOf(plan("README.md"), plan("app/ios/src/iosMain/Main.kt"), plan("core/common/src/Api.kt"), plan("native/rust/core/src/lib.rs"))) {
            val actual = results(plan)
            for (job in required(plan)) {
                rejects { ciGate(plan, actual - job, sha) }
                for (state in listOf("failure", "cancelled", "skipped", "neutral", "pending", "", "in_progress")) {
                    rejects { ciGate(plan, actual + (job to mapOf("result" to state)), sha) }
                }
            }
            rejects { ciGate(plan, actual + ("undeclared" to mapOf("result" to "success")), sha) }
        }
    }

    @Test fun unaffectedChecksMayOnlyBeSkippedOrActuallySuccessful() {
        val plan = plan("README.md")
        for (job in plan["jobs"].obj().keys - required(plan)) {
            assertEquals("PASS", ciGate(plan, results(plan) + (job to mapOf("result" to "success")), sha)["result"])
            for (state in listOf("failure", "cancelled", "neutral", "pending")) rejects { ciGate(plan, results(plan) + (job to mapOf("result" to state)), sha) }
        }
    }

    @Test fun wrongSourceOrWeakenedPlanCannotSatisfyTheGate() {
        val plan = plan("README.md")
        rejects { ciGate(plan, results(plan), "f".repeat(40)) }
        val jobs = plan["jobs"].obj()
        for (job in listOf("source_policy", "android", "shared_kmp")) {
            rejects { ciGate(plan + ("jobs" to (jobs + (job to (jobs[job].obj() + ("required" to false))))), results(plan), sha) }
        }
        rejects { ciGate(plan + ("tier" to "full"), results(plan), sha) }
    }

    @Test fun missingDuplicatedOrCyclicPolicyCannotAuthorizeSkipping() {
        for (bad in listOf(policy + ("fast_jobs" to listOf("source_policy")), policy + ("full_jobs" to policy["full_jobs"].array().dropLast(1)), policy + ("full_jobs" to (policy["full_jobs"].array() + "ios")), policy + ("dependencies" to mapOf("ios" to listOf("desktop_intel"), "desktop_intel" to listOf("ios"))))) {
            rejects { ciPlan(bad, listOf("app/ios/src/Main.kt"), sha, sha, sha, false) }
        }
    }

    @Test fun unsafeOrAmbiguousChangesCannotProduceAPlan() {
        for (paths in listOf(listOf("../secret"), listOf("/root"), listOf("core/../app"), listOf("README.md", "README.md"), listOf("bad\npath"))) rejects { ciPlan(policy, paths, sha, sha, sha, false) }
        rejects { ciPlan(policy, emptyList(), "0".repeat(40), sha, sha, false) }
    }
}
