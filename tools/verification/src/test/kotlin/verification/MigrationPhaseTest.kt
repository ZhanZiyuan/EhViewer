package verification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MigrationPhaseTest {
    private val workflow = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(".github/workflows/ci.yml") }.first(Files::isRegularFile).readText()
    private val source = "eab4c8b53845438d66b7f37f93b57458a11a6fb9"
    private val settings = "val releaseVersion = \"1.15.10\"\nversionCode = 180074\n"

    @Test fun currentCoverageCannotClaimM2OrFinalAssetsAccepted() {
        val report = migrationPhaseContract(workflow, source, settings)
        assertEquals("M2", report["milestone"])
        for (key in listOf("milestone_complete", "artifacts_verified", "product_p0_verified", "publish_enabled")) assertEquals(key, false, report[key])
        assertEquals(listOf("android"), report["shared_product_core_targets"])
        assertEquals(setOf("ci/shared-kmp", "ci/release-dry-run"), report["pending_check_categories"].obj().keys)
        val targets = report["target_coverage"].array().map { it.obj() }
        assertEquals(11, targets.size)
        assertEquals(releaseTargets.map { it.id }.toSet(), targets.map { it.str("id") }.toSet())
        targets.forEach { assertEquals("NOT_VERIFIED", it["final_delivery_acceptance"]) }
        assertEquals("NOT_INTEGRATED", targets.single { it["id"] == "windows-x64" }["available_ci_surface"])
        for (id in listOf("macos-arm64", "linux-x64")) assertEquals("HOST_JNI_ONLY", targets.single { it["id"] == id }["available_ci_surface"])
    }

    @Test fun missingExtraOrDuplicatedJobsCannotSilentlyChangePhase() {
        for (changed in listOf(workflow.replace("  ios:\n", "  hidden_ios:\n"), workflow + "\n  future_target:\n", workflow + "\n  source_policy:\n", workflow.substringBefore("jobs:\n"))) {
            assertThrows(IllegalArgumentException::class.java) { migrationPhaseContract(changed, source, settings) }
        }
    }

    @Test fun eachMissingOrDuplicateDependencyAndConditionalGateFails() {
        val dependencies = listOf("android", "ios", "rust_host", "desktop_intel", "desktop_intel_native", "desktop_linux_arm64", "windows_native_core", "source_policy")
        val original = "needs: [${dependencies.joinToString(", ")}]"
        for (removed in dependencies) {
            val changed = workflow.replace(original, "needs: [${dependencies.filter { it != removed }.joinToString(", ")}]")
            assertThrows(IllegalArgumentException::class.java) { migrationPhaseContract(changed, source, settings) }
        }
        for (changed in listOf(workflow.replace(original, "needs: [${(dependencies + "ios").joinToString(", ")}]"), workflow.replace("name: ci/gate\n    if: always()", "name: ci/gate\n    if: success()"))) {
            assertThrows(IllegalArgumentException::class.java) { migrationPhaseContract(changed, source, settings) }
        }
    }

    @Test fun invalidSourceCannotProducePhaseEvidence() {
        for (sha in listOf("0".repeat(40), "eab4c8b53", "F".repeat(40), "1".repeat(39))) {
            assertThrows(IllegalArgumentException::class.java) { migrationPhaseContract(workflow, sha, settings) }
        }
    }

    @Test fun extraValidOrQuotedJobHeadersCannotHideFromPhase() {
        for (header in listOf("future-target:", "FutureTarget:", "\"future_target\":", "'future_target':", "future_target: # undeclared platform")) {
            val added = "\n  $header\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo phase-contract-fixture\n"
            assertThrows(header, IllegalArgumentException::class.java) { migrationPhaseContract(workflow + added, source, settings) }
        }
    }

    @Test fun commentsOnReviewedJobHeadersPreserveCoverage() {
        val changed = workflow.replace("jobs:\n", "jobs: # current phase\n  # Reviewed mandatory jobs\n")
            .replace("  source_policy:\n", "  source_policy: # source and phase evidence\n")
            .replace("  gate:\n", "  gate: # always required\n")
        assertEquals(migrationPhaseContract(workflow, source, settings), migrationPhaseContract(changed, source, settings))
    }

    @Test fun prematureVersionChangesAndAmbiguousAuthorityFail() {
        for (changed in listOf(settings.replace("1.15.10", "1.16.0"), settings.replace("180074", "180075"), settings.replace("180074", "0"), settings + settings, "")) {
            assertThrows(IllegalArgumentException::class.java) { migrationPhaseContract(workflow, source, changed) }
        }
    }
}
