package verification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val WINDOWS_JDK_URL = "https://aka.ms/download-jdk/microsoft-jdk-25.0.5.0.2-windows-aarch64.zip"
private const val WINDOWS_JDK_SHA256 = "f227cc459fbd65a42c1b28123d68e207eb500899c1c78238f21a038cd33688a3"

/** Extract only a checksum-verified vendor archive into a fresh owned directory. */
fun extractWindowsJdk(archive: Path, destination: Path) {
    require(!destination.exists()) { "Use a fresh JDK extraction directory" }
    destination.createDirectories()
    var total = 0L
    ZipFile(archive.toFile()).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size in 1..20_000) { "Unexpected JDK ZIP entry count" }
        for (entry in entries) {
            val relative = Path.of(entry.name)
            val path = destination.resolve(relative).normalize()
            require(!relative.isAbsolute && !Regex("^[A-Za-z]:").containsMatchIn(entry.name) && !entry.name.contains('\\') && path.startsWith(destination) && path != destination) { "JDK ZIP path escaped its directory" }
            if (entry.isDirectory) {
                path.createDirectories()
            } else {
                path.parent.createDirectories()
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 1024L * 1024 * 1024) { "JDK ZIP exceeds the extraction budget" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }
}

/** Preserve the complete native gate, then prove real owned-result calls from a native JVM. */
fun windowsJvmFfi(coreReport: Path, output: Path): Map<String, Any?> {
    require(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") { "Use a native Windows ARM64 JVM and runner" }
    require(System.getenv("GITHUB_ACTIONS") != "true" || System.getenv("RUNNER_ARCH") == "ARM64") { "Expected the Windows ARM64 runner" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    val core = readJson(coreReport)
    require(core["result"] == "PASS" && core["source_commit"] == source && core["source_dirty"] == dirty && core["target"] == "aarch64-pc-windows-msvc" && core["passed"] == 39 && core["filtered"] == 0) { "A complete native Core/C ABI result from this exact source is required" }
    require(!output.exists()) { "Use a fresh JVM FFI evidence directory" }
    val work = root.resolve("app/desktop/build/windows-jvm-ffi")
    require(!work.exists()) { "Use a fresh JVM FFI build directory" }
    output.createDirectories()
    work.createDirectories()
    Files.copy(coreReport, output.resolve("native-core-proof.json"))
    fun capture(name: String, vararg arguments: Any, environment: Map<String, String> = emptyMap()): String = try {
        command(*arguments, environment = environment, timeout = Duration.ofMinutes(15)).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    fun pe(path: Path) {
        require(path.isRegularFile()) { "Missing ARM64 Windows image: $path" }
        path.inputStream().use { verifyWindowsArm64Pe(it.readNBytes(1 shl 20)) }
    }
    val zip = work.resolve("jdk.zip")
    capture("jdk-download.txt", "curl.exe", "--fail", "--silent", "--show-error", "--location", "--connect-timeout", "30", "--max-time", "600", WINDOWS_JDK_URL, "--output", zip)
    require(sha256(zip) == WINDOWS_JDK_SHA256) { "Pinned Microsoft JDK checksum mismatch" }
    val extracted = work.resolve("jdk")
    extractWindowsJdk(zip, extracted)
    val jdk = Files.list(extracted).use { paths -> paths.toList().single() }
    require(jdk.isDirectory()) { "Unexpected vendor JDK root" }
    for (name in listOf("bin/java.exe", "bin/server/jvm.dll", "bin/jlink.exe", "bin/jpackage.exe")) pe(jdk.resolve(name))
    val release = jdk.resolve("release").readText()
    require(release.contains("OS_ARCH=\"aarch64\"") && release.contains("IMPLEMENTOR=\"Microsoft\"") && release.contains("JAVA_VERSION=\"25.0.5.0.2\"")) { "Unexpected JDK release identity" }
    Files.copy(jdk.resolve("release"), output.resolve("jdk-release.txt"))
    for (name in listOf("LICENSE", "ADDITIONAL_LICENSE_INFO")) {
        Files.copy(jdk.resolve("legal/java.base/$name"), output.resolve("jdk-$name.txt"))
    }
    capture("java-version.txt", jdk.resolve("bin/java.exe"), "-XshowSettings:properties", "-version")

    val coreWork = root.resolve("app/desktop/build/windows-native-core")
    val native = coreWork.resolve("installed/arm64-windows-ehviewer")
    val pathKey = System.getenv().keys.single { it.equals("PATH", ignoreCase = true) }
    val cargoEnvironment = mapOf(
        pathKey to "${native.resolve("bin")};${System.getenv(pathKey)}",
        "CARGO_TARGET_DIR" to coreWork.resolve("target").toString(),
        "CARGO_NET_GIT_FETCH_WITH_CLI" to "true",
        "CARGO_ENCODED_RUSTFLAGS" to listOf("-L", "native=${native.resolve("lib")}", "-l", "libwebpdemux", "-l", "libwebp").joinToString("\u001f"),
    )
    val channel = Regex("channel = \"(nightly-[0-9-]+)\"").find(root.resolve("rust-toolchain.toml").readText())!!.groupValues[1]
    capture(
        "cargo-cdylib.txt", "cargo", "+$channel-aarch64-pc-windows-msvc", "rustc", "--manifest-path", root.resolve("native/rust/Cargo.toml"),
        "--locked", "-p", "ehviewer_capi", "--lib", "--crate-type", "cdylib", "--target", "aarch64-pc-windows-msvc", environment = cargoEnvironment,
    )
    // A real Unicode/space directory exercises the Windows wide-character loader.
    val bundle = work.resolve("Native JVM 资料").createDirectories()
    for (item in core["dlls"].array()) {
        val dll = item.obj()
        val name = dll.str("file")
        require(Path.of(name).fileName.toString() == name) { "Invalid dependency file name" }
        val original = coreReport.parent.resolve("native-libraries").resolve(name)
        require(sha256(original) == dll.str("sha256")) { "Native dependency changed after its tests: $name" }
        pe(original)
        Files.copy(original, bundle.resolve(name))
    }
    val library = bundle.resolve("ehviewer_capi.dll")
    Files.copy(coreWork.resolve("target/aarch64-pc-windows-msvc/debug/ehviewer_capi.dll"), library)
    pe(library)
    val evidence = output.resolve("native-libraries").createDirectories()
    Files.copy(library, evidence.resolve(library.fileName))
    val caller = output.resolve("caller")

    // The native Windows launcher can still use Cp1252. Keep both JVM hops ASCII-only.
    fun encoded(path: Path) = Base64.getEncoder().encodeToString(path.toString().toByteArray(Charsets.UTF_8))
    capture(
        "jvm-probe.txt", jdk.resolve("bin/java.exe"), "-Dorg.gradle.appname=gradlew", "-jar", root.resolve("gradle/wrapper/gradle-wrapper.jar"),
        "-p", root.resolve("app/desktop"), "rustCapiProbe", "spotlessCheck", "-PffiLibraryBase64=${encoded(library)}", "-PffiOutputBase64=${encoded(caller)}",
        "--console=plain", "--no-daemon", "--max-workers=1", "-Dorg.gradle.parallel=false", "-Dorg.gradle.jvmargs=-Xmx2g", "-Dorg.gradle.java.home=$jdk",
        environment = mapOf("JAVA_HOME" to jdk.toString(), pathKey to "${jdk.resolve("bin")};${System.getenv(pathKey)}"),
    )
    val proof = Properties().apply { caller.resolve("ffi.properties").inputStream().use { load(it) } }
    require(proof.getProperty("result") == "PASS" && proof.getProperty("architecture") == "aarch64" && proof.getProperty("os").startsWith("Windows")) { "Caller was not a real Windows ARM64 JVM" }
    require(proof.getProperty("java_version") == "25.0.5.0.2" && Path.of(proof.getProperty("java_home")).toRealPath() == jdk.toRealPath()) { "Caller used a different JVM" }
    require(Path.of(proof.getProperty("library")).toRealPath() == library.toRealPath()) { "Caller loaded a different DLL" }
    require(proof.getProperty("path_transport") == "utf8-base64") { "Caller did not preserve the explicit UTF-8 path transport" }
    require(proof.getProperty("rust_hash_results") == "2000" && proof.getProperty("rust_active_results") == "0" && proof.getProperty("abi_version") == "1") { "Incomplete real Rust ownership calls" }
    for (name in listOf("undersized_copy", "stale_handle", "double_free", "library_closed")) require(proof.getProperty(name) == "PASS") { "Missing FFI check: $name" }
    val result = mapOf(
        "source_commit" to source, "source_dirty" to dirty, "result" to "PASS", "target" to "aarch64-pc-windows-msvc",
        "jdk_url" to WINDOWS_JDK_URL, "jdk_archive_sha256" to WINDOWS_JDK_SHA256, "java_sha256" to sha256(jdk.resolve("bin/java.exe")),
        "rust_capi_sha256" to sha256(library), "caller" to proof.stringPropertyNames().sorted().associateWith { proof.getProperty(it) },
        "packaged_jvm_window" to "NOT_VERIFIED", "windows_zip" to "NOT_VERIFIED", "complete_license_audit" to "NOT_VERIFIED",
    )
    output.resolve("windows-jvm-ffi.json").writeText(json(result))
    return result
}
