package verification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText

fun deviceTests(sdk: Path, serial: String, output: Path, app: Path, test: Path, expectedApi: Int? = null, expectedPageSize: Int? = null): Map<String, Any?> {
    output.createDirectories()
    val adb = sdk.resolve("platform-tools/adb")
    fun run(name: String, vararg args: String): String {
        val text = try {
            command(adb, "-s", serial, *args)
        } catch (failure: CommandFailure) {
            output.resolve(name).writeText(failure.output)
            throw failure
        }
        output.resolve(name).writeText(text)
        return text
    }
    val api = run("api.txt", "shell", "getprop", "ro.build.version.sdk").trim().toInt()
    val pages = runCatching { command(adb, "-s", serial, "shell", "getconf", "PAGE_SIZE").trim().toInt() }.getOrElse {
        val maps = run("smaps.txt", "shell", "cat", "/proc/self/smaps")
        Regex("(?m)^KernelPageSize:\\s+(\\d+)").find(maps)!!.groupValues[1].toInt() * 1024
    }
    require(pages >= 4096 && (expectedApi == null || api == expectedApi) && (expectedPageSize == null || pages == expectedPageSize)) { "Unexpected device API/page size" }
    output.resolve("page-size.txt").writeText("$pages\n")
    val digests = listOf(app, test).associate { it.name to sha256(it) }
    listOf(app, test).forEachIndexed { index, apk -> run("install-$index.log", "install", "-r", apk.toString()) }
    // Some API 26 logd versions reject clearing an active buffer. Keep the error
    // in the report; clearing historical logs is not a prerequisite for JUnit.
    runCatching { run(ADB_LOG_NAME, "logcat", "-c") }
    val result = run("instrumentation.log", "shell", "am", "instrument", "-w", "-r", "moe.tarsin.ehviewer.debug.test/androidx.test.runner.AndroidJUnitRunner")
    run("native-errors.log", "logcat", "-d", "-s", "AndroidRuntime:E", "libc:F", "DEBUG:F")
    require(Regex("(?m)^OK \\(19 tests\\)").containsMatchIn(result) && listOf("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed").none(result::contains)) { "JUnit did not report 19 successful tests" }
    return mapOf("serial" to serial, "api" to api, "page_size" to pages, "tests" to 19, "apks" to digests, "result" to "PASS")
        .also { output.resolve("result.json").writeText(json(it)) }
}

private const val ADB_LOG_NAME = "logcat-clear.log"

fun emulatorTests(options: Options): Map<String, Any?> {
    val sdk = options.path("--sdk", System.getenv("ANDROID_HOME"))
    val image = options.get("--image", System.getenv("TEST_IMAGE"))
    val label = options.get("--label", System.getenv("TEST_LABEL"))
    require(Regex("api[0-9]+(?:-16k|-[a-z0-9-]+)?").matches(label))
    val expectedApi = label.removePrefix("api").substringBefore('-').toInt()
    val output = options.path("--output", "device-reports").createDirectories()
    val sdkManager = sdk.resolve("cmdline-tools/latest/bin/sdkmanager")
    val avdManager = sdk.resolve("cmdline-tools/latest/bin/avdmanager")
    val adb = sdk.resolve("platform-tools/adb")
    require(runCatching { command(adb, "-s", "emulator-5554", "get-state") }.isFailure) { "Emulator port 5554 is already in use" }
    command(sdkManager, image, timeout = Duration.ofMinutes(15))
    return temporary { temp ->
        val avd = temp.resolve("avd")
        command(avdManager, "create", "avd", "--name", "ehviewer-ci", "--package", image, "--device", "pixel_2", "--path", avd, input = "no\n")
        val config = avd.resolve("config.ini")
        config.writeText(config.readLines().filterNot { it.startsWith("disk.dataPartition.size=") }.joinToString("\n", postfix = "\ndisk.dataPartition.size=2G\n"))
        val process = ProcessBuilder(sdk.resolve("emulator/emulator").toString(), "-avd", "ehviewer-ci", "-port", "5554", "-no-window", "-no-audio", "-no-snapshot", "-gpu", "swiftshader", "-no-boot-anim")
            .redirectErrorStream(true).redirectOutput(output.resolve("emulator.log").toFile()).start()
        try {
            val deadline = System.nanoTime() + Duration.ofMinutes(6).toNanos()
            while (runCatching { command(adb, "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed", timeout = Duration.ofSeconds(10)).trim() }.getOrNull() != "1") {
                require(process.isAlive && System.nanoTime() < deadline) { "Emulator failed to boot" }
                Thread.sleep(2000)
            }
            fun input(name: String): Path = filesBelow(root.resolve("device-inputs"), name).single { it.name == name }
            deviceTests(sdk, "emulator-5554", output, input("app-universal-debug.apk"), input("app-debug-androidTest.apk"), expectedApi, if (label.endsWith("16k")) 16384 else null)
        } finally {
            runCatching { output.resolve("logcat.txt").writeText(command(adb, "-s", "emulator-5554", "logcat", "-d")) }
            if (process.isAlive) runCatching { command(adb, "-s", "emulator-5554", "emu", "kill") }
            if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            runCatching { command(avdManager, "delete", "avd", "--name", "ehviewer-ci") }
        }
    }
}
