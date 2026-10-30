package verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Phase coverage is independent of YAML layout; execution requirements live in CiPlanTest. */
class MigrationPhaseTest {
    private val source = "eab4c8b53845438d66b7f37f93b57458a11a6fb9"
    private val settings = "val releaseVersion = \"1.15.10\"\nversionCode = 180074\n"
    private fun report() = migrationPhaseContract(source, settings)

    @Test fun currentCoverageCannotClaimM3OrFinalAssetsAccepted() {
        val report = report()
        assertEquals("M3", report["milestone"])
        for (key in listOf("milestone_complete", "artifacts_verified", "product_p0_verified", "publish_enabled")) assertEquals(key, false, report[key])
    }

    @Test fun retainedModuleCoverageIsExplicit() {
        val report = report()
        for (module in listOf("domain", "common", "network", "shared-ui", "data")) assertEquals(listOf("android", "jvm", "iosArm64", "iosSimulatorArm64"), report["shared_module_targets"].obj()[module])
        for (module in listOf("i18n", "ui")) assertEquals(listOf("android"), report["shared_module_targets"].obj()[module])
    }

    @Test fun executablePhaseGateMatchesActualMigratedDataArchitecture() {
        val report = verifyMigrationPhase()
        val architecture = report["retained_architecture_checks"].obj()
        assertEquals("PASS", architecture["result"])
        assertEquals(portableDomainTargets, architecture["portable_data_targets"])
        assertEquals(architecture["portable_data_targets"], report["shared_module_targets"].obj()["data"])
        assertEquals(listOf("i18n", "ui"), architecture["legacy_shared_modules"])
        assertEquals(false, report["milestone_complete"])
    }

    @Test fun nativeAndJvmExecutionSurfacesRemainRecorded() {
        val report = report()
        assertEquals(listOf("desktop_intel_native", "desktop_linux_arm64", "rust_host", "windows_native_core"), report["common_jvm_evidence_jobs"])
        assertEquals(listOf("iosArm64", "iosSimulatorArm64"), report["domain_ios_targets"])
        assertEquals("ios", report["domain_ios_evidence_job"])
    }

    @Test fun searchIntegrationDoesNotClaimCompleteProductAcceptance() {
        for (key in listOf("ios_product_domain_integration", "desktop_product_domain_integration")) assertEquals("GALLERY_SEARCH", report()[key])
        assertEquals(false, report()["product_p0_verified"])
    }

    @Test fun everyDeliveryTargetRetainsItsActualCurrentScope() {
        val targets = report()["target_coverage"].array().map(Any?::obj)
        assertEquals(11, targets.size)
        assertEquals(releaseTargets.map { it.id }.toSet(), targets.map { it.str("id") }.toSet())
        targets.forEach { assertEquals("NOT_VERIFIED", it["final_delivery_acceptance"]) }
        assertEquals("NOT_INTEGRATED", targets.single { it["id"] == "windows-x64" }["available_ci_surface"])
        for (id in listOf("macos-arm64", "linux-x64")) assertEquals("HOST_JNI_ONLY", targets.single { it["id"] == id }["available_ci_surface"])
    }

    @Test fun onlySixCandidatesAreCurrentlyIntegrated() {
        assertEquals(6, report()["staged_candidate_targets"].array().size)
        assertEquals(5, report()["remaining_final_package_targets"].array().size)
    }

    @Test fun invalidSourceCannotProducePhaseEvidence() {
        for (sha in listOf("0".repeat(40), "eab4c8b53", "F".repeat(40), "1".repeat(39))) {
            assertThrows(IllegalArgumentException::class.java) { migrationPhaseContract(sha, settings) }
        }
    }

    @Test fun prematureVersionChangesAndAmbiguousAuthorityFail() {
        for (changed in listOf(settings.replace("1.15.10", "1.16.0"), settings.replace("180074", "180075"), settings.replace("180074", "0"), settings + settings, "")) {
            assertThrows(IllegalArgumentException::class.java) { migrationPhaseContract(source, changed) }
        }
    }

    @Test fun stableAndroidRegressionsAreDeclaredWithoutDependingOnJobOrder() {
        assertEquals(".github/ci-plan.json", report()["ci_policy"])
        assertEquals(listOf("android", "shared_kmp", "source_policy"), report()["always_required_jobs"])
    }
}
