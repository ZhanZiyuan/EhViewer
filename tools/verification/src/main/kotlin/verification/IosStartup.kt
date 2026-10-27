package verification

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

fun latestIosRuntime(runtimes: List<Any?>): Map<String, Any?> = runtimes.map(Any?::obj)
    .filter { it["isAvailable"] == true && it.str("identifier").startsWith("com.apple.CoreSimulator.SimRuntime.iOS-") }
    .maxByOrNull {
        val numbers = it.str("version").split('.').map(String::toInt)
        require(numbers.size in 1..3 && numbers.all { number -> number in 0..999 })
        (numbers + List(3 - numbers.size) { 0 }).fold(0L) { total, number -> total * 1000 + number }
    } ?: error("No available iOS Simulator runtime; startup has not been verified")

fun runtimeIphone(runtime: Map<String, Any?>): Map<String, Any?> = runtime["supportedDeviceTypes"].array()
    .map(Any?::obj).firstOrNull { it["productFamily"] == "iPhone" }
    ?: error("Selected iOS runtime has no supported iPhone device type")

/** Launch a real app in an owned Simulator. Never boot, erase or reuse a user's device. */
fun iosStartup(app: Path, output: Path): Map<String, Any?> {
    require(System.getProperty("os.name").startsWith("Mac") && command("uname", "-m").trim() == "arm64") { "M1 requires macOS ARM64" }
    require(Files.isDirectory(app) && !output.exists()) { "Expected an app bundle and a fresh report directory" }
    val executable = app.resolve("EhViewer")
    require(Files.isExecutable(executable)) { "App executable missing or not executable" }
    require(command("xcrun", "lipo", "-archs", executable).trim() == "arm64") { "Expected arm64 executable" }
    require("IOSSIMULATOR" in command("xcrun", "vtool", "-show-build", executable)) { "Expected Simulator Mach-O platform" }
    command("codesign", "--verify", "--strict", app)
    fun plist(key: String) = command("/usr/libexec/PlistBuddy", "-c", "Print :$key", app.resolve("Info.plist")).trim()
    val bundle = plist("CFBundleIdentifier")
    require(bundle == "moe.tarsin.ehviewer")
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI startup requires a clean source tree" }
    val runtime = latestIosRuntime(JsonSlurper().parseText(command("xcrun", "simctl", "list", "runtimes", "-j")).obj()["runtimes"].array())
    val type = runtimeIphone(runtime)
    val device = command("xcrun", "simctl", "create", "EhViewer-M1-${UUID.randomUUID()}", type.str("identifier"), runtime.str("identifier")).trim()
    require(Regex("[A-Fa-f0-9-]{36}").matches(device)) { "Invalid Simulator identifier" }
    output.createDirectories()
    fun logs() = command("xcrun", "simctl", "spawn", device, "log", "show", "--style", "compact", "--last", "5m", "--predicate", "process == \"EhViewer\"", timeout = Duration.ofSeconds(30))
    fun awaitMarkers(vararg markers: Pair<String, Int>): String {
        repeat(30) {
            val text = logs()
            if (markers.all { (marker, count) -> text.split(marker).size - 1 >= count }) return text
            Thread.sleep(500)
        }
        error("UIKit/Compose lifecycle markers missing; app startup failed")
    }
    try {
        command("xcrun", "simctl", "boot", device)
        command("xcrun", "simctl", "bootstatus", device, "-b", timeout = Duration.ofMinutes(5))
        command("xcrun", "simctl", "install", device, app)
        command("xcrun", "simctl", "launch", device, bundle)
        awaitMarkers("EhViewerM1:compose-ready" to 1, "EhViewerM1:active" to 1)
        command("xcrun", "simctl", "io", device, "screenshot", output.resolve("startup.png"))
        require(output.resolve("startup.png").fileSize() > 0)
        command("xcrun", "simctl", "launch", device, "com.apple.Preferences")
        awaitMarkers("EhViewerM1:background" to 1)
        command("xcrun", "simctl", "launch", device, bundle)
        awaitMarkers("EhViewerM1:active" to 2)
        command("xcrun", "simctl", "terminate", device, bundle)
        command("xcrun", "simctl", "launch", device, bundle)
        val lifecycle = awaitMarkers("EhViewerM1:cold-start" to 2, "EhViewerM1:compose-ready" to 2)
        output.resolve("lifecycle.log").writeText(lifecycle)
        val result = mapOf(
            "source_commit" to command("git", "rev-parse", "HEAD").trim(),
            "source_dirty" to dirty,
            "runtime" to runtime.str("name"), "device_type" to type.str("name"), "architecture" to "arm64",
            "bundle_id" to bundle, "version" to plist("CFBundleShortVersionString"), "build" to plist("CFBundleVersion"),
            "executable_sha256" to sha256(executable), "cold_start" to "PASS", "background_foreground" to "PASS",
            "compose_ready" to "PASS", "development_signed_device" to "NOT_VERIFIED",
        )
        output.resolve("startup.json").writeText(json(result))
        return result
    } catch (failure: Throwable) {
        output.resolve("failure.txt").writeText(failure.toString())
        try {
            output.resolve("failed-lifecycle.log").writeText(logs())
        } catch (captureFailure: Throwable) {
            failure.addSuppressed(captureFailure)
        }
        throw failure
    } finally {
        try {
            command("xcrun", "simctl", "shutdown", device)
        } finally {
            command("xcrun", "simctl", "delete", device)
        }
    }
}
