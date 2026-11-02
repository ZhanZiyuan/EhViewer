package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun nativeReleasePartPlan(manifests: Map<String, Map<String, Any?>>, source: String, version: String, code: Int): Map<String, String> {
    require(manifests.keys == nativeReleaseRoles.keys) { "Expected exactly seven native matrix parts" }
    return manifests.flatMap { (targetId, manifest) ->
        require(manifest["result"] == "PASS" && manifest["source_commit"] == source && manifest["source_dirty"] == false) { "Native part is not clean successful same-source evidence" }
        require(manifest["target"] == targetId && manifest["version"] == version && manifest["android_version_code"] == code) { "Native part CPU/version differs" }
        require(manifest["roles"].array() == nativeReleaseRoles.getValue(targetId) && manifest["runner_architecture"] == releaseTargets.single { it.id == targetId }.runnerArchitecture) { "Native part runner or evidence roles differ" }
        nativeReleaseRoles.getValue(targetId).map { it to targetId }
    }.toMap().also { require(it.size == nativeReleaseRoles.values.sumOf { it.size }) { "Duplicate native evidence role" } }
}

/** Android retains its original inspector; native packages use the same strict checks as Full PR CI. */
fun combinePlatformRelease(parts: Path, intelConsumer: Path, tools: AndroidTools, output: Path, tag: String, publicTestDryRun: Boolean = false, localComponentTest: Boolean = false): Map<String, Any?> = temporary { temporary ->
    if (publicTestDryRun) requirePublicReleaseDryRun(System.getenv(), localComponentTest)
    val source = command("git", "rev-parse", "HEAD").trim()
    require(command("git", "status", "--porcelain", "--untracked-files=all").isBlank()) { "Aggregation source is not clean" }
    val contract = releaseContract(root.resolve("app/build.gradle.kts").readText(), source, tag)
    val version = contract.str("version")
    val code = (contract["android_version_code"] as Number).toInt()
    val androidNames = (abis.keys + "universal").map { "release-part-$it" }.toSet()
    val nativeNames = nativeReleaseRoles.keys.map { "release-part-$it" }.toSet()
    require(parts.listDirectoryEntries().map { it.name }.toSet() == androidNames + nativeNames) { "Expected exactly eleven matrix artifacts" }
    val manifests = nativeReleaseRoles.keys.associateWith { readJson(parts.resolve("release-part-$it/native-part.json")) }
    val roles = nativeReleasePartPlan(manifests, source, version, code)
    val android = temporary.resolve("android-parts").createDirectories()
    androidNames.forEach { name -> copyDesktopImage(parts.resolve(name), android.resolve(name)) }
    val destination = output.toAbsolutePath().normalize()
    require(!destination.exists(NOFOLLOW_LINKS)) { "Refusing to overwrite a Release payload" }
    destination.parent.createDirectories()
    val staging = Files.createTempDirectory(destination.parent, ".platform-release-")
    try {
        val result = combineRelease(android, tools, staging.resolve("assets"), tag, publicTestDryRun, localComponentTest)
        require(result["versionCode"] == code) { "Android versionCode differs from the common release contract" }
        val attachments = result["attachments"].array().map(Any?::obj).toMutableList()
        fun path(role: String, name: String): Path {
            val directory = if (role == "intel-consumer") intelConsumer else parts.resolve("release-part-${roles.getValue(role)}/evidence/$role")
            val candidate = directory.resolve(name).normalize()
            require(candidate.toRealPath().startsWith(directory.toRealPath())) { "Native receipt path escapes its part" }
            return candidate
        }
        val native = verifyNativeCandidatePackages(source, version, code, ::path) { id, file, digest ->
            val target = releaseTargets.single { it.id == id }
            val name = target.fileName(version)
            require(attachments.none { it["name"] == name } && file.isRegularFile(NOFOLLOW_LINKS)) { "Duplicated/missing native installation asset" }
            val retained = staging.resolve("assets/$name")
            Files.copy(file, retained)
            require(sha256(retained) == digest) { "Staging changed native package bytes" }
            attachments += mapOf("name" to name, "target" to id, "architecture" to target.architecture, "format" to target.format, "sha256" to digest, "bytes" to Files.size(retained))
        }
        verifyReleaseAssetNames(version, attachments.map { it.str("name") })
        require(staging.resolve("assets").listDirectoryEntries().map { it.name }.toSet() == attachments.map { it.str("name") }.toSet()) { "Unexpected Release payload file" }
        val manifest = result + mapOf(
            "schema_version" to 2, "attachments" to attachments.sortedBy { it.str("name") }, "native_package_checks" to native,
            "all_eleven_package_bytes_verified" to true, "native_execution_in_package_job" to "NOT_EXECUTED",
            "same_source_native_producer_and_intel_consumer_receipts_verified" to true,
            "native_parts" to nativeReleaseRoles.keys.associateWith { mapOf("manifest_sha256" to sha256(parts.resolve("release-part-$it/native-part.json")), "target" to it) },
            "intel_consumer_receipt_sha256" to sha256(intelConsumer.resolve("intel-dmg.json")),
            "product_p0_verified" to false, "minimum_systems" to "NOT_VERIFIED", "complete_license_sbom" to "NOT_VERIFIED", "development_signed_ios_device" to "NOT_VERIFIED",
        )
        staging.resolve("release-manifest.json").writeText(json(manifest))
        Files.move(staging, destination)
        manifest
    } finally {
        if (staging.exists(NOFOLLOW_LINKS)) Files.walk(staging).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
