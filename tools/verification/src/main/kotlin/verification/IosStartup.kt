package verification

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun latestIosRuntime(runtimes: List<Any?>, identifier: String? = null): Map<String, Any?> = runtimes.map(Any?::obj)
    .filter { it["isAvailable"] == true && it.str("identifier").startsWith("com.apple.CoreSimulator.SimRuntime.iOS-") }
    .filter { identifier == null || it.str("identifier") == identifier }
    .maxByOrNull {
        val numbers = it.str("version").split('.').map(String::toInt)
        require(numbers.size in 1..3 && numbers.all { number -> number in 0..999 })
        (numbers + List(3 - numbers.size) { 0 }).fold(0L) { total, number -> total * 1000 + number }
    } ?: error("No available iOS Simulator runtime; startup has not been verified")

fun runtimeIphone(runtime: Map<String, Any?>): Map<String, Any?> = runtime["supportedDeviceTypes"].array()
    .map(Any?::obj).firstOrNull { it["productFamily"] == "iPhone" }
    ?: error("Selected iOS runtime has no supported iPhone device type")

fun iosLaunchPid(text: String, bundle: String): Long = Regex("^${Regex.escape(bundle)}: ([1-9][0-9]*)$", RegexOption.MULTILINE)
    .find(text)?.groupValues?.get(1)?.toLong() ?: error("Simulator did not report the launched app PID: $text")

fun rustInteropResults(stderr: String): Int {
    val marker = Regex("EhViewerM1:rust-ffi-ready completed=([0-9]+) live=([0-9]+)").findAll(stderr).toList().singleOrNull()
        ?: error("Expected exactly one Rust interop result for this process")
    val completed = marker.groupValues[1].toInt()
    require(completed >= 2000 && marker.groupValues[2].toLong() == 0L) { "Rust interop probe did not complete or leaked owned results" }
    return completed
}

/** Launch a real app in an owned Simulator. Never boot, erase or reuse a user's device. */
fun iosStartup(app: Path, output: Path, runtimeIdentifier: String? = null): Map<String, Any?> {
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
    require(plist("CADisableMinimumFrameDurationOnPhone") == "true") { "Compose requires CADisableMinimumFrameDurationOnPhone=true" }
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI startup requires a clean source tree" }
    val runtime = latestIosRuntime(JsonSlurper().parseText(command("xcrun", "simctl", "list", "runtimes", "-j")).obj()["runtimes"].array(), runtimeIdentifier)
    val type = runtimeIphone(runtime)
    output.createDirectories()
    val device = command("xcrun", "simctl", "create", "EhViewer-M1-${UUID.randomUUID()}", type.str("identifier"), runtime.str("identifier")).trim()
    require(Regex("[A-Fa-f0-9-]{36}").matches(device)) { "Invalid Simulator identifier" }
    var captureDirectory: Path? = null
    val captures = mutableListOf<Path>()
    fun phase(name: String) = Files.writeString(output.resolve("phases.log"), "${java.time.Instant.now()} $name\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    fun logs() = command("xcrun", "simctl", "spawn", device, "log", "show", "--style", "compact", "--last", "1h", "--predicate", "process == \"EhViewer\" OR eventMessage CONTAINS \"$bundle\"", timeout = Duration.ofSeconds(30))
    fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    fun awaitMarkers(stage: String, pid: Long, stderr: Path, vararg markers: Pair<String, Int>) {
        phase(stage)
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        while (System.nanoTime() < deadline) {
            check(alive(pid)) { "$stage: app PID $pid exited unexpectedly; see captured stderr and lifecycle log" }
            val text = stderr.readText()
            if (markers.all { (marker, count) -> text.split("EhViewerM1:$marker").size - 1 >= count }) return
            Thread.sleep(500)
        }
        error("$stage: UIKit/Compose lifecycle markers missing for PID $pid: ${markers.toList()}")
    }
    fun launch(name: String): Pair<Long, Path> {
        phase(name)
        // launchd_sim must write inside this app's container, not the host's Documents directory.
        val stdout = requireNotNull(captureDirectory).resolve("$name.stdout.log")
        val stderr = requireNotNull(captureDirectory).resolve("$name.stderr.log")
        captures += listOf(stdout, stderr)
        stdout.writeText("")
        stderr.writeText("")
        val response = command("xcrun", "simctl", "launch", "--stdout=$stdout", "--stderr=$stderr", device, bundle)
        output.resolve("$name.launch.log").writeText(response)
        return iosLaunchPid(response, bundle) to stderr
    }
    try {
        output.resolve("environment.json").writeText(
            json(
                mapOf(
                    "source_commit" to command("git", "rev-parse", "HEAD").trim(),
                    "source_dirty" to dirty,
                    "runtime" to runtime,
                    "device_type" to type,
                    "owned_simulator" to device,
                    "xcode" to command("xcodebuild", "-version").trim(),
                ),
            ),
        )
        phase("boot")
        command("xcrun", "simctl", "boot", device)
        command("xcrun", "simctl", "bootstatus", device, "-b", timeout = Duration.ofMinutes(5))
        command("xcrun", "simctl", "install", device, app)
        captureDirectory = Path.of(command("xcrun", "simctl", "get_app_container", device, bundle, "data").trim()).resolve("tmp")
        val (firstPid, firstStderr) = launch("first-cold-start")
        awaitMarkers("first-render", firstPid, firstStderr, "cold-start" to 1, "rust-ffi-ready" to 1, "compose-ready" to 1, "active" to 1)
        command("xcrun", "simctl", "io", device, "screenshot", output.resolve("startup.png"))
        require(output.resolve("startup.png").fileSize() > 0)
        phase("open-settings")
        command("xcrun", "simctl", "launch", device, "com.apple.Preferences")
        awaitMarkers("background", firstPid, firstStderr, "background" to 1)
        phase("return-to-foreground")
        val foreground = command("xcrun", "simctl", "launch", device, bundle)
        output.resolve("foreground.launch.log").writeText(foreground)
        check(iosLaunchPid(foreground, bundle) == firstPid) { "App restarted instead of resuming the original PID $firstPid" }
        awaitMarkers("foreground", firstPid, firstStderr, "active" to 2)
        phase("terminate")
        command("xcrun", "simctl", "terminate", device, bundle)
        val exitDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (alive(firstPid) && System.nanoTime() < exitDeadline) Thread.sleep(100)
        check(!alive(firstPid)) { "Terminated app PID $firstPid is still alive" }
        val (secondPid, secondStderr) = launch("second-cold-start")
        check(secondPid != firstPid) { "Second cold start reused the terminated PID" }
        awaitMarkers("second-render", secondPid, secondStderr, "cold-start" to 1, "rust-ffi-ready" to 1, "compose-ready" to 1, "active" to 1)
        val rustResults = listOf(rustInteropResults(firstStderr.readText()), rustInteropResults(secondStderr.readText()))
        output.resolve("lifecycle.log").writeText(logs())
        val result = mapOf(
            "source_commit" to command("git", "rev-parse", "HEAD").trim(),
            "source_dirty" to dirty,
            "runtime" to runtime.str("name"), "device_type" to type.str("name"), "architecture" to "arm64",
            "bundle_id" to bundle, "version" to plist("CFBundleShortVersionString"), "build" to plist("CFBundleVersion"),
            "executable_sha256" to sha256(executable), "cold_start" to "PASS", "background_foreground" to "PASS",
            "first_pid" to firstPid, "second_pid" to secondPid, "explicit_termination" to "PASS",
            "compose_ready" to "PASS", "development_signed_device" to "NOT_VERIFIED",
            "rust_c_abi" to "PASS", "rust_completed_results_by_launch" to rustResults, "rust_live_results_after_probe" to 0,
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
            captures.filter(Path::exists).forEach { Files.copy(it, output.resolve(it.fileName)) }
        } finally {
            try {
                command("xcrun", "simctl", "shutdown", device)
            } finally {
                command("xcrun", "simctl", "delete", device)
            }
        }
    }
}
