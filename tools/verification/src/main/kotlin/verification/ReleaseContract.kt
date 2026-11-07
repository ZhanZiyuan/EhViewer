package verification

import kotlin.io.path.readText

/** Delivery requirements only: a plan does not prove that any installation package exists. */
data class ReleaseTarget(
    val id: String,
    val platform: String,
    val architecture: String,
    val suffix: String,
    val runner: String,
    val runnerArchitecture: String,
    val format: String,
    val signing: String,
    val rosettaRequired: Boolean = false,
) {
    fun fileName(version: String): String = "EhViewer-$version-$suffix"
}

val releaseTargets: List<ReleaseTarget> = listOf(
    ReleaseTarget("android-arm64", "android", "arm64-v8a", "arm64-v8a.apk", "ubuntu-latest", "X64", "APK", "ORIGINAL_ANDROID_CERTIFICATE"),
    ReleaseTarget("android-armv7", "android", "armeabi-v7a", "armeabi-v7a.apk", "ubuntu-latest", "X64", "APK", "ORIGINAL_ANDROID_CERTIFICATE"),
    ReleaseTarget("android-universal", "android", "arm64-v8a+armeabi-v7a+x86_64", "universal.apk", "ubuntu-latest", "X64", "APK", "ORIGINAL_ANDROID_CERTIFICATE"),
    ReleaseTarget("android-x64", "android", "x86_64", "x86_64.apk", "ubuntu-latest", "X64", "APK", "ORIGINAL_ANDROID_CERTIFICATE"),
    ReleaseTarget("ios-arm64", "ios", "arm64", "arm64-unsigned.ipa", "macos-latest", "ARM64", "IPA_PAYLOAD", "UNSIGNED_NOT_DIRECTLY_INSTALLABLE"),
    ReleaseTarget("macos-x64", "macos", "x86_64", "macos-x86_64.dmg", "macos-latest", "ARM64", "UDIF_DMG", "UNSIGNED", rosettaRequired = true),
    ReleaseTarget("macos-arm64", "macos", "arm64", "macos-arm64.dmg", "macos-latest", "ARM64", "UDIF_DMG", "UNSIGNED"),
    ReleaseTarget("linux-x64", "linux", "x86_64", "linux-x86_64.AppImage", "ubuntu-latest", "X64", "APPIMAGE_WITH_RUNTIME", "UNSIGNED"),
    ReleaseTarget("linux-arm64", "linux", "aarch64", "linux-aarch64.AppImage", "ubuntu-24.04-arm", "ARM64", "APPIMAGE_WITH_RUNTIME", "UNSIGNED"),
    ReleaseTarget("windows-x64", "windows", "x86_64", "windows-x86_64.zip", "windows-latest", "X64", "ZIP_WITH_RUNTIME_AND_ENTRY", "UNSIGNED"),
    ReleaseTarget("windows-arm64", "windows", "arm64", "windows-arm64.zip", "windows-11-arm", "ARM64", "ZIP_WITH_RUNTIME_AND_ENTRY", "UNSIGNED"),
)

/** Name-set check only. Format, actual CPU, version, source and digest checks remain mandatory. */
fun verifyReleaseAssetNames(version: String, names: List<String>) {
    require(versionFromTag(version) == version) { "Expected a canonical version without a tag prefix" }
    val expected = releaseTargets.map { it.fileName(version) }
    require(expected.size == 11 && expected.toSet().size == 11) { "Invalid eleven-target contract" }
    require(names.size == 11 && names.toSet().size == 11 && names.toSet() == expected.toSet()) { "Expected exactly eleven root-level installation asset names; missing, duplicate, extra or nested assets are forbidden" }
}

fun releaseContract(settings: String, sourceSha: String, requestedTag: String? = null): Map<String, Any?> {
    require(Regex("[0-9a-f]{40}").matches(sourceSha) && sourceSha != "0".repeat(40)) { "Expected a nonzero full source SHA" }
    val versions = Regex("(?m)^val releaseVersion = \"([^\"]+)\"[ \\t]*$").findAll(settings).toList()
    val codes = Regex("(?m)^[ \\t]*versionCode = ([0-9]+)[ \\t]*$").findAll(settings).toList()
    require(versions.size == 1 && codes.size == 1) { "Expected one authoritative releaseVersion and Android versionCode" }
    val version = versions.single().groupValues[1]
    require(versionFromTag(version) == version) { "Expected a canonical source releaseVersion" }
    val code = codes.single().groupValues[1].toIntOrNull()
    require(code != null && code in 1..2_100_000_000) { "Invalid Android versionCode" }
    val tag = requestedTag ?: "v$version"
    require(versionFromTag(tag) == version) { "Requested version differs from authoritative source releaseVersion" }
    require(releaseTargets.map { it.id }.toSet().size == 11) { "Duplicate release target" }
    verifyReleaseAssetNames(version, releaseTargets.map { it.fileName(version) })
    return mapOf(
        "schema_version" to 1, "verification_scope" to "CONTRACT_ONLY", "artifacts_verified" to false, "publish_enabled" to false,
        "source_commit" to sourceSha, "version" to version, "android_version_code" to code, "planned_tag" to "v$version",
        "targets" to releaseTargets.map {
            mapOf(
                "id" to it.id, "platform" to it.platform, "architecture" to it.architecture, "file" to it.fileName(version),
                "runner" to it.runner, "runner_architecture" to it.runnerArchitecture, "format_required" to it.format,
                "signing_required" to it.signing, "rosetta_required" to it.rosettaRequired,
            )
        },
    )
}

fun verifyReleaseContract(requestedTag: String?): Map<String, Any?> {
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    return releaseContract(root.resolve("app/build.gradle.kts").readText(), source, requestedTag) + ("source_dirty" to dirty)
}
