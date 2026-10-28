package verification

import com.hippo.ehviewer.jni.benchmarkArchives
import groovy.json.JsonSlurper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

/** Check the actual library header before the JVM attempts to load it. */
fun verifyDesktopLibrary(header: ByteArray, target: String) {
    require(header.size >= 32) { "Truncated native library" }
    val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    when (target) {
        "aarch64-apple-darwin" -> {
            require(data.getInt(0) == 0xfeedfacf.toInt() && data.getInt(4) == 0x100000c && data.getInt(12) == 6) { "Expected ARM64 Mach-O dylib" }
        }
        "x86_64-unknown-linux-gnu" -> {
            require(data.getInt(0) == 0x464c457f && header[4] == 2.toByte() && header[5] == 1.toByte() && data.getShort(16).toInt() == 3 && data.getShort(18).toInt() == 62) { "Expected x86_64 ELF shared object" }
        }
        else -> error("Desktop JNI smoke has not been implemented for $target")
    }
}

/** Real desktop JNI loading, archive extraction and ownership regression; not a product launcher. */
fun desktopNativeSmoke(output: Path): Map<String, Any?> {
    require(!output.exists()) { "Expected a fresh evidence directory" }
    val mac = System.getProperty("os.name").startsWith("Mac")
    val machine = command("uname", "-m").trim()
    val target = when {
        mac && machine == "arm64" -> "aarch64-apple-darwin"
        System.getProperty("os.name") == "Linux" && machine == "x86_64" -> "x86_64-unknown-linux-gnu"
        else -> error("This slice verifies macOS ARM64 and Linux x86_64 only")
    }
    require(command("rustc", "-vV").lineSequence().any { it == "host: $target" }) { "Rust host does not match the native runner" }
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    output.createDirectories()
    fun capture(name: String, vararg args: Any, timeout: Duration = Duration.ofMinutes(5)): String = try {
        command(*args, timeout = timeout).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    val nativeSearch = if (mac) {
        listOf("libarchive", "webp").flatMap { listOf("-L", "native=${command("brew", "--prefix", it).trim()}/lib") }
    } else {
        emptyList()
    }
    val cargo = listOf(
        "cargo", "rustc", "--manifest-path", root.resolve("native/rust/Cargo.toml").toString(),
        "--locked", "--lib", "--features", "jvm", "--crate-type", "cdylib", "--target", target,
    ) + if (nativeSearch.isEmpty()) emptyList() else listOf("--") + nativeSearch
    capture("cargo.log", *cargo.toTypedArray(), timeout = Duration.ofMinutes(15))
    val library = root.resolve("native/rust/target/$target/debug/libehviewer_rust.${if (mac) "dylib" else "so"}")
    library.inputStream().use { verifyDesktopLibrary(it.readNBytes(32), target) }
    val results = desktopArchiveRegression(output, Path.of(System.getProperty("java.home"), "bin", "java"), library)
    val result = mapOf(
        "source_commit" to command("git", "rev-parse", "HEAD").trim(), "source_dirty" to dirty,
        "target" to target, "jvm_architecture" to System.getProperty("os.arch"), "jvm_version" to System.getProperty("java.version"),
        "rust" to command("rustc", "--version").trim(), "library_sha256" to sha256(library), "result" to "PASS",
        "checks" to results, "packaged_runtime" to "NOT_VERIFIED", "ios_c_abi" to "NOT_VERIFIED",
    )
    output.resolve("desktop-native.json").writeText(json(result))
    return result
}

/** The same immutable fixtures and strict JNI assertions for host and packaged-library probes. */
fun desktopArchiveRegression(output: Path, java: Path, library: Path): Map<String, Any?> {
    fun capture(name: String, vararg args: Any): String = try {
        command(*args).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    val client = listOf(java.toString(), "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), "verification.MainKt")
    return temporary { fixtures ->
        // Reuse only EhViewer's immutable regression data, never an external reference tree.
        val baseline = "8fdfb6c5550fab506759a5587b1c8ee4050f507e"
        command("git", "archive", "--format=tar", "--output=${fixtures.resolve("fixtures.tar")}", baseline, "native/test-fixtures")
        command("tar", "-xf", fixtures.resolve("fixtures.tar"), "-C", fixtures)
        val inputs = fixtures.resolve("native/test-fixtures")
        benchmarkArchives(Options(listOf("--output", inputs.toString())))
        val large = fixtures.resolve("large")
        benchmarkArchives(Options(listOf("--output", large.toString(), "--pages", "1", "--page-bytes", "${16 shl 20}")))
        val regression = JsonSlurper().parseText(
            capture(
                "regression.json",
                *(client + listOf("native-regression", "--library", library.toString(), "--fixtures", inputs.toString(), "--large-archive", large.resolve("large-stored.zip").toString())).toTypedArray(),
            ),
        ).obj()
        require(regression["result"] == "passed" && (regression["checks"] as Number).toInt() >= 4170) { "Incomplete JNI ownership regression" }
        val differential = JsonSlurper().parseText(
            capture(
                "archives.json",
                *(client + listOf("native-differential", "--library", library.toString(), "--fixtures", inputs.toString(), "--readonly")).toTypedArray(),
            ),
        ).obj()
        require(differential["archives"].obj().size == 13) { "Incomplete archive regression cases" }
        mapOf("ownership_checks" to regression["checks"], "archive_cases" to 13, "fixture_baseline" to baseline)
    }
}
