package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Both ELF identity and CPU are required; a renamed x64 object must fail. */
fun verifyLinuxArm64Elf(header: ByteArray) {
    require(header.size >= 32) { "Truncated ELF" }
    val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    require(
        data.getInt(0) == 0x464c457f && header[4] == 2.toByte() && header[5] == 1.toByte() && header[6] == 1.toByte() &&
            data.getShort(16).toInt() in setOf(2, 3) && data.getShort(18).toInt() == 183 && data.getInt(20) == 1,
    ) { "Expected little-endian ELF64 AArch64 executable/library" }
}

/** Real jpackage application image, bundled JVM/Skiko/Rust and window; not an AppImage. */
fun desktopLinuxArm64(image: Path, output: Path): Map<String, Any?> {
    require(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "aarch64" && command("uname", "-m").trim() == "aarch64") { "Use the native Linux ARM64 JDK and runner" }
    require(command("rustc", "-vV").lineSequence().any { it == "host: aarch64-unknown-linux-gnu" }) { "Rust host must match Linux ARM64" }
    val requestedRenderApi = System.getenv("SKIKO_RENDER_API")
    val linuxRenderApis = setOf("OPENGL", "SOFTWARE_FAST", "SOFTWARE_COMPAT")
    require(requestedRenderApi == null || requestedRenderApi in linuxRenderApis) { "Unsupported explicit Linux render API" }
    require(image.isDirectory() && !output.exists()) { "Expected an application image and a fresh report directory" }
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    output.createDirectories()
    fun capture(name: String, vararg args: Any, timeout: Duration = Duration.ofMinutes(5)): String = try {
        command(*args, timeout = timeout).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    val checks = temporary { scratch ->
        val installed = scratch.resolve("EhViewer")
        capture("install.txt", "cp", "-a", image, installed)
        val contents = installed.resolve("lib")
        val notices = desktopNativeNotices(contents)
        val launcher = installed.resolve("bin/EhViewer")
        require(Files.isExecutable(launcher)) { "Missing executable launcher" }
        val rust = contents.resolve("app/resources/libehviewer_rust.so")
        val home = contents.resolve("runtime")
        val jvm = home.resolve("lib/server/libjvm.so")
        val relocations = capture("rust-relocations.txt", "ldd", "-r", rust)
        require(!relocations.contains("undefined symbol:") && !relocations.contains("not found")) { "Rust has unresolved runtime relocations" }
        require(home.resolve("lib/modules").isRegularFile()) { "Missing bundled jlink modules" }
        for (path in listOf(launcher, rust, jvm)) path.inputStream().use { verifyLinuxArm64Elf(it.readNBytes(32)) }
        val nativeFiles = mutableListOf<Map<String, Any?>>()
        Files.walk(installed).use { paths ->
            paths.filter {
                require(it.toRealPath().startsWith(installed.toRealPath())) { "Image symlink escapes installation" }
                it.isRegularFile()
            }.forEach { path ->
                val header = path.inputStream().use { it.readNBytes(32) }
                if (header.size >= 4 && ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 0x464c457f) {
                    verifyLinuxArm64Elf(header)
                    val dynamic = capture("native-dynamic-${nativeFiles.size}.txt", "readelf", "-d", path)
                    val search = dynamic.lineSequence().filter { it.contains("(RPATH)") || it.contains("(RUNPATH)") }
                        .flatMap { Regex("\\[([^]]+)]").find(it)!!.groupValues[1].split(':').asSequence() }.toList()
                    require(search.all { it.startsWith("${'$'}ORIGIN") && !it.contains(root.toString()) }) { "Native search path escapes the image: ${path.name}" }
                    val dependencies = capture("native-dependencies-${nativeFiles.size}.txt", "env", "LD_LIBRARY_PATH=${home.resolve("lib")}:${home.resolve("lib/server")}", "ldd", path)
                    require(!dependencies.contains("not found")) { "Missing native dependency: ${path.name}" }
                    val resolved = Regex("(/[^\\s(]+)").findAll(dependencies).map { Path.of(it.value).toRealPath() }.toList()
                    require(resolved.all { it.startsWith(installed) || it.startsWith("/usr/lib") || it.startsWith("/lib") }) { "Native dependency resolves outside image/system: ${path.name}" }
                    nativeFiles += mapOf("path" to installed.relativize(path).toString(), "sha256" to sha256(path), "search_paths" to search)
                } else {
                    require(path.extension !in setOf("so", "dylib", "jnilib", "dll") && !Regex("\\.so\\.[0-9.]+$").containsMatchIn(path.name)) { "Invalid or foreign native library: ${path.name}" }
                }
            }
        }
        val skiko = contents.resolve("app").listDirectoryEntries("libskiko*.so").single()
        require(sha256(skiko) == "d4b06daa856c00707183127e5ff269d082db307f677a72a6076b8cbb7f54121e") { "Unexpected Skiko Linux ARM64 runtime" }
        var jarNatives = 0
        contents.resolve("app").listDirectoryEntries("*.jar").forEach { jar ->
            ZipFile(jar.toFile()).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.') in setOf("so", "dylib", "jnilib", "dll") }.forEach { entry ->
                    require(entry.name.endsWith(".so")) { "Foreign native in ${jar.name}!/${entry.name}" }
                    zip.getInputStream(entry).use { verifyLinuxArm64Elf(it.readNBytes(32)) }
                    jarNatives++
                }
            }
        }
        val version = Regex("val releaseVersion = \"([^\"]+)\"").find(root.resolve("app/build.gradle.kts").readText())!!.groupValues[1]
        val config = contents.resolve("app/EhViewer.cfg").readText()
        require(config.lineSequence().any { it == "java-options=-Djpackage.app-version=$version" }) { "Application image version mismatch" }
        val launches = (1..2).map { attempt ->
            val proofDirectory = output.resolve("launch-$attempt")
            capture("launch-$attempt.log", launcher, "--m1-smoke", proofDirectory, timeout = Duration.ofMinutes(2))
            val proof = Properties().apply { proofDirectory.resolve("launcher.properties").inputStream().use(::load) }
            require(proof.getProperty("architecture") == "aarch64")
            require(proof.getProperty("application_version") == version) { "Running application version mismatch" }
            require(Path.of(proof.getProperty("java_home")).toRealPath() == home.toRealPath()) { "Launcher used an external JVM" }
            require(proof.getProperty("java_version") == System.getProperty("java.version")) { "Unexpected bundled JVM version" }
            require(proof.getProperty("rust_hash_results").toInt() == 100 && proof.getProperty("compose_frames").toInt() > 0 && proof.getProperty("window_showing") == "true")
            val renderApi = proof.getProperty("render_api")
            require(renderApi in linuxRenderApis && (requestedRenderApi == null || renderApi == requestedRenderApi)) { "Measured render API does not match the requested backend: $renderApi / $requestedRenderApi" }
            mapOf("rust_hash_results" to 100, "compose_frames" to proof.getProperty("compose_frames").toInt(), "bundled_runtime" to "PASS", "render_api" to renderApi)
        }
        val regression = desktopArchiveRegression(output, Path.of(System.getProperty("java.home"), "bin/java"), rust)
        mapOf(
            "version" to version, "native_files" to nativeFiles, "installed_notices" to notices, "skiko_sha256" to sha256(skiko), "jar_native_libraries" to jarNatives,
            "launches" to launches, "requested_render_api" to requestedRenderApi, "jni_regression_external_test_jdk" to regression, "runtime_jvm_sha256" to sha256(jvm), "rust_sha256" to sha256(rust), "launcher_sha256" to sha256(launcher),
        )
    }
    val result = mapOf(
        "source_commit" to command("git", "rev-parse", "HEAD").trim(), "source_dirty" to dirty, "result" to "PASS",
        "target" to "aarch64-unknown-linux-gnu", "jvm_architecture" to System.getProperty("os.arch"), "jvm_version" to System.getProperty("java.version"),
        "kernel" to command("uname", "-sr").trim(), "os_release" to Path.of("/etc/os-release").readText(), "rust" to command("rustc", "--version").trim(),
        "checks" to checks, "appimage" to "NOT_VERIFIED", "minimum_os" to "NOT_VERIFIED", "desktop_p0" to "NOT_VERIFIED", "complete_dependency_license_audit" to "NOT_VERIFIED",
    )
    output.resolve("linux-arm64.json").writeText(json(result))
    return result
}
