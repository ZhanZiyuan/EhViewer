package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Preserve the original two real Windows launches and every original assertion. */
fun windowsPackagedLaunches(image: Path, output: Path, version: String): List<Map<String, String>> {
    require(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") { "Use a native Windows ARM64 JVM and runner" }
    val launcher = image.resolve("EhViewer.exe")
    val runtime = image.resolve("runtime")
    val rust = image.resolve("app/resources/ehviewer_capi.dll")
    val renderApi = "SOFTWARE_COMPAT"
    return (1..2).map { attempt ->
        val report = output.resolve("launch-$attempt")
        val log = output.resolve("launch-$attempt.log")
        require(!report.exists(NOFOLLOW_LINKS) && !log.exists(NOFOLLOW_LINKS)) { "Use fresh owned launch evidence" }
        val encoded = Base64.getEncoder().encodeToString(report.toString().toByteArray(Charsets.UTF_8))
        try {
            command(launcher, "--m1-smoke-base64", encoded, timeout = Duration.ofMinutes(2), environment = mapOf("SKIKO_RENDER_API" to renderApi)).also { Files.writeString(log, it, CREATE_NEW) }
        } catch (failure: CommandFailure) {
            Files.writeString(log, failure.output, CREATE_NEW)
            throw failure
        }
        val proof = Properties().apply { report.resolve("launcher.properties").inputStream().use(::load) }
        require(proof.getProperty("architecture") == "aarch64" && proof.getProperty("java_version") == "25.0.5.0.2") { "Running JVM identity mismatch" }
        require(proof.getProperty("windows_code_page") == "65001" && java.nio.charset.Charset.forName(proof.getProperty("native_encoding")) == Charsets.UTF_8) { "Launcher did not activate its per-process UTF-8 manifest" }
        require(Path.of(proof.getProperty("java_home")).toRealPath() == runtime.toRealPath()) { "Launcher used an external JVM" }
        require(Path.of(proof.getProperty("rust_library")).toRealPath() == rust.toRealPath()) { "Caller loaded a different Rust DLL" }
        require(proof.getProperty("application_version") == version && proof.getProperty("rust_hash_results") == "2000" && proof.getProperty("rust_active_results") == "0") { "Incomplete packaged Rust ownership calls" }
        require(proof.getProperty("gallery_product_checks") == "3") { "Packaged detail/page/image regression did not execute" }
        require(proof.getProperty("account_product_checks") == "3") { "Packaged account decoder regression did not execute" }
        desktopBrowserReceipt(report, rust, image.resolve("app/resources/ehviewer_browser.exe"), setOf("aarch64"), "C_ABI")
        for (name in listOf("rust_ownership_errors", "rust_library_closed")) require(proof.getProperty(name) == "PASS") { "Missing ownership check: $name" }
        require(proof.getProperty("window_showing") == "true" && proof.getProperty("compose_frames").toInt() > 0 && proof.getProperty("render_api") == renderApi) { "Window did not render with the requested backend" }
        proof.stringPropertyNames().sorted().associateWith { proof.getProperty(it) }
    }
}

/** Inspect first, then write only regular bytes into a fresh owned root; ignore link attributes. */
fun extractStageZip(archive: Path, manifest: Map<String, Any?>, destination: Path): Path {
    verifyStageZip(archive, manifest)
    val requested = destination.toAbsolutePath().normalize()
    val output = requireNotNull(requested.parent).toRealPath().resolve(requested.fileName)
    require(!output.exists(NOFOLLOW_LINKS)) { "Use a fresh owned extraction directory" }
    Files.createDirectory(output)
    ZipFile(archive.toFile()).use { zip ->
        for (entry in zip.entries().asSequence()) {
            val file = output.resolve(stageZipName(entry.name)).normalize()
            require(file.startsWith(output)) { "ZIP extraction escapes its owned directory" }
            Files.createDirectories(file.parent)
            Files.newOutputStream(file, CREATE_NEW).use { target -> zip.getInputStream(entry).use { it.copyTo(target) } }
        }
    }
    for (entry in manifest["files"].array().map(Any?::obj)) {
        val file = output.resolve(entry.str("path"))
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == (entry["bytes"] as Number).toLong() && sha256(file) == entry["sha256"]) { "Extracted bytes differ from the inspected ZIP" }
    }
    return output.resolve("EhViewer")
}

/** Offline component inspection uses the same extractor but cannot attest native execution. */
fun stageZipExtractionEvidence(packageReport: Path, output: Path): Map<String, Any?> {
    val packaged = readJson(packageReport)
    val version = packaged.str("version")
    val name = releaseTargets.single { it.id == "windows-arm64" }.fileName(version)
    require(versionFromTag(version) == version && packaged["archive"] == name && packaged["target"] == "aarch64-pc-windows-msvc") { "Unexpected staged Windows archive identity" }
    val archive = packageReport.parent.resolve(name)
    val digest = sha256(archive)
    require(packaged["archive_evidence"].obj()["result"] == "PASS" && packaged["archive_evidence"].obj()["sha256"] == digest) { "Stage archive differs from its producer receipt" }
    val manifest = packaged.filterKeys { it !in setOf("archive", "archive_evidence", "native_files_verified") }
    val image = extractStageZip(archive, manifest, output)
    require(sha256(archive) == digest) { "Extraction changed its input archive" }
    return mapOf(
        "verification_scope" to "STAGED_ZIP_BYTE_EXTRACTION_ONLY", "input_source_commit" to packaged["source_commit"], "input_source_dirty" to packaged["source_dirty"],
        "version" to version, "archive_sha256" to digest, "extracted_image" to image.toString(), "payload_file_count" to manifest["files"].array().size,
        "native_execution" to "NOT_EXECUTED", "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
    )
}

fun windowsZipLaunch(packageReport: Path, runtimeReport: Path, output: Path): Map<String, Any?> {
    require(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") { "Use a native Windows ARM64 JVM and runner" }
    require(System.getenv("GITHUB_ACTIONS") != "true" || System.getenv("RUNNER_ARCH") == "ARM64") { "Expected the Windows ARM64 runner" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    val delivery = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    val packaged = readJson(packageReport)
    val measured = readJson(runtimeReport)
    require(packaged["source_commit"] == source && packaged["source_dirty"] == false && packaged["packager_source_dirty"] == false && packaged["version"] == delivery["version"] && packaged["android_version_code"] == delivery["android_version_code"] && packaged["target"] == "aarch64-pc-windows-msvc") { "A clean same-source Windows package is required" }
    require(packaged["verification_scope"] == "M2_WINDOWS_ARM64_RUNTIME_STAGE" && packaged["runtime_proof_sha256"] == sha256(runtimeReport)) { "Package runtime provenance changed" }
    require(measured["source_commit"] == source && measured["source_dirty"] == false && measured["result"] == "PASS" && measured["version"] == delivery["version"] && measured["target"] == "aarch64-pc-windows-msvc") { "A same-source measured runtime is required" }
    val name = releaseTargets.single { it.id == "windows-arm64" }.fileName(delivery.str("version"))
    require(packaged["archive"] == name && packaged["archive_evidence"].obj()["result"] == "PASS") { "Unexpected Windows ZIP identity" }
    val archive = packageReport.parent.resolve(name)
    val archiveSha = sha256(archive)
    require(archiveSha == packaged["archive_evidence"].obj()["sha256"]) { "ZIP differs from the producer's verified package" }
    val manifest = packaged.filterKeys { it !in setOf("archive", "archive_evidence", "native_files_verified") }
    verifyStageZip(archive, manifest)
    val requested = output.toAbsolutePath().normalize()
    val evidence = requireNotNull(requested.parent).toRealPath().resolve(requested.fileName)
    require(!evidence.exists(NOFOLLOW_LINKS) && !evidence.startsWith(packageReport.parent.toRealPath()) && !evidence.startsWith(runtimeReport.parent.toRealPath())) { "Use fresh extraction evidence separate from producer inputs" }
    Files.createDirectory(evidence)
    val image = extractStageZip(archive, manifest, evidence.resolve("ZIP 安装目录"))
    val native = measured["native_files"].array().map(Any?::obj).associate { stageZipName(it.str("path").replace('\\', '/')) to it.str("sha256") }
    val actual = Files.walk(image).use { walk -> walk.filter { it.isRegularFile() && it.extension.lowercase(Locale.ROOT) in setOf("exe", "dll") }.map { image.relativize(it).joinToString("/") }.toList().toSet() }
    require(actual == native.keys && native.size == packaged["native_files_verified"]) { "Extracted native inventory changed" }
    native.forEach { (path, expected) ->
        val file = image.resolve(path)
        require(sha256(file) == expected) { "Extracted native bytes changed" }
        file.inputStream().use { verifyWindowsArm64Pe(it.readNBytes(1 shl 20)) }
    }
    measured["installed_notices"].obj().forEach { (path, expected) -> require(sha256(image.resolve("app/resources/licenses/" + stageZipName(path.replace('\\', '/')))) == expected) { "Extracted notice bytes changed" } }
    val launches = windowsPackagedLaunches(image, evidence, delivery.str("version"))
    require(sha256(archive) == archiveSha) { "Launching changed the source ZIP" }
    val result = mapOf(
        "schema_version" to 1, "verification_scope" to "M2_WINDOWS_ARM64_ZIP_LAUNCH", "source_commit" to source, "source_dirty" to dirty, "version" to delivery["version"],
        "target" to "aarch64-pc-windows-msvc", "archive" to name, "archive_sha256" to archiveSha, "package_report_sha256" to sha256(packageReport), "runtime_report_sha256" to sha256(runtimeReport),
        "extracted_file_count" to manifest["files"].array().size, "native_files_verified" to native.size, "zip_extracted_launch" to "PASS", "launches" to launches,
        "minimum_os" to "NOT_VERIFIED", "hardware_acceleration" to "NOT_VERIFIED", "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
    )
    evidence.resolve("windows-arm64-zip-launch.json").writeText(json(result))
    return result
}
