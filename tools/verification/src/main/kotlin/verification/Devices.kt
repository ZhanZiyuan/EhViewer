package verification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
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
