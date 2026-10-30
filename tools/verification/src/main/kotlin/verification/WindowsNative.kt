package verification

import groovy.json.JsonSlurper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Reject COFF objects, x64/ARM64EC images, renamed files and invalid DOS/PE offsets. */
fun verifyWindowsArm64Pe(header: ByteArray) {
    require(header.size >= 64) { "Truncated DOS header" }
    val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val offset = data.getInt(60)
    require(data.getShort(0).toInt() == 0x5a4d && offset in 64..header.size - 26) { "Invalid DOS/PE header offset" }
    require(
        data.getInt(offset) == 0x4550 && (data.getShort(offset + 4).toInt() and 0xffff) == 0xaa64 &&
            data.getShort(offset + 24).toInt() == 0x20b && (data.getShort(offset + 22).toInt() and 2) == 2,
    ) { "Expected an executable PE32+ ARM64 image" }
}

private const val VCPKG_BASELINE = "15b12b99a227d51e21c86dffd40385439eac87a0"
private const val WINDOWS_TARGET = "aarch64-pc-windows-msvc"

/** Real Windows ARM64 Core/C ABI tests. This is not a packaged JVM/window or final ZIP proof. */
fun windowsNativeCore(output: Path): Map<String, Any?> {
    require(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") { "Use a native Windows ARM64 JDK and runner" }
    require(System.getenv("GITHUB_ACTIONS") != "true" || System.getenv("RUNNER_ARCH") == "ARM64") { "Expected the Windows ARM64 runner" }
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    require(!output.exists()) { "Use a fresh Windows evidence directory" }
    val work = root.resolve("app/desktop/build/windows-native-core")
    require(!work.exists()) { "Use a fresh native build directory" }
    output.createDirectories()
    work.createDirectories()
    fun capture(name: String, vararg args: Any, environment: Map<String, String> = emptyMap(), timeout: Duration = Duration.ofMinutes(15)): String = try {
        command(*args, environment = environment, timeout = timeout).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    fun pe(path: Path) {
        require(path.isRegularFile()) { "Missing Windows image: $path" }
        path.inputStream().use { verifyWindowsArm64Pe(it.readNBytes(1 shl 20)) }
    }
    pe(Path.of(System.getProperty("java.home"), "bin/java.exe"))
    capture("fixture-baseline.txt", "git", "cat-file", "-e", "8fdfb6c5550fab506759a5587b1c8ee4050f507e^{commit}")
    val channel = Regex("channel = \"(nightly-[0-9-]+)\"").find(root.resolve("rust-toolchain.toml").readText())!!.groupValues[1]
    val toolchain = "$channel-$WINDOWS_TARGET"
    capture("rust-install.txt", "rustup", "toolchain", "install", toolchain, "--profile", "minimal", "--component", "rustfmt", "--component", "clippy")
    val rustVersion = capture("rust-version.txt", "rustc", "+$toolchain", "-vV")
    require(rustVersion.lineSequence().any { it == "host: $WINDOWS_TARGET" }) { "Rust compiler host is not native Windows ARM64" }
    pe(Path.of(capture("rust-path.txt", "rustup", "which", "--toolchain", toolchain, "rustc").trim()))

    val vcpkg = work.resolve("vcpkg")
    // vcpkg checks out historical ports in a sanitized environment. Keep their blobs local so
    // checkout-index cannot trigger a promisor fetch that depends on the parent's network setup.
    capture("vcpkg-clone.txt", "git", "clone", "--no-checkout", "https://github.com/microsoft/vcpkg.git", vcpkg)
    capture("vcpkg-checkout.txt", "git", "-C", vcpkg, "checkout", "--detach", VCPKG_BASELINE)
    require(capture("vcpkg-head.txt", "git", "-C", vcpkg, "rev-parse", "HEAD").trim() == VCPKG_BASELINE)
    val manifest = work.resolve("manifest").createDirectories()
    val triplets = work.resolve("triplets").createDirectories()
    val triplet = "arm64-windows-ehviewer"
    // Generated build configuration, not maintained C/C++ or a business implementation.
    triplets.resolve("$triplet.cmake").writeText(
        "include(\"${vcpkg.toString().replace('\\', '/')}/triplets/arm64-windows.cmake\")\nset(VCPKG_BUILD_TYPE release)\n",
    )
    val manifestFile = manifest.resolve("vcpkg.json")
    manifestFile.writeText(
        json(
            mapOf(
                "name" to "ehviewer-windows-native-spike",
                "version-string" to "0.0.0",
                "builtin-baseline" to VCPKG_BASELINE,
                "dependencies" to listOf(
                    mapOf("name" to "libarchive", "default-features" to false, "features" to listOf("crypto", "lzma")),
                    mapOf("name" to "libwebp", "default-features" to false, "features" to listOf("simd")),
                ),
                "overrides" to listOf(mapOf("name" to "libarchive", "version" to "3.8.7"), mapOf("name" to "libwebp", "version" to "1.6.0", "port-version" to 3)),
            ),
        ),
    )
    Files.copy(manifestFile, output.resolve("vcpkg-manifest.json"))
    Files.copy(triplets.resolve("$triplet.cmake"), output.resolve("generated-triplet.cmake"))
    Files.copy(vcpkg.resolve("LICENSE.txt"), output.resolve("vcpkg-LICENSE.txt"))
    val dependencyEnvironment = mapOf("VCPKG_DISABLE_METRICS" to "1", "VCPKG_DEFAULT_HOST_TRIPLET" to "arm64-windows")
    capture("vcpkg-bootstrap.txt", "cmd.exe", "/d", "/c", vcpkg.resolve("bootstrap-vcpkg.bat"), "-disableMetrics", environment = dependencyEnvironment)
    val installed = work.resolve("installed")
    capture(
        "vcpkg-install.txt", vcpkg.resolve("vcpkg.exe"), "install", "--triplet=$triplet", "--host-triplet=arm64-windows",
        "--x-manifest-root=$manifest", "--x-install-root=$installed", "--overlay-triplets=$triplets",
        environment = dependencyEnvironment, timeout = Duration.ofMinutes(30),
    )
    capture("vcpkg-tracked-clean.txt", "git", "-C", vcpkg, "diff", "--exit-code")
    val native = installed.resolve(triplet)
    // libwebp's upstream MSVC build explicitly uses the lib prefix for import libraries.
    for (name in listOf("archive.lib", "libwebpdemux.lib", "libwebp.lib")) {
        require(native.resolve("lib").resolve(name).isRegularFile()) { "Missing native import library: $name" }
    }
    val status = installed.resolve("vcpkg/status")
    require(status.isRegularFile()) { "Missing installed dependency inventory" }
    Files.copy(status, output.resolve("vcpkg-status.txt"))
    val dlls = Files.list(native.resolve("bin")).use { it.filter { path -> path.extension.equals("dll", ignoreCase = true) }.sorted().toList() }
    require(dlls.isNotEmpty()) { "No native codec DLLs were built" }
    val libraryEvidence = output.resolve("native-libraries").createDirectories()
    val identities = dlls.map { path ->
        pe(path)
        Files.copy(path, libraryEvidence.resolve(path.fileName))
        mapOf("file" to path.name, "sha256" to sha256(path), "architecture" to "ARM64")
    }
    val notices = mutableListOf<Map<String, String>>()
    Files.walk(native.resolve("share")).use { paths ->
        paths.filter { it.isRegularFile() && it.name == "copyright" }.sorted().forEach { path ->
            val relative = native.relativize(path)
            val copy = output.resolve("licenses").resolve(relative)
            copy.parent.createDirectories()
            Files.copy(path, copy)
            notices += mapOf("path" to relative.toString(), "sha256" to sha256(copy))
        }
    }
    require(notices.any { it["path"] == Path.of("share/libarchive/copyright").toString() } && notices.any { it["path"] == Path.of("share/libwebp/copyright").toString() }) { "Missing direct native copyright texts" }
    val pathKey = System.getenv().keys.single { it.equals("PATH", ignoreCase = true) }
    val environment = mapOf(
        pathKey to "${native.resolve("bin")};${System.getenv(pathKey)}",
        "CARGO_TARGET_DIR" to work.resolve("target").toString(),
        "CARGO_NET_GIT_FETCH_WITH_CLI" to "true",
        // Encoded flags preserve paths containing spaces. The pinned WebP Rust binding has build=false.
        "CARGO_ENCODED_RUSTFLAGS" to listOf("-L", "native=${native.resolve("lib")}", "-l", "libwebpdemux", "-l", "libwebp").joinToString("\u001f"),
    )
    val cargoManifest = root.resolve("native/rust/Cargo.toml")
    capture("rust-format.txt", "cargo", "+$toolchain", "fmt", "--all", "--manifest-path", cargoManifest, "--", "--check", environment = environment)
    val cargo = arrayOf<Any>("cargo", "+$toolchain", "test", "--locked", "--manifest-path", cargoManifest, "-p", "ehviewer_core", "-p", "ehviewer_capi", "-p", "ehviewer_session", "--target", WINDOWS_TARGET)
    val build = capture("cargo-build.jsonl", *cargo, "--no-run", "--message-format=json", environment = environment)
    val messages = build.lineSequence().filter { it.startsWith("{\"reason\":") }.map { JsonSlurper().parseText(it).obj() }.toList()
    val binaries = messages.filter { it["reason"] == "compiler-artifact" && it["profile"].obj()["test"] == true && it["executable"] is String }
    require(binaries.map { it["target"].obj().str("name") }.toSet() == setOf("ehviewer_core", "archive", "ehviewer_capi", "ehviewer_session") && binaries.size == 4) { "Missing complete Core/archive/C ABI/session test executables" }
    val executableEvidence = output.resolve("test-executables").createDirectories()
    val testImages = binaries.map { artifact ->
        val path = Path.of(artifact.str("executable")).toRealPath()
        require(path.startsWith(work.resolve("target").toRealPath())) { "Test executable escaped the owned build directory" }
        pe(path)
        Files.copy(path, executableEvidence.resolve(path.fileName))
        mapOf("name" to artifact["target"].obj().str("name"), "file" to path.name, "sha256" to sha256(path), "architecture" to "ARM64")
    }
    val testOutput = capture("cargo-tests.txt", *cargo, "--no-fail-fast", "--", "--color", "never", environment = environment)
    val summaries = Regex("test result: ok\\. ([0-9]+) passed; ([0-9]+) failed; ([0-9]+) ignored; [0-9]+ measured; ([0-9]+) filtered out;")
        .findAll(testOutput).map { match -> match.groupValues.drop(1).map(String::toInt) }.toList()
    val ignored = Regex("(?m)^test ([^ ]+) \\.\\.\\. ignored(?:,.*)?$").findAll(testOutput).map { it.groupValues[1] }.toSet()
    require(summaries.size >= 4 && summaries.sumOf { it[0] } >= MINIMUM_NATIVE_SESSION_PASSES && summaries.all { it[1] == 0 && it[3] == 0 } && summaries.sumOf { it[2] } == 3) { "Incomplete native test execution" }
    require(testOutput.contains("test tests::real_platform_credential_store_restores_an_owned_encrypted_session ... ok")) { "The real Windows credential store test did not execute" }
    require(ignored == setOf("parser::test_parser::tests::test_parse_gallery_detail", "parser::test_parser::tests::test_parse_info", "parser::test_parser::tests::test_parse_profile")) { "Unexpected ignored native tests" }
    require(testOutput.contains("test zip_file_writer_preserves_ownership_offsets_and_reports_output_errors ... ok")) { "Portable File writer did not execute" }
    capture("rust-clippy.txt", "cargo", "+$toolchain", "clippy", "--locked", "--manifest-path", cargoManifest, "-p", "ehviewer_core", "-p", "ehviewer_capi", "-p", "ehviewer_session", "--all-targets", "--target", WINDOWS_TARGET, "--", "-D", "warnings", environment = environment)
    val headers = messages.filter { it["reason"] == "build-script-executed" && (it["package_id"] as? String)?.contains("ehviewer_capi") == true }
        .map { Path.of(it.str("out_dir")).resolve("ehviewer_capi.h") }.filter { it.isRegularFile() }.distinct()
    require(headers.size == 1) { "Missing generated C ABI header" }
    verifyGalleryCapiHeader(headers.single().readText())
    Files.copy(headers.single(), output.resolve("ehviewer_capi.h"))
    val result = mapOf(
        "source_commit" to command("git", "rev-parse", "HEAD").trim(), "source_dirty" to dirty, "result" to "PASS",
        "target" to WINDOWS_TARGET, "jvm_architecture" to System.getProperty("os.arch"), "jdk" to System.getProperty("java.version"),
        "os" to System.getProperty("os.name"), "os_version" to System.getProperty("os.version"), "rust" to rustVersion.trim(),
        "vcpkg_baseline" to VCPKG_BASELINE, "manifest_sha256" to sha256(manifestFile), "dlls" to identities, "installed_copyrights" to notices,
        "test_images" to testImages, "passed" to summaries.sumOf { it[0] }, "existing_online_ignored" to ignored.sorted(), "filtered" to 0,
        "generated_header_sha256" to sha256(headers.single()), "packaged_jvm_window" to "NOT_VERIFIED", "windows_zip" to "NOT_VERIFIED", "complete_license_audit" to "NOT_VERIFIED",
    )
    output.resolve("windows-native-core.json").writeText(json(result))
    return result
}
