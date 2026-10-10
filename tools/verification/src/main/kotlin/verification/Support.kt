package verification

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
import kotlin.io.path.readText

val root: Path = Path.of("").toAbsolutePath().normalize()
val abis = linkedMapOf("arm64-v8a" to 183, "armeabi-v7a" to 40, "x86_64" to 62)
const val CERTIFICATE = "5d91dff13b6489a9cfadbb197b3e795eefc8c39f849c4543f32c160720fb40a6"

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: error("Expected JSON object")
fun Any?.array(): List<Any?> = this as? List<*> ?: error("Expected JSON array")
fun Map<String, Any?>.str(key: String): String = this[key] as? String ?: error("Expected string: $key")
fun readJson(path: Path): Map<String, Any?> = JsonSlurper().parse(path.toFile()).obj()
fun json(value: Any?): String = JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n"
fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.inputStream().use { stream ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

class CommandFailure(val command: List<String>, val output: String) : IllegalStateException(
    "Command failed: ${command.joinToString(" ")}\n$output",
)

fun command(vararg args: Any, input: String? = null, timeout: Duration = Duration.ofMinutes(5)): String {
    val arguments = args.map(Any::toString)
    // Redirect to a file so a verbose child cannot deadlock on a full output pipe.
    val capture = Files.createTempFile("ehviewer-command-", ".log")
    try {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(capture.toFile()).start()
        process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly().waitFor()
            error("Command timed out: ${arguments.first()}")
        }
        val output = capture.readText()
        if (process.exitValue() != 0) throw CommandFailure(arguments, output)
        return output
    } finally {
        capture.deleteIfExists()
    }
}

fun <T> temporary(block: (Path) -> T): T {
    val directory = Files.createTempDirectory("ehviewer-verification-").toRealPath()
    try {
        return block(directory)
    } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

class Options(args: List<String>) {
    private val flags = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    init {
        var index = 0
        while (index < args.size) {
            val key = args[index++]
            if (key.startsWith("--")) {
                require(!flags.containsKey(key)) { "Duplicate option: $key" }
                flags[key] = if (key in setOf("--debug", "--test-signing")) "true" else args.getOrNull(index++) ?: error("Missing $key value")
            } else {
                positional += key
            }
        }
    }
    fun get(key: String, default: String? = null): String = flags[key] ?: default ?: error("Missing $key")
    fun path(key: String, default: String? = null): Path = Path.of(get(key, default)).toAbsolutePath().normalize()
    fun has(key: String): Boolean = flags.containsKey(key)
}

class AndroidTools(val sdk: Path, ndk: String = "29.0.14206865") {
    val buildTools: Path = sdk.resolve("build-tools/37.0.0")
    val llvm: Path = sdk.resolve(
        "ndk/$ndk/toolchains/llvm/prebuilt/" +
            if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64/bin" else "linux-x86_64/bin",
    )
}
