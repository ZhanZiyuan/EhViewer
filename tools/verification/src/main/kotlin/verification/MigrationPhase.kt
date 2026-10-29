package verification

import kotlin.io.path.readText

private val phaseJobs = setOf("android", "ios", "rust_host", "desktop_intel", "desktop_intel_native", "desktop_linux_arm64", "windows_native_core", "source_policy")

/** Reviewed M2 coverage. A declared CI surface is not an executed build or asset receipt. */
fun migrationPhaseContract(workflow: String, sourceSha: String, settings: String): Map<String, Any?> {
    require(Regex("[0-9a-f]{40}").matches(sourceSha) && sourceSha != "0".repeat(40)) { "Expected a nonzero full source SHA" }
    val delivery = releaseContract(settings, sourceSha)
    require(delivery["version"] == "1.15.10" && delivery["android_version_code"] == 180074) { "The reviewed M2 version remains 1.15.10 / 180074; an M8 release freeze requires separate acceptance" }
    val jobs = Regex("(?m)^  ([a-z_][a-z0-9_]*):[ \\t]*$").findAll(workflow.substringAfter("jobs:\n", "")).map { it.groupValues[1] }.toList()
    require(jobs.size == jobs.toSet().size && jobs.toSet() == phaseJobs + "gate") { "CI jobs differ from the reviewed M2 phase; update coverage and validation independently" }
    val gate = workflow.substringAfter("  gate:\n", "")
    require(Regex("(?m)^    if: always\\(\\)[ \\t]*$").containsMatchIn(gate)) { "The phase gate must always run" }
    val needs = Regex("(?m)^    needs: \\[([^]\\n]+)]$").findAll(gate).toList()
    require(needs.size == 1) { "Expected one explicit aggregate dependency list" }
    val required = needs.single().groupValues[1].split(',').map(String::trim)
    require(required.size == required.toSet().size && required.toSet() == phaseJobs) { "Every declared M2 job must remain mandatory" }
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
        "windows-arm64" to "APPLICATION_IMAGE_SPIKE",
    )
    require(surfaces.keys == releaseTargets.map { it.id }.toSet()) { "Every final delivery target needs explicit phase coverage" }
    return mapOf(
        "schema_version" to 1,
        "milestone" to "M2",
        "milestone_complete" to false,
        "verification_scope" to "PHASE_DECLARATIONS_ONLY",
        "source_commit" to sourceSha,
        "version" to delivery["version"],
        "android_version_code" to delivery["android_version_code"],
        "mandatory_job_ids" to phaseJobs.sorted(),
        "shared_product_core_targets" to listOf("android"),
        "pending_check_categories" to mapOf(
            "ci/shared-kmp" to "Dedicated phase-aware shared compilation/resource checks remain to be integrated in M2 before M3",
            "ci/release-dry-run" to "Actual installed-target secret-free packaging aggregation remains to be integrated in M2; all eleven final assets are required in M6-M8",
        ),
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
    // Keep the original strict Android-only core guard until a separately verified M3 migration.
    val architecture = verifyArchitecture()
    require(architecture["shared_core_android_only"] == true && architecture["result"] == "PASS")
    return migrationPhaseContract(root.resolve(".github/workflows/ci.yml").readText(), source, root.resolve("app/build.gradle.kts").readText()) + mapOf(
        "source_dirty" to dirty,
        "retained_architecture_checks" to architecture,
    )
}
