package verification

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun latestIosRuntime(runtimes: List<Any?>, identifier: String? = null): Map<String, Any?> = runtimes.map(Any?::obj)
    .filter { it["isAvailable"] == true && it.str("identifier").startsWith("com.apple.CoreSimulator.SimRuntime.iOS-") }
    .filter { identifier != null || Regex("com\\.apple\\.CoreSimulator\\.SimRuntime\\.iOS-[0-9]+(?:-[0-9]+){0,2}").matches(it.str("identifier")) }
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

data class IosLogQuery(val start: Instant, val end: Instant) {
    fun arguments(device: String, bundle: String): List<String> {
        val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ssxx", Locale.ROOT).withZone(ZoneOffset.UTC)
        return listOf(
            "xcrun", "simctl", "spawn", device, "log", "show", "--style", "compact",
            "--start", format.format(start), "--end", format.format(end),
            "--predicate", "process == \"EhViewer\" OR eventMessage CONTAINS \"$bundle\"",
        )
    }
}

fun iosLogQuery(started: Instant, finished: Instant): IosLogQuery {
    require(!finished.isBefore(started)) { "Simulator log window ended before it started" }
    // Include both fractional boundary seconds; exclude unrelated pre-launch boot logging.
    return IosLogQuery(started.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1), finished.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1))
}

fun iosUnifiedLifecycle(log: String, firstPid: Long, secondPid: Long): Map<String, Map<String, Int>> {
    require(firstPid > 0 && secondPid > 0 && firstPid != secondPid) { "Expected two distinct app processes" }
    fun process(pid: Long, resumed: Boolean): Map<String, Int> {
        val lines = log.lineSequence().filter { "EhViewer[$pid:" in it && "EhViewerM1:" in it }.toList()
        val messages = lines.map { it.substringAfter("EhViewerM1:") }
        val counts = listOf("cold-start", "compose-ready", "background", "active").associateWith { marker -> messages.count { it == marker } }
        require(counts.getValue("cold-start") == 1 && counts.getValue("compose-ready") >= 1) { "Unified log lacks cold start/Compose evidence for PID $pid" }
        require(counts.getValue("active") >= (if (resumed) 2 else 1) && (!resumed || counts.getValue("background") >= 1)) { "Unified log lacks lifecycle transitions for PID $pid" }
        return counts + ("rust_completed_results" to rustInteropResults(lines.joinToString("\n")))
    }
    return mapOf("first" to process(firstPid, resumed = true), "second" to process(secondPid, resumed = false))
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
    var logStart = Instant.now()
    fun phase(name: String) = Files.writeString(output.resolve("phases.log"), "${java.time.Instant.now()} $name\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    fun logs(name: String): String {
        val query = iosLogQuery(logStart, Instant.now())
        output.resolve("$name-query.json").writeText(json(mapOf("start" to query.start.toString(), "end" to query.end.toString(), "timeout_seconds" to 30)))
        return command(*query.arguments(device, bundle).toTypedArray(), timeout = Duration.ofSeconds(30))
    }
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
        val response = command("xcrun", "simctl", "launch", "--stdout=$stdout", "--stderr=$stderr", device, bundle, "--startup-regression")
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
        val sessionBackend = iosSessionBackendTests(device, output)
        val networkTests = temporary { staged ->
            phase("shared-network-tests")
            val networkStarted = Instant.now()
            val log = try {
                command(
                    root.resolve("gradlew"), ":core:network:iosSimulatorArm64Test", "--rerun",
                    "-Pehviewer.network.testSimulator=$device", "-Pehviewer.network.testDirectory=$staged",
                    "--console=plain", "--no-daemon", "--max-workers=1", "-Dorg.gradle.parallel=false",
                    "-Dorg.gradle.jvmargs=-Xmx2g", "-Pkotlin.native.jvmArgs=-Xmx3g", timeout = Duration.ofMinutes(10),
                )
            } catch (failure: CommandFailure) {
                output.resolve("network-tests.log").writeText(failure.output)
                throw failure
            }
            output.resolve("network-tests.log").writeText(log)
            val binary = root.resolve("core/network/build/bin/iosSimulatorArm64/debugTest/test.kexe")
            require(sha256(binary) == sha256(staged.resolve("test.kexe"))) { "Executed network tests differ from the actual Native binary" }
            val xml = filesBelow(root.resolve("core/network/build/test-results/iosSimulatorArm64Test"), ".xml")
            require(xml.isNotEmpty() && xml.all { Files.getLastModifiedTime(it).toInstant() >= networkStarted.minusSeconds(1) }) { "Missing or stale Native network test receipts" }
            val evidence = sharedHostTestEvidence(xml.map { Files.readAllBytes(it) }, filesBelow(root.resolve("core/network/src/commonTest"), ".kt").size)
            verifyNetworkTests(evidence)
            xml.forEach { Files.copy(it, output.resolve("network-${it.fileName}")) }
            evidence + mapOf("test_executable_sha256" to sha256(binary), "execution" to "BOOTED_OWNED_SIMULATOR")
        }
        val readerTests = temporary { staged ->
            phase("shared-reader-tests")
            val started = Instant.now()
            val log = try {
                command(
                    root.resolve("gradlew"), ":app:shared:iosSimulatorArm64Test", "--rerun",
                    "-Pehviewer.reader.testSimulator=$device", "-Pehviewer.reader.testDirectory=$staged",
                    "--console=plain", "--no-daemon", "--max-workers=1", "-Dorg.gradle.parallel=false",
                    "-Dorg.gradle.jvmargs=-Xmx2g", "-Pkotlin.native.jvmArgs=-Xmx3g", timeout = Duration.ofMinutes(10),
                )
            } catch (failure: CommandFailure) {
                output.resolve("reader-tests.log").writeText(failure.output)
                throw failure
            }
            output.resolve("reader-tests.log").writeText(log)
            val binary = root.resolve("app/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe")
            require(sha256(binary) == sha256(staged.resolve("test.kexe"))) { "Executed reader tests differ from the actual Native binary" }
            val xml = filesBelow(root.resolve("app/shared/build/test-results/iosSimulatorArm64Test"), ".xml")
            require(xml.isNotEmpty() && xml.all { Files.getLastModifiedTime(it).toInstant() >= started.minusSeconds(1) }) { "Missing or stale Native reader test receipts" }
            val evidence = sharedHostTestEvidence(xml.map { Files.readAllBytes(it) }, filesBelow(root.resolve("app/shared/src/commonTest"), ".kt").size)
            verifyReaderTests(evidence)
            xml.forEach { Files.copy(it, output.resolve("reader-${it.fileName}")) }
            Files.copy(binary, output.resolve("reader-tests.kexe"))
            evidence + mapOf("test_executable_sha256" to sha256(binary), "execution" to "BOOTED_OWNED_SIMULATOR")
        }
        command("xcrun", "simctl", "install", device, app)
        captureDirectory = Path.of(command("xcrun", "simctl", "get_app_container", device, bundle, "data").trim()).resolve("tmp")
        logStart = Instant.now()
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
        for (stderr in listOf(firstStderr, secondStderr)) require(Regex("EhViewerM3:gallery-product checks=3").findAll(stderr.readText()).count() == 1) { "Actual iOS detail/page/image regression did not execute" }
        val lifecycle = logs("lifecycle")
        output.resolve("lifecycle.log").writeText(lifecycle)
        val unified = iosUnifiedLifecycle(lifecycle, firstPid, secondPid)
        require(listOf("first", "second").map { unified.getValue(it).getValue("rust_completed_results") } == rustResults) { "Unified log and stderr Rust evidence disagree" }
        val result = mapOf(
            "source_commit" to command("git", "rev-parse", "HEAD").trim(),
            "source_dirty" to dirty,
            "network_tests" to networkTests,
            "reader_tests" to readerTests,
            "session_backend_tests" to sessionBackend,
            "runtime" to runtime.str("name"), "device_type" to type.str("name"), "architecture" to "arm64",
            "bundle_id" to bundle, "version" to plist("CFBundleShortVersionString"), "build" to plist("CFBundleVersion"),
            "executable_sha256" to sha256(executable), "cold_start" to "PASS", "background_foreground" to "PASS",
            "first_pid" to firstPid, "second_pid" to secondPid, "explicit_termination" to "PASS",
            "compose_ready" to "PASS", "development_signed_device" to "NOT_VERIFIED",
            "rust_c_abi" to "PASS", "rust_completed_results_by_launch" to rustResults, "rust_live_results_after_probe" to 0,
            "gallery_product_checks_by_launch" to listOf(3, 3),
            "unified_log_lifecycle" to unified,
        )
        output.resolve("startup.json").writeText(json(result))
        return result
    } catch (failure: Throwable) {
        output.resolve("failure.txt").writeText(failure.toString())
        try {
            output.resolve("failed-lifecycle.log").writeText(logs("failed-lifecycle"))
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
