package verification

import kotlin.io.path.readText

/** Reviewed first M3 migration. A declared surface is not an executed artifact receipt. */
fun migrationPhaseContract(sourceSha: String, settings: String): Map<String, Any?> {
    require(Regex("[0-9a-f]{40}").matches(sourceSha) && sourceSha != "0".repeat(40)) { "Expected a nonzero full source SHA" }
    val delivery = releaseContract(settings, sourceSha)
    require(delivery["version"] == "1.15.10" && delivery["android_version_code"] == 180074) { "The reviewed M3 version remains 1.15.10 / 180074; an M8 release freeze requires separate acceptance" }
    val surfaces = mapOf(
        "android-arm64" to "TEST_SIGNED_APK_PREFLIGHT",
        "android-armv7" to "TEST_SIGNED_APK_PREFLIGHT",
        "android-universal" to "TEST_SIGNED_APK_PREFLIGHT",
        "android-x64" to "TEST_SIGNED_APK_PREFLIGHT",
        "ios-arm64" to "DEVICE_BUILD_AND_ARM64_SIMULATOR_SPIKE",
        "macos-x64" to "ARM_PRODUCED_DMG_ROSETTA_AND_INTEL_CONSUMPTION_SPIKE",
        "macos-arm64" to "HOST_JNI_ONLY",
        "linux-x64" to "HOST_JNI_ONLY",
        "linux-arm64" to "APPLICATION_IMAGE_SPIKE",
        "windows-x64" to "NOT_INTEGRATED",
        "windows-arm64" to "SOURCE_BOUND_ZIP_AND_EXTRACTED_NATIVE_LAUNCH_SPIKE",
    )
    require(surfaces.keys == releaseTargets.map { it.id }.toSet()) { "Every final delivery target needs explicit phase coverage" }
    return mapOf(
        "schema_version" to 1,
        "milestone" to "M3",
        "milestone_complete" to false,
        "verification_scope" to "PHASE_DECLARATIONS_ONLY",
        "source_commit" to sourceSha,
        "version" to delivery["version"],
        "android_version_code" to delivery["android_version_code"],
        "ci_policy" to ".github/ci-plan.json",
        "always_required_jobs" to listOf("android", "shared_kmp", "source_policy"),
        "shared_product_core_targets" to portableDomainTargets,
        "shared_module_targets" to mapOf("domain" to portableDomainTargets, "common" to portableCommonTargets, "network" to portableDomainTargets, "shared-ui" to portableDomainTargets, "data" to listOf("android"), "i18n" to listOf("android"), "ui" to listOf("android")),
        "common_jvm_evidence_jobs" to listOf("desktop_intel_native", "desktop_linux_arm64", "rust_host", "windows_native_core"),
        "domain_ios_targets" to domainIosTargets,
        "domain_ios_evidence_job" to "ios",
        "ios_product_domain_integration" to "GALLERY_SEARCH",
        "desktop_product_domain_integration" to "GALLERY_SEARCH",
        "pending_check_categories" to emptyMap<String, String>(),
        "staged_candidate_targets" to listOf("android-arm64", "android-armv7", "android-universal", "android-x64", "macos-x64", "windows-arm64"),
        "remaining_final_package_targets" to listOf("ios-arm64", "macos-arm64", "linux-x64", "linux-arm64", "windows-x64"),
        "target_coverage" to releaseTargets.map {
            mapOf("id" to it.id, "available_ci_surface" to surfaces.getValue(it.id), "final_delivery_acceptance" to "NOT_VERIFIED")
        },
        "artifacts_verified" to false,
        "product_p0_verified" to false,
        "publish_enabled" to false,
    )
}

fun verifyMigrationPhase(): Map<String, Any?> {
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    // Common and domain retain their reviewed JVM/iOS targets.
    val architecture = verifyArchitecture()
    require(architecture["legacy_shared_core_android_only"] == true && architecture["portable_domain_targets"] == portableDomainTargets && architecture["result"] == "PASS")
    require(architecture["portable_common_targets"] == portableCommonTargets && architecture["legacy_shared_modules"] == listOf("data", "i18n", "ui"))
    return migrationPhaseContract(source, root.resolve("app/build.gradle.kts").readText()) + mapOf(
        "source_dirty" to dirty,
        "retained_architecture_checks" to architecture,
    )
}
