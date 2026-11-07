package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

val nativeReleaseRoles = linkedMapOf(
    "ios-arm64" to listOf("ios"),
    "macos-x64" to listOf("intel-producer"),
    "macos-arm64" to listOf("macos-host"),
    "linux-x64" to listOf("linux-host"),
    "linux-arm64" to listOf("linux-runtime"),
    "windows-x64" to listOf("windows-x64-core", "windows-x64-jvm", "windows-x64-runtime", "windows-x64-package", "windows-x64-launch"),
    "windows-arm64" to listOf("windows-core", "windows-jvm", "windows-runtime", "windows-package", "windows-launch"),
)

/** Fresh native builds reuse the already exercised product packagers and mandatory real launches. */
fun buildNativeReleasePart(targetId: String, outputInput: Path): Map<String, Any?> {
    val target = releaseTargets.single { it.id == targetId && it.platform != "android" }
    val source = command("git", "rev-parse", "HEAD").trim()
    require(command("git", "status", "--porcelain", "--untracked-files=all").isBlank()) { "Release builds require a clean source tree" }
    require(System.getenv("RUNNER_ARCH") == target.runnerArchitecture) { "Unexpected native release runner CPU" }
    val output = outputInput.toAbsolutePath().normalize()
    require(!output.exists(NOFOLLOW_LINKS)) { "Use a fresh native Release part directory" }
    Files.createDirectories(output)
    fun role(name: String): Path {
        require(name in nativeReleaseRoles.getValue(targetId))
        return output.resolve("evidence/$name")
    }
    fun gradle(vararg tasks: String, heap: String = "2g") {
        try {
            command(root.resolve("gradlew"), *tasks, "--console=plain", "--no-daemon", "--max-workers=1", "-Dorg.gradle.parallel=false", "-Dorg.gradle.jvmargs=-Xmx$heap", timeout = Duration.ofMinutes(50)).also { output.resolve("gradle-build.log").writeText(it) }
        } catch (failure: CommandFailure) {
            output.resolve("gradle-build.log").writeText(failure.output)
            throw failure
        }
    }
    when (targetId) {
        "ios-arm64" -> {
            gradle(":app:ios:packageUnsignedIpa", ":app:ios:spotlessCheck", heap = "4g")
            val archive = filesBelow(root.resolve("app/ios/build/packages"), ".ipa").single()
            stageUnsignedIosIpa(archive, role("ios").resolve("unsigned-ipa"))
        }
        "macos-x64" -> {
            gradle(":app:desktop:packageDmg", ":app:desktop:spotlessCheck")
            desktopIntelDmg(root.resolve("app/desktop/build/compose/binaries/main/dmg"), role("intel-producer").resolve("reports/desktop-intel"))
            val destination = role("intel-producer").resolve("app/desktop/build/compose/binaries/main/dmg")
            Files.createDirectories(destination)
            Files.copy(filesBelow(root.resolve("app/desktop/build/compose/binaries/main/dmg"), ".dmg").single(), destination.resolve("EhViewer-${releaseContract(root.resolve("app/build.gradle.kts").readText(), source).str("version")}.dmg"))
        }
        "macos-arm64" -> {
            gradle("-p", "app/desktop", "packageDmg", "spotlessCheck", "-Pehviewer.desktopOnly=true")
            desktopArm64Dmg(root.resolve("app/desktop/build/compose/binaries/main/dmg"), role("macos-host").resolve("arm64-dmg"))
        }
        "linux-x64", "linux-arm64" -> {
            gradle("-p", "app/desktop", "createDistributable", "spotlessCheck", "-Pehviewer.desktopOnly=true")
            val image = root.resolve("app/desktop/build/compose/binaries/main/app/EhViewer")
            val arm64 = targetId == "linux-arm64"
            val evidence = if (arm64) role("linux-runtime").resolve("reports/desktop-linux-arm64") else role("linux-host").resolve("linux-image")
            if (arm64) desktopLinuxArm64(image, evidence) else desktopLinuxX64(image, evidence)
            linuxAppImage(image, evidence.resolve(if (arm64) "linux-arm64.json" else "linux-x64.json"), if (arm64) evidence.resolve("appimage") else role("linux-host").resolve("appimage"))
        }
        "windows-arm64", "windows-x64" -> {
            val platform = if (targetId == "windows-arm64") WindowsPlatform.ARM64 else WindowsPlatform.X64
            val prefix = if (platform == WindowsPlatform.ARM64) "windows" else "windows-x64"
            val core = role("$prefix-core")
            windowsNativeCore(core, platform)
            val jvm = role("$prefix-jvm")
            windowsJvmFfi(core.resolve("windows-native-core.json"), jvm)
            val runtime = role("$prefix-runtime")
            if (platform == WindowsPlatform.ARM64) {
                desktopWindowsArm64(jvm.resolve("windows-jvm-ffi.json"), core.resolve("windows-native-core.json"), runtime)
            } else {
                desktopWindowsX64(jvm.resolve("windows-jvm-ffi.json"), core.resolve("windows-native-core.json"), runtime)
            }
            val packaged = role("$prefix-package")
            val measured = runtime.resolve("windows-${platform.assetArchitecture}-runtime.json")
            windowsStageZip(measured, runtime.resolve("image/EhViewer"), packaged)
            windowsZipLaunch(packaged.resolve("windows-${platform.assetArchitecture}-package.json"), measured, role("$prefix-launch"))
        }
        else -> error("Unsupported native release target")
    }
    require(command("git", "status", "--porcelain", "--untracked-files=all").isBlank()) { "Native build changed tracked source" }
    val contract = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    val result = mapOf(
        "result" to "PASS", "source_commit" to source, "source_dirty" to false, "target" to targetId,
        "version" to contract["version"], "android_version_code" to contract["android_version_code"],
        "roles" to nativeReleaseRoles.getValue(targetId), "runner_architecture" to System.getenv("RUNNER_ARCH"),
        "ios_unsigned_installability" to "NOT_DIRECTLY_INSTALLABLE", "publish_enabled" to false,
    )
    output.resolve("native-part.json").writeText(json(result))
    return result
}
